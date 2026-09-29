package yumefusaka.envoymart.contract;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单。金额单位「分」，与商品服务一致。
 * <p>
 * <b>由 order-service 发出，AI 服务的订单工具消费。</b>
 * <p>
 * 消费方要展示状态时用 {@link #statusText} 而不是 {@link #status}：
 * 后者是枚举名（{@code PAID}），直接拼进回复里会变成「订单状态: PAID」——
 * 模型会照说，用户会愣一下。
 */
@Data
@Builder
public class OrderResponse {

    private Long id;
    private String orderNo;

    /** 枚举名，如 CREATED */
    private String status;

    /** 状态的中文说明。前端直接展示它，不必自己维护一份状态字典 */
    private String statusText;

    private Long totalAmount;
    private Long freightAmount;
    private Long discountAmount;
    private Long payAmount;

    private String receiverName;
    private String receiverPhone;
    private String receiverProvince;
    private String receiverCity;
    private String receiverDistrict;
    private String receiverDetail;

    /** 支付截止时间。前端据此显示倒计时 */
    private LocalDateTime expireAt;
    private LocalDateTime createdAt;
    private LocalDateTime paidAt;
    private LocalDateTime shippedAt;
    private LocalDateTime receivedAt;
    private LocalDateTime closedAt;

    private String remark;
    private String cancelReason;

    private List<OrderItemResponse> items;
}
