package yumefusaka.envoymart.orderservice.model;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 售后单状态与允许的流转。
 * <p>
 * 与订单状态机分开：售后的生命周期比订单短得多，而且它的「终态」不止一个
 * （完成 / 驳回 / 用户撤销），复用订单那套枚举会把两者都搞乱。
 */
public enum AfterSaleStatus {

    /** 已申请，待审核 */
    APPLIED,
    /** 审核通过，待用户寄回（仅退款类型会直接跳到退款中） */
    APPROVED,
    /** 退货中 */
    RETURNING,
    /** 商家已收到退货 */
    RECEIVED,
    /** 退款中 */
    REFUNDING,
    /** 已完成 */
    FINISHED,
    /** 已驳回 */
    REJECTED,
    /** 用户撤销 */
    CANCELLED;

    private static final Map<AfterSaleStatus, Set<AfterSaleStatus>> ALLOWED = Map.of(
            APPLIED, EnumSet.of(APPROVED, REJECTED, CANCELLED),
            APPROVED, EnumSet.of(RETURNING, REFUNDING, CANCELLED),
            RETURNING, EnumSet.of(RECEIVED, CANCELLED),
            RECEIVED, EnumSet.of(REFUNDING),
            REFUNDING, EnumSet.of(FINISHED),
            FINISHED, EnumSet.noneOf(AfterSaleStatus.class),
            REJECTED, EnumSet.noneOf(AfterSaleStatus.class),
            CANCELLED, EnumSet.noneOf(AfterSaleStatus.class));

    /**
     * 进行中的状态（尚未终结）。同一订单行不允许同时有两个。
     * <p>
     * 供条件查询用（{@code in (...)}）：取消订单前要查「这单有没有在途售后」，
     * 有的话退款已经被售后链路接管，取消再退一次就是重复打款。
     */
    public static final List<String> ACTIVE_NAMES = List.of(
            APPLIED.name(), APPROVED.name(), RETURNING.name(), RECEIVED.name(), REFUNDING.name());

    public boolean canTransitTo(AfterSaleStatus target) {
        return ALLOWED.getOrDefault(this, Set.of()).contains(target);
    }

    public boolean isActive() {
        return ACTIVE_NAMES.contains(name());
    }

    public boolean isTerminal() {
        return this == FINISHED || this == REJECTED || this == CANCELLED;
    }

    public static AfterSaleStatus parse(String value) {
        try {
            return valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("未知的售后状态：" + value);
        }
    }

    public String text() {
        return switch (this) {
            case APPLIED -> "待审核";
            case APPROVED -> "待寄回";
            case RETURNING -> "退货中";
            case RECEIVED -> "已收货";
            case REFUNDING -> "退款中";
            case FINISHED -> "已完成";
            case REJECTED -> "已驳回";
            case CANCELLED -> "已撤销";
        };
    }
}
