package yumefusaka.envoymart.reviewservice.model;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单服务返回的订单摘要 —— 只取评价需要校验的字段。
 * <p>
 * order-service 返回的对象字段更多（收件人、金额、时间轴），这里不逐个复刻：
 * 未声明的字段会在反序列化时被忽略，而每多声明一个字段就多一处「下游改了名、
 * 这里悄悄变成 null」的可能。评价只关心「这单是不是你的、能不能评」。
 */
@Data
public class OrderSnapshot {

    private Long id;
    private String orderNo;
    /** 只有已收货/已完成的订单才允许评价 */
    private String status;
    private LocalDateTime receivedAt;
    private List<Item> items;

    /** 订单明细行。评价以它为单位，所以必须带 id */
    @Data
    public static class Item {
        /** 订单行 id —— 它就是评价的唯一性依据 */
        private Long id;
        private Long spuId;
        private Long skuId;
        private String spuName;
        private String skuSpecText;
        private Integer quantity;
    }
}
