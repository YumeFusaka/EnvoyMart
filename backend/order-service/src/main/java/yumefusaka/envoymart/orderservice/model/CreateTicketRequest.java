package yumefusaka.envoymart.orderservice.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateTicketRequest {

    /** 见 {@code TicketCategory}，取值非法时 400 而不是静默落到 OTHER */
    @NotBlank(message = "请选择工单分类")
    private String category;

    @NotBlank(message = "请填写标题")
    @Size(max = 128, message = "标题最长 128 位")
    private String title;

    /** 第一条消息（用户的问题描述）。插入工单时一并落库 */
    @NotBlank(message = "请描述你遇到的问题")
    @Size(max = 2000, message = "描述最长 2000 位")
    private String content;

    /**
     * 关联订单，可空 —— 不是所有工单都关于某张订单。
     * <p>
     * 非空时服务端会校验它属于当前用户：让用户 A 的工单挂上用户 B 的订单号，
     * 客服按着这个线索去查，问的是完全不相关的人。
     */
    private Long orderId;
}
