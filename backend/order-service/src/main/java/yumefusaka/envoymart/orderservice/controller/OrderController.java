package yumefusaka.envoymart.orderservice.controller;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import yumefusaka.envoymart.common.result.PageResult;
import yumefusaka.envoymart.common.result.Result;
import yumefusaka.envoymart.common.web.IdentityHeaderInterceptor;
import yumefusaka.envoymart.orderservice.model.CheckoutRequest;
import yumefusaka.envoymart.contract.LogisticsResponse;
import yumefusaka.envoymart.contract.OrderResponse;
import yumefusaka.envoymart.orderservice.model.OrderListQuery;
import yumefusaka.envoymart.orderservice.model.OrderPreviewRequest;
import yumefusaka.envoymart.orderservice.model.OrderPreviewResponse;
import yumefusaka.envoymart.orderservice.model.OrderTabCount;
import yumefusaka.envoymart.orderservice.service.OrderDomainService;

import java.util.List;

/**
 * 订单的用户侧接口。
 * <p>
 * 购物车已拆到 {@link CartController}：它们是两个聚合，状态机与生命周期都不同。
 * <p>
 * 管理侧动作不在这里：发货原先以 {@code /orders/internal/{id}/ship} 的形式挂在下面，
 * 现已搬到 {@link OrderAdminController}（{@code /orders/admin}）——
 * 那条路走网关拿身份、按角色判定、把发货人写进流水，而 internal 那条三样都没有。
 */
@RestController
@RequestMapping("/orders")
public class OrderController {

    private final OrderDomainService orderDomainService;

    public OrderController(OrderDomainService orderDomainService) {
        this.orderDomainService = orderDomainService;
    }

    @PostMapping("/checkout")
    public Result<OrderResponse> checkout(
            @RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId,
            @Valid @RequestBody CheckoutRequest request) {
        return Result.success(orderDomainService.checkout(userId, request));
    }

    /**
     * 结算页试算。<b>不改任何状态</b>：商品明细取自服务端（购物车已勾选条目 + SKU 快照），
     * 与 {@link #checkout} 是同一段装配代码 —— 于是「预览说券能用、提交却被拒」
     * 这一类分叉不可能发生（两边问的是同一批商品、同一份类目路径）。
     * <p>
     * 请求体可省：不传券就是「我这单原价多少、我有哪些券可用」。
     */
    @PostMapping("/preview")
    public Result<OrderPreviewResponse> preview(
            @RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId,
            @RequestBody(required = false) OrderPreviewRequest request) {
        return Result.success(orderDomainService.preview(userId, request));
    }

    /**
     * 我的订单，按状态页签分页。
     * <p>
     * 字面量路径 {@code /summary} 与这里的根路径都不与 {@code /{id}} 冲突：
     * Spring 的字面量优先于模板。真踩过的坑是另一回事 —— 新接口在<b>跑着旧 class
     * 的进程</b>里不存在时，{@code /orders/summary} 会被匹配到 {@code /{id}} 上，
     * 报一句「参数格式不正确：id」，把「路由没生效」伪装成「参数写错了」。
     */
    @GetMapping
    public Result<PageResult<OrderResponse>> listOrders(
            @RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId,
            OrderListQuery query) {
        return Result.success(orderDomainService.listOrders(userId, query));
    }

    /** 各页签的数量，给角标用。与列表同一口径（页签成员关系只在 OrderTab 里定义一次） */
    @GetMapping("/summary")
    public Result<List<OrderTabCount>> summary(
            @RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId) {
        return Result.success(orderDomainService.orderSummary(userId));
    }

    @GetMapping("/{id}")
    public Result<OrderResponse> detail(
            @RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId,
            @PathVariable("id") Long id) {
        return Result.success(orderDomainService.getOrder(userId, id));
    }

    @PostMapping("/{id}/cancel")
    public Result<OrderResponse> cancelOrder(
            @RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId,
            @PathVariable("id") Long id) {
        return Result.success(orderDomainService.cancelOrder(userId, id));
    }

    @PostMapping("/by-no/{orderNo}/cancel")
    public Result<OrderResponse> cancelOrderByNo(
            @RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId,
            @PathVariable("orderNo") String orderNo) {
        return Result.success(orderDomainService.cancelOrderByNo(userId, orderNo));
    }

    /**
     * 确认收货。用户动作 —— 它是售后与评价的前置条件：
     * 没有这一步，订单永远停在「已发货」，政策引擎要求的「已收货」不可达。
     */
    @PostMapping("/{id}/receive")
    public Result<OrderResponse> confirmReceipt(
            @RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId,
            @PathVariable("id") Long id) {
        return Result.success(orderDomainService.confirmReceipt(userId, id));
    }

    @GetMapping("/{id}/logistics")
    public Result<LogisticsResponse> logistics(
            @RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId,
            @PathVariable("id") Long id) {
        return Result.success(orderDomainService.getLogistics(userId, id));
    }

    /**
     * 内部接口：某用户最早一次已收货订单的时间，给评价侧判「新账号刷评」用。
     * <p>
     * <b>不带身份头、也不读身份</b>：它是服务间通道，网关对 {@code /orders/internal/}
     * 一律 404（见 {@code JwtGatewayFilter.INTERNAL_ONLY_PREFIXES}），只有 Feign 直连可达。
     * 参数是 userId 而不是「当前用户」：调用方（review-service）要判的是「写评价的那个人」，
     * 而它手上已经有经过鉴权的 userId，不需要也不能让订单服务再猜一次。
     * <p>
     * 返回 {@code data} 为 null 表示「从未有过收货」，调用方按新账号对待。
     */
    @GetMapping("/internal/users/{userId}/first-received")
    public Result<java.time.LocalDateTime> firstReceivedAt(@PathVariable("userId") String userId) {
        return Result.success(orderDomainService.firstReceivedAt(userId));
    }
}
