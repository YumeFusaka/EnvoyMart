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

    /**
     * 使用的优惠券（用户券 id），选填。
     * <p>
     * 传的是用户券而不是模板：同一张模板被很多人领，各自的有效期与使用状态都不同。
     */
    private Long userCouponId;

    /**
     * 幂等键：同一次「确认下单」意图的多次投递带同一个值。
     * <p>
     * <b>为什么交易域要认这个字段</b>：下单是跨服务写操作，客户端读超时不代表服务端没做成。
     * 用户按「稍后再试」再点一次，如果没有幂等键，服务端看到的是两次一模一样的请求，
     * 只能建两笔订单。这个字段让「同一次确认」在服务端可被识别。
     * <p>
     * 选填是为了兼容：前端旧版本、外部 MCP 调用方暂时不带也能下单（退化为无幂等）。
     * 带了就一定生效——它落在 {@code shop_order.request_id} 的唯一约束上。
     */
    @Size(max = 64, message = "幂等键最长 64 位")
    private String requestId;
}
