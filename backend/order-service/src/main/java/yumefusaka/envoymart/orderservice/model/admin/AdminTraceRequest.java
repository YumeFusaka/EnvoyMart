package yumefusaka.envoymart.orderservice.model.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

/**
 * 补录一条物流节点。
 * <p>
 * <b>为什么需要它</b>：本项目没有对接承运商推送，轨迹目前只有发货与签收两条
 * （各自写死在一处代码里）。于是"包裹在路上"这段最需要被看见的过程，在用户那边
 * 是<b>一片空白</b>——从"已揽收"直接跳到"已签收"，中间隔了三天。
 * 真实电商里这一段由承运商推、或客服手工补；这里没有承运商，就由客服补。
 *
 * @see yumefusaka.envoymart.orderservice.model.DeliveryStatus
 */
@Data
public class AdminTraceRequest {

    /** 见 {@code DeliveryStatus}。取值非法一律 400，不静默忽略 */
    @NotBlank(message = "物流状态不能为空")
    private String status;

    /** 展示给用户的那句话。不填空就按状态给一句默认的 */
    @Size(max = 255, message = "节点说明最长 255 个字符")
    private String description;

    @Size(max = 128, message = "所在地最长 128 个字符")
    private String location;

    /**
     * 节点发生时间，不填就是"现在"。
     * <p>
     * 补录的价值恰恰在于<b>事后</b>补：客服往往是拿到承运商的对账单之后才回头录入，
     * 那时"现在"早已不是包裹经过那一站的时间。按录入时间落库会让轨迹的时间顺序
     * 与事实不符，而轨迹的全部意义就是那个顺序。
     * <p>
     * 格式写死在注解上（ISO-8601）而不是依赖框架默认——与 {@code AdminOrderQuery}
     * 同一条理由：默认解析器接受什么格式随版本变化，而前端只会按一个固定格式发。
     */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime happenAt;
}
