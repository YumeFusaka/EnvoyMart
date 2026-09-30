package yumefusaka.envoymart.orderservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工单消息。
 * <p>
 * 消息**不驱动状态**：客服回复一句追加说明不会把 RESOLVED 拉回 PROCESSING。
 * 每一条状态转移都由显式动作（接手 / 标记解决 / 关闭 / 重开）发起，
 * 消息只是消息——否则「不客气」三个字都会改变工单的生命周期。
 */
@Data
@TableName("support_ticket_message")
public class SupportTicketMessageEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long ticketId;
    /** USER / ADMIN / SYSTEM，见 {@code TicketSenderType} */
    private String senderType;
    /** SYSTEM 消息为 null */
    private String senderId;
    private String content;
    private LocalDateTime createdAt;
}
