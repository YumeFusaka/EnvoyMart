package yumefusaka.envoymart.orderservice.controller;

import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import yumefusaka.envoymart.common.result.PageResult;
import yumefusaka.envoymart.common.result.Result;
import yumefusaka.envoymart.common.web.IdentityHeaderInterceptor;
import yumefusaka.envoymart.orderservice.model.CreateTicketRequest;
import yumefusaka.envoymart.orderservice.model.TicketDetailResponse;
import yumefusaka.envoymart.orderservice.model.TicketMessageRequest;
import yumefusaka.envoymart.orderservice.model.TicketReopenRequest;
import yumefusaka.envoymart.orderservice.model.TicketResponse;
import yumefusaka.envoymart.orderservice.model.TicketSummary;
import yumefusaka.envoymart.orderservice.service.SupportTicketService;
import yumefusaka.envoymart.orderservice.service.TicketNotifier;
import yumefusaka.envoymart.orderservice.service.TicketStreamHub;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 客服工单的用户侧接口。
 * <p>
 * 管理侧动作在 {@link TicketAdminController}（{@code /tickets/admin/tickets}），
 * 两边不共用路径也不共用 DTO：管理侧要看到提交人、要按用户筛选、
 * 关闭时原因必填，这些用户侧都不该有 —— 共用一个类型只会让两边互相迁就，
 * 最后某一侧多出几个永远为空的字段。
 * <p>
 * {@code userId} 一律取自网关注入的身份头，<b>没有一个接口接受 userId 参数</b>：
 * 工单里装着订单号与沟通记录，接受调用方自报身份等于把别人的工单借给他看。
 */
@RestController
@RequestMapping("/tickets")
public class SupportTicketController {

    private final SupportTicketService ticketService;
    private final TicketStreamHub streamHub;
    private final TicketNotifier notifier;

    /**
     * 心跳跑在虚拟线程上：一个用户一条连接，给每条连接排一个真线程是浪费，
     * 而定时任务的语义又要求「每 25 秒一次」而不是「每次请求顺带」。
     */
    private final ScheduledExecutorService heartbeat =
            Executors.newScheduledThreadPool(1, Thread.ofVirtual().factory());

    public SupportTicketController(SupportTicketService ticketService,
                                   TicketStreamHub streamHub,
                                   TicketNotifier notifier) {
        this.ticketService = ticketService;
        this.streamHub = streamHub;
        this.notifier = notifier;
    }

    @PostMapping
    public Result<TicketDetailResponse> create(
            @RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId,
            @Valid @RequestBody CreateTicketRequest request) {
        return Result.success(ticketService.create(userId, request));
    }

    /** 我的工单：默认按最近活跃排序，可按状态筛选（取值非法 400） */
    @GetMapping
    public Result<PageResult<TicketResponse>> listMine(
            @RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size) {
        return Result.success(ticketService.listMine(userId, status, page, size));
    }

    /**
     * 计数摘要：列表页的筛选页签与顶栏角标共用。
     * <p>
     * 放在 {@code /{id}} 之前只是为了让读的人先看到它——<b>能不能命中不靠声明顺序</b>，
     * Spring 的路径匹配里字面量段本来就优先于模板段（{@code /reviews/mine} 也是这么活下来的）。
     */
    @GetMapping("/summary")
    public Result<TicketSummary> summary(
            @RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId) {
        return Result.success(ticketService.summary(userId));
    }

    /**
     * 「等你回应」的 SSE 推送。
     * <p>
     * 与 {@code /tickets/summary} 是同一个判据、同一段 SQL，区别只在<b>谁先动</b>：
     * 拉取要用户手动刷新才看得到客服刚回的话，而「客服回了、我在页面上却不知道」
     * 恰恰是工单最需要消除的状态。所以这里推送，不再让用户靠刷新猜。
     * <p>
     * <b>只在建立时推一次、之后由写路径驱动</b>：建立时的那一次是初始快照（连上就有数，
     * 不必再拉一次 summary）；此后的每一次推送都由客服回复 / 用户发消息 / 关闭 / 重开
     * 这些真实写动作触发。心跳只保活、不带数据 —— 它若也带计数，就会变成一种
     * 「每 25 秒重算一次」的轮询，而这条链路特意不是轮询。
     * <p>
     * 事件名固定为 {@code awaiting}，载荷是 {@code {awaitingMe, ticketIds}}。
     * 用户身份取自网关身份头，<b>连接按用户隔离</b>：每个连接只会收到本用户的数据。
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId) {
        SseEmitter emitter = streamHub.register(userId);

        // 立起停止旗并由三个终结回调共享，避免「心跳与断开」互相看不见对方的动作：
        // 连接被关掉后若心跳还在往它写，日志会每隔 25 秒刷一条无意义的失败
        AtomicBoolean closed = new AtomicBoolean(false);
        Runnable stop = () -> closed.set(true);
        emitter.onCompletion(stop);
        emitter.onTimeout(stop);
        emitter.onError(e -> closed.set(true));

        // 初始快照：连上即知道当前有没有等你回应的工单
        notifier.notifyAwaiting(userId);

        // 心跳：注释帧（以冒号开头）对 EventSource 不可见，只为了让中间的代理与浏览器
        // 认出「这条连接还活着」。带数据的心跳是轮询，不带数据的心跳才是保活
        heartbeat.scheduleAtFixedRate(() -> {
            if (closed.get()) {
                return;
            }
            try {
                emitter.send(SseEmitter.event().comment("keep-alive"));
            } catch (IOException | IllegalStateException e) {
                closed.set(true);
            }
        }, TicketStreamHub.HEARTBEAT_MILLIS, TicketStreamHub.HEARTBEAT_MILLIS, TimeUnit.MILLISECONDS);

        return emitter;
    }

    @GetMapping("/{id}")
    public Result<TicketDetailResponse> detail(
            @RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId,
            @PathVariable("id") Long id) {
        return Result.success(ticketService.detail(userId, id));
    }

    /** 追加说明。工单已关闭时 409 —— 关闭是终态，新问题请新开工单 */
    @PostMapping("/{id}/messages")
    public Result<TicketDetailResponse> addMessage(
            @RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId,
            @PathVariable("id") Long id,
            @Valid @RequestBody TicketMessageRequest request) {
        return Result.success(ticketService.addMessage(userId, id, request.getContent()));
    }

    /** 关闭自己的工单。已解决的=确认解决，其余=自行撤销，用户不需要填原因 */
    @PostMapping("/{id}/close")
    public Result<TicketDetailResponse> close(
            @RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId,
            @PathVariable("id") Long id) {
        return Result.success(ticketService.close(userId, id));
    }

    /** 重开：只对「已解决」的工单开放，可附一句「哪个问题还在」 */
    @PostMapping("/{id}/reopen")
    public Result<TicketDetailResponse> reopen(
            @RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId,
            @PathVariable("id") Long id,
            @Valid @RequestBody(required = false) TicketReopenRequest request) {
        return Result.success(ticketService.reopen(userId, id,
                request == null ? null : request.getContent()));
    }
}
