package yumefusaka.envoymart.orderservice.model;

import lombok.Builder;
import lombok.Data;
import yumefusaka.envoymart.orderservice.entity.SupportTicketEntity;

import java.time.LocalDateTime;

/**
 * 用户侧看到的工单。
 * <p>
 * <b>不带 userId</b>：这是"我的工单"列表，每一条都是自己的，
 * 带上用户 id 只会让前端有机会把它显示出来（"提交人：u1002"）。
 * 管理侧要这一列，所以那边有独立的类型，而不是给这个加个字段两边共用。
 */
@Data
@Builder
public class TicketResponse {

    private Long id;
    private String ticketNo;
    private String category;
    private String categoryText;
    private String title;
    private String status;
    private String statusText;
    private Long orderId;
    private String orderNo;
    /** USER / ADMIN：最后一条消息是谁发的。前端据此标"待客服回复 / 待您确认" */
    private String lastReplyBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime resolvedAt;
    private LocalDateTime closedAt;
    private String closeReason;

    public static TicketResponse from(SupportTicketEntity entity) {
        return TicketResponse.builder()
                .id(entity.getId())
                .ticketNo(entity.getTicketNo())
                .category(entity.getCategory())
                .categoryText(TicketCategory.parse(entity.getCategory()).text())
                .title(entity.getTitle())
                .status(entity.getStatus())
                .statusText(TicketStatus.parse(entity.getStatus()).text())
                .orderId(entity.getOrderId())
                .orderNo(entity.getOrderNo())
                .lastReplyBy(entity.getLastReplyBy())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .resolvedAt(entity.getResolvedAt())
                .closedAt(entity.getClosedAt())
                .closeReason(entity.getCloseReason())
                .build();
    }
}
