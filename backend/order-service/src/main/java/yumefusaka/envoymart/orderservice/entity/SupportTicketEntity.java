package yumefusaka.envoymart.orderservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 客服工单。
 * <p>
 * 工单本身只存"当前状态"，上下文全在 {@code support_ticket_message} 里——
 * 把对话塞进 JSON 列会让"按消息内容搜索"变成全表扫描，而工单的第一需求
 * 恰恰是客服要能搜到「之前有人提过同样的问题吗」。
 */
@Data
@TableName("support_ticket")
public class SupportTicketEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String ticketNo;
    private String userId;
    /** 可空；非空时必须是该用户自己的订单 */
    private Long orderId;
    private String orderNo;
    /** ORDER / REFUND / PRODUCT / OTHER，见 {@code TicketCategory} */
    private String category;
    private String title;
    /** OPEN / PROCESSING / RESOLVED / CLOSED，见 {@code TicketStatus} */
    private String status;
    /** USER / ADMIN，最后一条消息的发送方 */
    private String lastReplyBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime resolvedAt;
    private LocalDateTime closedAt;
    private String closeReason;
}
