package yumefusaka.envoymart.orderservice.model.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 发货。
 * <p>
 * 运单号是<b>必填</b>且<b>全局唯一</b>（{@code order_delivery} 上有唯一约束）：
 * 一个不填运单号的「已发货」状态，用户点进去看到的是一条查不到任何东西的假轨迹——
 * 那比「还没发货」糟糕得多，他会拿着它去催件。
 */
@Data
public class AdminShipRequest {

    /** 承运商编码，如 SF / JD / YTO */
    @NotBlank(message = "承运商编码不能为空")
    @Size(max = 32, message = "承运商编码最长 32 个字符")
    private String carrierCode;

    @NotBlank(message = "承运商名称不能为空")
    @Size(max = 64, message = "承运商名称最长 64 个字符")
    private String carrierName;

    @NotBlank(message = "运单号不能为空")
    @Size(max = 64, message = "运单号最长 64 个字符")
    private String trackingNo;
}
