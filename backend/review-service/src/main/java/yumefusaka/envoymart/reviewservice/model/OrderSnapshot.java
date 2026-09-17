package yumefusaka.envoymart.reviewservice.model;

import lombok.Data;

import java.util.List;

/**
 * 订单服务返回的订单摘要 —— 只取评价需要校验的字段。
 * <p>
 * order-service 返回的对象字段更多（收件人、地址、金额），这里不逐个复刻：
 * 未声明的字段会在反序列化时被忽略，而每多声明一个字段就多一处"下游改了名、
 * 这里悄悄变成 null"的可能。评价只关心「这单是不是你的、里面有没有这件商品」。
 */
@Data
public class OrderSnapshot {

    private Long id;
    private String orderNo;
    private List<Item> items;

    /** 订单明细行 —— 只需要能判断商品在不在单里 */
    @Data
    public static class Item {
        private Long productId;
        private String productName;
        private Integer quantity;
    }
}
