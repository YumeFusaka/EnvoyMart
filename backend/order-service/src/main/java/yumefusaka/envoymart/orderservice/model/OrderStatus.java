package yumefusaka.envoymart.orderservice.model;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 订单状态与允许的流转。
 * <p>
 * 在引入这个枚举之前，状态是散落在三个服务里的字符串字面量（{@code "DELIVERING"}、
 * {@code "PAID"}、{@code "CANCELLED"}），而且**下单直接置 DELIVERING（"配送中"）** ——
 * 于是支付服务不得不把 {@code PENDING} 和 {@code DELIVERING} 都列为"可支付状态"
 * 来兜住这个错误建模。改一个状态要在全仓库搜字符串，漏一处不会有任何编译提示。
 * <p>
 * 流转规则集中在这里，非法流转一律拒绝而不是静默更新 —— 后者会让订单停在
 * 一个谁也没预期的状态上，而排查时没有任何线索。
 */
public enum OrderStatus {

    /** 待支付 */
    CREATED,
    /** 已支付，待发货 */
    PAID,
    /** 已发货 */
    SHIPPED,
    /** 已收货 */
    RECEIVED,
    /** 已完成 */
    COMPLETED,
    /** 已取消（用户主动） */
    CANCELLED,
    /** 已关闭（超时未支付） */
    CLOSED,
    /** 退款中 */
    REFUNDING,
    /** 已退款 */
    REFUNDED;

    private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED = Map.of(
            CREATED, EnumSet.of(PAID, CANCELLED, CLOSED),
            PAID, EnumSet.of(SHIPPED, REFUNDING),
            SHIPPED, EnumSet.of(RECEIVED, REFUNDING),
            RECEIVED, EnumSet.of(COMPLETED, REFUNDING),
            COMPLETED, EnumSet.of(REFUNDING),
            REFUNDING, EnumSet.of(REFUNDED),
            CANCELLED, EnumSet.noneOf(OrderStatus.class),
            CLOSED, EnumSet.noneOf(OrderStatus.class),
            REFUNDED, EnumSet.noneOf(OrderStatus.class));

    /** 终态：不会再变，任何尝试流转都直接拒绝 */
    private static final Set<OrderStatus> TERMINAL =
            EnumSet.of(CANCELLED, CLOSED, REFUNDED);

    public boolean canTransitTo(OrderStatus target) {
        return ALLOWED.getOrDefault(this, Set.of()).contains(target);
    }

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    /** 未支付：可以取消、也可以被超时关单 */
    public boolean isUnpaid() {
        return this == CREATED;
    }

    public static OrderStatus parse(String value) {
        try {
            return valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("未知的订单状态：" + value);
        }
    }
}
