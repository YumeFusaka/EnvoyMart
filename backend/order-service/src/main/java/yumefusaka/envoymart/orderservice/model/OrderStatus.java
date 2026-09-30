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
            // REFUNDING 有两条出口：全退完 → REFUNDED；只退了部分行 → 回到 RECEIVED。
            // 订单状态是「所有订单行事实的投影」，不是独立事实 —— 一单两件只退一件时，
            // 退完就该回到正常状态继续，而不是把整单标成已退款。回到 RECEIVED 而不是
            // COMPLETED，是因为 COMPLETED 是超时任务从 RECEIVED 推进的，让任务重新走一遍
            // 即可，不影响语义（申诉期按 receivedAt 计算，与状态无关）
            REFUNDING, EnumSet.of(REFUNDED, RECEIVED),
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

    /**
     * 状态的中文说明，展示给用户。
     * <p>
     * 放在服务端而不是前端：状态集合会变，散在客户端的那份迟早与后端不一致 ——
     * 而那时用户看到的是一个没人认识的状态名。
     * <p>
     * 放在枚举上而不是某个 service 的私有方法里：订单状态的中文说法原先写在
     * {@code OrderDomainServiceImpl} 内部，于是管理端要显示状态时只有两条路——
     * 复制那份 switch，或者调一个只对买家开放的接口。它属于状态集合本身。
     * （售后那边 {@link AfterSaleStatus#text()} 一直是这么放的。）
     */
    public String text() {
        return switch (this) {
            case CREATED -> "待支付";
            case PAID -> "待发货";
            case SHIPPED -> "已发货";
            case RECEIVED -> "已收货";
            case COMPLETED -> "已完成";
            case CANCELLED -> "已取消";
            case CLOSED -> "已关闭";
            case REFUNDING -> "退款中";
            case REFUNDED -> "已退款";
        };
    }
}
