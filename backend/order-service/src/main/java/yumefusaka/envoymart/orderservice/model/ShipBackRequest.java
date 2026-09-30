package yumefusaka.envoymart.orderservice.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 用户寄回退货的请求。
 * <p>
 * 单据编号与承运商都要填：只有一个快递单号而没有承运商时，商家拿到货也不知道去哪查；
 * 只有承运商没有单号，等于什么都没提供。长度上限对齐数据库列宽。
 */
@Data
public class ShipBackRequest {

    @NotBlank(message = "请选择或填写快递公司")
    @Size(max = 32, message = "快递公司名称最长 32 位")
    private String carrier;

    @NotBlank(message = "请填写退货运单号")
    @Size(max = 64, message = "运单号最长 64 位")
    private String trackingNo;
}
