package yumefusaka.envoymart.orderservice.model.admin;

import lombok.Builder;
import lombok.Data;
import yumefusaka.envoymart.orderservice.entity.SupportTicketEntity;
import yumefusaka.envoymart.orderservice.model.TicketCategory;
import yumefusaka.envoymart.orderservice.model.TicketStatus;

import java.time.LocalDateTime;

/**
 * 管理端列表里的工单。
 * <p>
 * 比用户侧多一个 {@code userId}：客服要能看到「谁提的」——同一个人反复来单
 * 本身就说明问题不在单据上。用户侧不需要这一列，所以两边是两个类型，
 * 而不是一个类型加个注解按角色裁剪。
 */
@Data
@Builder
public class AdminTicketSummary {

    private Long id;
    private String ticketNo;
    /** 提交人 */
    private String userId;
    private String category;
    private String categoryText;
    private String title;
    private String status;
    private String statusText;
    private Long orderId;
    private String orderNo;
    private String lastReplyBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime resolvedAt;
    private LocalDateTime closedAt;

    public static AdminTicketSummary from(SupportTicketEntity entity) {
        return AdminTicketSummary.builder()
                .id(entity.getId())
                .ticketNo(entity.getTicketNo())
                .userId(entity.getUserId())
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
                .build();
    }
}
