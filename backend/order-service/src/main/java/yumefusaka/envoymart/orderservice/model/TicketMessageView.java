package yumefusaka.envoymart.orderservice.model;

import lombok.Builder;
import lombok.Data;
import yumefusaka.envoymart.orderservice.entity.SupportTicketMessageEntity;

import java.time.LocalDateTime;

@Data
@Builder
public class TicketMessageView {

    private Long id;
    private String senderType;
    /** SYSTEM 消息为 null；用户侧拿到的客服消息同样为 null，见 {@link #forCustomer} */
    private String senderId;
    private String content;
    private LocalDateTime createdAt;

    public static TicketMessageView from(SupportTicketMessageEntity entity) {
        return TicketMessageView.builder()
                .id(entity.getId())
                .senderType(entity.getSenderType())
                .senderId(entity.getSenderId())
                .content(entity.getContent())
                .createdAt(entity.getCreatedAt())
                .build();
    }

    /**
     * 用户侧的视图：把客服消息里的 {@code senderId} 抹掉。
     * <p>
     * 客服消息带的是值班客服的账号 id，用户拿它没有任何用处 —— 界面显示"客服"
     * 就够了，而回执里多一个内部账号标识，等于把一个用不上的内部字段送出去。
     * 客服侧（{@code AdminTicketDetail}）保留原值：那是追责与交接要看的。
     */
    public TicketMessageView forCustomer() {
        return TicketMessageView.builder()
                .id(id)
                .senderType(senderType)
                .senderId(TicketSenderType.ADMIN.name().equals(senderType) ? null : senderId)
                .content(content)
                .createdAt(createdAt)
                .build();
    }
}
