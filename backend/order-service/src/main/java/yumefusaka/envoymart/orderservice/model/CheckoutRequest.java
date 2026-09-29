package yumefusaka.envoymart.orderservice.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 下单请求。
 * <p>
 * 收货信息由前端从地址簿里选一条后展开传入，而不是传 {@code addressId} 让服务端回查——
 * 订单要存的是**下单那一刻的快照**，地址簿之后被改被删都与它无关。
 * 回查再落库看似更"规范"，实际是把快照和实时数据混在了一起。
 */
@Data
public class CheckoutRequest {

    @NotBlank(message = "收货人不能为空")
    @Size(max = 32, message = "收货人姓名最长 32 位")
    private String receiverName;

    @NotBlank(message = "手机号不能为空")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String receiverPhone;

    @NotBlank(message = "省份不能为空")
    private String receiverProvince;

    @NotBlank(message = "城市不能为空")
    private String receiverCity;

    @NotBlank(message = "区县不能为空")
    private String receiverDistrict;

    @NotBlank(message = "详细地址不能为空")
    @Size(max = 200, message = "详细地址最长 200 位")
    private String receiverDetail;

    @Size(max = 255, message = "备注最长 255 位")
    private String remark;
}
