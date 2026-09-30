package yumefusaka.envoymart.orderservice.controller;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import yumefusaka.envoymart.common.result.PageResult;
import yumefusaka.envoymart.common.result.Result;
import yumefusaka.envoymart.common.web.IdentityHeaderInterceptor;
import yumefusaka.envoymart.orderservice.model.CreateTicketRequest;
import yumefusaka.envoymart.orderservice.model.TicketDetailResponse;
import yumefusaka.envoymart.orderservice.model.TicketMessageRequest;
import yumefusaka.envoymart.orderservice.model.TicketReopenRequest;
import yumefusaka.envoymart.orderservice.model.TicketResponse;
import yumefusaka.envoymart.orderservice.service.SupportTicketService;

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

    public SupportTicketController(SupportTicketService ticketService) {
        this.ticketService = ticketService;
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
