package yumefusaka.envoymart.orderservice.model;

/**
 * 工单消息的发送方。
 * <p>
 * {@code SYSTEM} 是必要的第三种：超时自动关闭没有人工发起者，
 * 若把这条消息记成 ADMIN，追责时会去找一个不存在的操作人；
 * 若干脆不记，用户看到状态自己变了却没有任何解释。
 */
public enum TicketSenderType {

    USER,
    ADMIN,
    SYSTEM
}
