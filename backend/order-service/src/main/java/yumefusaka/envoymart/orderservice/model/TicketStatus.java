package yumefusaka.envoymart.orderservice.model;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 工单状态与允许的流转。
 * <p>
 * 单向往前，唯一一条回边是 {@code RESOLVED → PROCESSING}（用户重开）。
 * {@code CLOSED} 是终态且<b>不可重开</b>：超时自动关闭会制造一批 CLOSED，
 * 若还能重开，用户半年后回来重开一条上下文早已散尽的工单，客服手上只有
 * 一句"还是不行"。新问题开新工单——把「重开」限制在 RESOLVED，
 * 是让状态机保持单向所付的最小代价。
 * <p>
 * {@code OPEN → RESOLVED} 是允许的：有些工单不需要来回（"请帮我取消订单"，
 * 客服办完直接标记解决），强行要求先回复一条只是形式主义。
 */
public enum TicketStatus {

    /** 待处理：等客服接手 */
    OPEN,
    /** 处理中：客服已介入 */
    PROCESSING,
    /** 已解决：等用户确认 */
    RESOLVED,
    /** 已关闭（终态） */
    CLOSED;

    private static final Map<TicketStatus, Set<TicketStatus>> ALLOWED = Map.of(
            OPEN, EnumSet.of(PROCESSING, RESOLVED, CLOSED),
            PROCESSING, EnumSet.of(RESOLVED, CLOSED),
            // 重开这条回边只在这里：已解决 ≠ 用户认可
            RESOLVED, EnumSet.of(PROCESSING, CLOSED),
            CLOSED, EnumSet.noneOf(TicketStatus.class));

    public boolean canTransitTo(TicketStatus target) {
        return ALLOWED.getOrDefault(this, Set.of()).contains(target);
    }

    public boolean isTerminal() {
        return this == CLOSED;
    }

    public static TicketStatus parse(String value) {
        try {
            return valueOf(value);
        } catch (IllegalArgumentException | NullPointerException e) {
            // null 单独接住：Enum.valueOf(null) 抛的是 NPE，会一路落进兜底变成 500。
            // 取值可能来自查询参数，缺参数是客户端的错，应当是 400
            throw new IllegalArgumentException("未知的工单状态：" + value);
        }
    }

    public String text() {
        return switch (this) {
            case OPEN -> "待处理";
            case PROCESSING -> "处理中";
            case RESOLVED -> "已解决";
            case CLOSED -> "已关闭";
        };
    }
}
