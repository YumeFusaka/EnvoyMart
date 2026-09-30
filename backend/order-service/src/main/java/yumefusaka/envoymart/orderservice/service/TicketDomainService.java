package yumefusaka.envoymart.orderservice.service;

import yumefusaka.envoymart.orderservice.entity.SupportTicketEntity;
import yumefusaka.envoymart.orderservice.model.TicketMessageView;
import yumefusaka.envoymart.orderservice.model.TicketSenderType;
import yumefusaka.envoymart.orderservice.model.TicketStatus;

import java.util.List;

/**
 * 工单域：状态转移、消息与归属校验的原子操作。
 * <p>
 * 用户侧与管理侧两个入口服务共用这一层，是为了让<b>同名动作在两边的实现完全一致</b> ——
 * 「关闭」在用户侧和客服侧走的是同一个 {@link #transit}（同一条状态机、同一种并发冲突语义），
 * 差异只在谁能关、以及关闭原因由谁提供。
 */
public interface TicketDomainService {

    /** 工单关闭的几种原因。关闭原因不允许为空：关闭是终结动作，事后必须答得出为什么 */
    String CLOSE_BY_USER_CONFIRMED = "用户确认已解决";
    String CLOSE_BY_USER_CANCELLED = "用户自行关闭";
    String CLOSE_BY_TIMEOUT = "超时未确认，系统自动关闭";

    /** 已解决的工单等用户确认的期限；超时由 {@link #autoCloseExpired} 自动关闭 */
    int RESOLVE_CONFIRM_DAYS = 7;

    /**
     * 按 id 取工单并校验归属 —— <b>用户侧入口必须走这个</b>。
     * <p>
     * 查不到与不属于自己返回同一个「工单不存在」：区分两者等于送给调用方一个
     * 探测他人工单是否存在的接口。
     */
    SupportTicketEntity requireOwned(Long ticketId, String userId);

    /**
     * 按 id 取工单，<b>不校验归属</b>。
     * <p>
     * <b>只给管理侧用。</b>客服处理的就是别人的工单，这条路径的授权由网关注入的
     * 管理员身份保证（{@code /admin} 路径段 + {@code @RequireAdmin}）。
     */
    SupportTicketEntity requireExists(Long ticketId);

    /** 工单的全部消息，时间正序 */
    List<TicketMessageView> messagesOf(Long ticketId);

    /**
     * 追加一条用户/客服消息，并同步刷新工单的最后发言方与活跃时间。
     * <p>
     * 消息与工单上的 {@code last_reply_by} 必须一起动：列表页靠它标「待客服回复 /
     * 待用户确认」，只插消息不刷新的话，客服回了十条列表上仍显示"等客服回"。
     */
    TicketMessageView appendMessage(SupportTicketEntity ticket, TicketSenderType senderType,
                                    String senderId, String content);

    /**
     * 追加一条系统消息，<b>不动 {@code last_reply_by}</b> ——
     * 那一列表达的是用户与客服之间的"球权"，系统不属于任何一方。
     */
    void appendSystemMessage(SupportTicketEntity ticket, String content);

    /**
     * 状态转移。校验 {@code canTransitTo} 之后用条件更新落库，
     * 0 行命中即有人在期间改了状态，抛 409 让人刷新重来而不是覆盖。
     * <p>
     * {@code closeReason} 只在目标是 {@code CLOSED} 时使用，其余传 null。
     */
    void transit(SupportTicketEntity ticket, TicketStatus target, String closeReason);

    /**
     * 批量关闭 {@code RESOLVED} 且已超过确认期限的工单，返回实际关闭条数。
     * <p>
     * 单批上限由调用方给：一次扫几万条会让这趟跑很久，而下一趟还要来。
     * 逐条独立判断、互不影响 —— 某一条恰好被用户抢先确认（条件更新 0 行）时，
     * 跳过它继续，不是让整批失败。
     */
    int autoCloseExpired(int batchSize);
}
