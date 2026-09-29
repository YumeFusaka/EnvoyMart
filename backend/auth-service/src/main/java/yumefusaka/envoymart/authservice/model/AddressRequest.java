package yumefusaka.envoymart.authservice.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AddressRequest {

    @NotBlank(message = "收货人不能为空")
    @Size(max = 32, message = "收货人姓名最长 32 位")
    private String receiverName;

    @NotBlank(message = "手机号不能为空")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String receiverPhone;

    @NotBlank(message = "省份不能为空")
    private String province;

    @NotBlank(message = "城市不能为空")
    private String city;

    @NotBlank(message = "区县不能为空")
    private String district;

    @NotBlank(message = "详细地址不能为空")
    @Size(max = 200, message = "详细地址最长 200 位")
    private String detail;

    @Size(max = 8, message = "标签最长 8 位")
    private String tag;

    /** 是否设为默认地址。为空表示不改变当前默认设置 */
    private Boolean isDefault;
}
