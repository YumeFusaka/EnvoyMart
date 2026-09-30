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
    /** SYSTEM 消息为 null */
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
}
