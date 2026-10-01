package yumefusaka.envoymart.orderservice.model;

import lombok.Data;

/**
 * 我的工单的计数摘要。列表页的筛选页签与顶栏角标共用这一份数字。
 * <p>
 * <b>{@code awaitingMe} 不是"未读"</b>：这个系统里没有已读位，也不该有——
 * 工单是异步对话，"现在轮到谁说话"是一个由状态和最后发言方唯一确定的事实，
 * 而"读没读过"要靠一个会过期、会因换设备而失真的标记来猜。
 * 所以这里的口径是：<b>工单未关闭，且客服已回过话（或已标记解决等你确认）</b>。
 * 用户看过但没回，它仍然是"等你回应"——因为那确实是事实。
 * <p>
 * 五个计数由一条聚合 SQL 查出来，而不是五个 {@code count(*)}：
 * 页签和角标会同时上屏，分成五次查询就会取到五个不同时刻的快照，
 * 而"全部"和四个状态之和对不上的界面，用户第一眼就会看见。
 * <p>
 * 需要无参构造器与 setter：这个类型的实例由 MyBatis 直接映射产生
 * （{@code SupportTicketMapper.countByUser}），加 {@code @Builder} 会把它们去掉。
 */
@Data
public class TicketSummary {

    /** 全部工单数（含已关闭） */
    private long total;
    private long open;
    private long processing;
    private long resolved;
    private long closed;
    /** 等你回应的条数：未关闭且客服已回过话（或已标记解决） */
    private long awaitingMe;
}
