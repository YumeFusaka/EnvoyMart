package yumefusaka.envoymart.contract;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 退款请求。
 * <p>
 * <b>由 order-service 发出，payment-service 消费。</b>
 * <p>
 * {@code amount} 为空表示「退剩余全部」而不是「退 0」——把这两个语义混在一起，
 * 后果是用户申请全额退款时实际退到 0 元且不报错。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RefundRequest {

    @NotNull(message = "orderId 不能为空")
    private Long orderId;

    /** 由售后退款时关联售后单；为空表示订单取消等主动退款 */
    private Long afterSaleId;

    /**
     * 主动退款的幂等键（{@code afterSaleId} 为空时使用）。
     * <p>
     * 同一支付单上，同一 bizNo 只退一次；重复请求返回首次的结果。
     * 「上次其实退成功了、只是响应在路上丢了」是重试最常见的触发场景 ——
     * 售后退款用 afterSaleId 兜住了这件事，主动退款没有它就只能是裸奔。
     * 约定格式：{@code CANCEL:{orderNo}}。
     */
    @Size(max = 64, message = "幂等键最长 64 位")
    private String bizNo;

    /** 退款金额（分）。不传表示全额退（扣除已退部分） */
    private Long amount;

    @NotBlank(message = "退款原因不能为空")
    @Size(max = 255, message = "退款原因最长 255 位")
    private String reason;
}
