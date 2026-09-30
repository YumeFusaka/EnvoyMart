package yumefusaka.envoymart.orderservice.controller;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import yumefusaka.envoymart.common.result.PageResult;
import yumefusaka.envoymart.common.result.Result;
import yumefusaka.envoymart.common.web.IdentityHeaderInterceptor;
import yumefusaka.envoymart.common.web.RequireAdmin;
import yumefusaka.envoymart.orderservice.model.admin.AdminOrderDetail;
import yumefusaka.envoymart.orderservice.model.admin.AdminOrderQuery;
import yumefusaka.envoymart.orderservice.model.admin.AdminOrderRemarkRequest;
import yumefusaka.envoymart.orderservice.model.admin.AdminOrderSummary;
import yumefusaka.envoymart.orderservice.model.admin.AdminShipRequest;
import yumefusaka.envoymart.orderservice.service.OrderAdminService;

/**
 * 订单的管理接口。
 * <p>
 * <b>路径挂在 {@code /orders/admin} 下</b>，而不是单开一个 {@code /admin/orders} ——
 * 网关的公开规则按前缀写、路由也按前缀配（{@code /orders/**} 已有），
 * 挂在原有前缀下路由不用动，而「管理接口不是公开资源」由网关的 {@code /admin} 段判据
 * 统一排除（见 {@code JwtGatewayFilter}）。这是批次 A（商品管理）定下的约定，
 * 这里跟着走，不再另立一套。
 * <p>
 * <b>发货为什么从 {@code /internal/} 搬到了这里</b>：那条路原先只有一道门 ——
 * 网关对 {@code /orders/internal/} 一律 404。它是服务间通道，<b>不表达「谁在操作」</b>，
 * 于是发货流水里的操作人永远是空的。管理台需要的是有身份、有授权、可追责的入口，
 * 所以另开这一条，同时把那一条删掉：同一次状态流转留两个入口，
 * 迟早有人从错的那个进，而错了不会有任何症状。
 */
@RequireAdmin
@RestController
@RequestMapping("/orders/admin")
public class OrderAdminController {

    private final OrderAdminService adminService;

    public OrderAdminController(OrderAdminService adminService) {
        this.adminService = adminService;
    }

    /** 订单列表：可按状态 / 用户 / 订单号 / 收货人 / 下单时间范围筛选 */
    @GetMapping("/orders")
    public Result<PageResult<AdminOrderSummary>> list(AdminOrderQuery query) {
        return Result.success(adminService.list(query));
    }

    /** 详情：订单行、状态流水、履约轨迹全在里面 */
    @GetMapping("/orders/{id}")
    public Result<AdminOrderDetail> detail(@PathVariable("id") Long id) {
        return Result.success(adminService.detail(id));
    }

    /**
     * 发货。
     * <p>
     * 提交的是承运商与运单号，<b>不是订单状态</b> —— 让调用方直接改状态
     * 等于把状态机交给前端；这里的状态只能由「已支付」条件更新到「已发货」。
     */
    @PostMapping("/orders/{id}/ship")
    public Result<AdminOrderSummary> ship(
            @PathVariable("id") Long id,
            @Valid @RequestBody AdminShipRequest request,
            @RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String operatorId) {
        return Result.success(adminService.ship(id, request, operatorId));
    }

    /**
     * 写商家备注。
     * <p>
     * 用 {@code PUT} 而不是 {@code POST}：备注是订单上的一个字段，重复提交同一个值
     * 结果完全一样。传空串即清除。
     * <p>
     * 备注走请求体而不是查询参数：它常常带着中文与换行，塞进 URL 会被各种
     * 编码层折腾一遍（本项目在 Git Bash 下真的踩到过命令行参数被按 GBK 转码）。
     */
    @PutMapping("/orders/{id}/remark")
    public Result<AdminOrderSummary> remark(@PathVariable("id") Long id,
                                            @Valid @RequestBody AdminOrderRemarkRequest request) {
        return Result.success(adminService.remark(id, request.getRemark()));
    }
}
