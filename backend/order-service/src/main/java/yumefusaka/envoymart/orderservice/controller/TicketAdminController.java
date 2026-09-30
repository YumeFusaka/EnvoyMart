package yumefusaka.envoymart.orderservice.controller;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import yumefusaka.envoymart.common.result.PageResult;
import yumefusaka.envoymart.common.result.Result;
import yumefusaka.envoymart.common.web.IdentityHeaderInterceptor;
import yumefusaka.envoymart.common.web.RequireAdmin;
import yumefusaka.envoymart.orderservice.model.admin.AdminTicketCloseRequest;
import yumefusaka.envoymart.orderservice.model.admin.AdminTicketDetail;
import yumefusaka.envoymart.orderservice.model.admin.AdminTicketQuery;
import yumefusaka.envoymart.orderservice.model.admin.AdminTicketReplyRequest;
import yumefusaka.envoymart.orderservice.model.admin.AdminTicketResolveRequest;
import yumefusaka.envoymart.orderservice.model.admin.AdminTicketSummary;
import yumefusaka.envoymart.orderservice.service.TicketAdminService;

/**
 * 客服工单的管理接口。
 * <p>
 * <b>路径挂在 {@code /tickets/admin} 下</b>，而不是 {@code /admin/tickets} ——
 * 批次 A（商品管理）定下的约定是「管理接口挂在原前缀的 {@code /admin} 段下」，
 * 网关的 {@code ADMIN_SEGMENT} 段判据（{@code /admin(?:/|$)}）自动把它排除在公开资源外，
 * 路由也只需按 {@code /tickets/**} 配一条。这里跟着走，不另立一套。
 * <p>
 * 三个写动作对应状态机上的三条边，<b>没有「直接改状态」的入口</b>：
 * 能提交任意目标状态的接口等于把状态机交给调用方，而状态机是这个域里唯一
 * 说得清「工单现在处于哪一步」的东西。
 */
@RequireAdmin
@RestController
@RequestMapping("/tickets/admin/tickets")
public class TicketAdminController {

    private final TicketAdminService adminService;

    public TicketAdminController(TicketAdminService adminService) {
        this.adminService = adminService;
    }

    /**
     * 工单列表。
     * <p>
     * {@code awaitingAdmin=true} 只看「最后一条消息来自用户」的 ——
     * 客服上班第一件事是捞自己的欠账，而不是从第一页翻到最后。
     */
    @GetMapping
    public Result<PageResult<AdminTicketSummary>> list(AdminTicketQuery query) {
        return Result.success(adminService.list(query));
    }

    @GetMapping("/{id}")
    public Result<AdminTicketDetail> detail(@PathVariable("id") Long id) {
        return Result.success(adminService.detail(id));
    }

    /** 回复用户。对「待处理」的工单，回复即接手（同时推进为「处理中」） */
    @PostMapping("/{id}/reply")
    public Result<AdminTicketDetail> reply(
            @PathVariable("id") Long id,
            @Valid @RequestBody AdminTicketReplyRequest request,
            @RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String operatorId) {
        return Result.success(adminService.reply(id, request.getContent(), operatorId));
    }

    /** 标记解决。可附一段说明，填不填都由客服决定 */
    @PostMapping("/{id}/resolve")
    public Result<AdminTicketDetail> resolve(
            @PathVariable("id") Long id,
            @Valid @RequestBody(required = false) AdminTicketResolveRequest request,
            @RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String operatorId) {
        return Result.success(adminService.resolve(id,
                request == null ? null : request.getContent(), operatorId));
    }

    /**
     * 关闭工单，<b>原因必填</b>。
     * <p>
     * 与用户自行关闭不对称是有意的：用户关自己的工单不需要解释，
     * 而终结别人的诉求时，对方至少要看到是谁、为什么。
     */
    @PostMapping("/{id}/close")
    public Result<AdminTicketDetail> close(
            @PathVariable("id") Long id,
            @Valid @RequestBody AdminTicketCloseRequest request,
            @RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String operatorId) {
        return Result.success(adminService.close(id, request.getReason(), operatorId));
    }
}
