package yumefusaka.envoymart.orderservice.model.admin;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 管理端订单列表的一行。
 * <p>
 * 与 {@code OrderResponse} 是两份东西，不是重复：那边是买家视角（收货信息、支付倒计时、
 * 完整订单行），这边是运营视角——运营在列表上要回答的是
 * 「这单谁下的、多少钱、到哪一步了、单号是多少、我有没有在上面写过备注」。
 * <p>
 * <b>刻意不带收货详细地址</b>：列表页不需要它，而把全量收货地址放进每一次列表查询的
 * 返回体里，等于把一整页用户的姓名电话地址塞进日志、缓存与浏览器内存。
 * 要看地址的是详情页，那里单独给。
 */
@Data
@Builder
public class AdminOrderSummary {

    private Long id;
    private String orderNo;
    /** 下单用户 id。列表上直接展示它，不额外调 auth-service 换昵称——
     *  一次列表查询要几百次跨服务调用，换来的只是一个可有可无的显示名 */
    private String userId;
    private String status;
    private String statusText;

    private Long totalAmount;
    private Long discountAmount;
    private Long payAmount;

    private String receiverName;
    private String receiverPhone;

    /** 订单行数。列表上看到「3 件商品」比看到一个商品名更有信息量 */
    private Integer itemCount;
    private Integer totalQuantity;
    /** 首件商品名，作为这一单的「是什么」的提示 */
    private String firstItemName;

    /** 已发货时的运单号，未发货为 null */
    private String trackingNo;
    private String carrierName;

    private LocalDateTime createdAt;
    private LocalDateTime paidAt;
    private LocalDateTime shippedAt;

    /** 商家备注。列表上要能一眼看出「这单备注过」，否则备注等于没写 */
    private String adminRemark;
}
