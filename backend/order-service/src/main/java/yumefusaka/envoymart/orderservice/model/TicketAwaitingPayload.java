package yumefusaka.envoymart.orderservice.model;

/**
 * 挂在 SSE 连接上的「等你回应」推送载荷。
 *
 * <p><b>与 {@code /tickets/summary} 的 {@code awaitingMe} 是同一个判据</b>，而且是
 * 同一段 SQL 算出来的（{@code countByUser}）—— 不存在第二份口径。推送与拉取若各写
 * 一遍「未关闭 && 客服已回过话」，两条路迟早分叉：用户看到角标是 3、点进去列表里
 * 只有 2 条，而两边单看都正常。
 *
 * <p>带上 {@code ticketIds} 而不只是数字：列表页收到推送后要能把「哪几条刚变成等我回应」
 * 高亮出来，只有一个计数就只能整页重拉。id 列表与计数来自同一时刻的查询。
 *
 * <p>刻意不带 {@code userId} 字段：这是推给本人连接的数据，用户 id 只会是个
 * 没处安放、又容易被前端顺手显示出来的字段。
 */
public record TicketAwaitingPayload(long awaitingMe, java.util.List<Long> ticketIds) {
}
