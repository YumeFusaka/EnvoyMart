package yumefusaka.envoymart.orderservice.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 一个订单页签的角标。
 * <p>
 * <b>标签名也一起回</b>，不让前端各写一份：页签的成员状态会变（比如以后
 * 「退款/取消」再拆开），那时前端那份写死的文案就成了没人认识的说法。
 * 顺序即返回顺序 —— 由 {@link OrderTab} 的声明顺序决定，无需前端再排一次。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderTabCount {

    /** 页签名，见 {@link OrderTab} */
    private String tab;
    private String label;
    private long count;
}
