package yumefusaka.envoymart.reviewservice.model;

import lombok.Builder;
import lombok.Data;
import yumefusaka.envoymart.contract.ProductSummary;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 「我的评价」列表项。
 * <p>
 * <b>与 {@link ReviewResponse} 的差别只有两点，都不是随手加的字段</b>：
 * <ul>
 *   <li>{@code product} —— 商品卡片数据。用户在自己的评价列表里认不出光秃秃的 spuId，
 *       而商品信息在另一个服务，只能在这里补上。</li>
 *   <li>没有 {@code nickname} —— 那是"别人怎么称呼这条评价的作者"，看自己的评价时
 *       这个字段没有任何意义。</li>
 * </ul>
 * <p>
 * <b>含被隐藏的评价</b>，并且如实带上 {@code status}：用户有权知道自己写的东西
 * 现在是什么状态，以及商家给的回复。把隐藏的评价从「我的评价」里抹掉，
 * 用户只会以为是自己没发表成功，然后再发一遍。
 */
@Data
@Builder
public class MyReviewItem {

    private Long id;
    private Long spuId;
    private Long skuId;
    private Long orderId;
    /**
     * 被评价的那一条订单行。
     * <p>
     * 订单详情页拿它来标「已评价」——评价的粒度是订单行而不是订单：一单三件商品，
     * 用户可能只评了其中一件，按订单标会让另外两件再也点不开评价入口。
     */
    private Long orderItemId;
    private Integer rating;
    private String content;
    private List<String> images;
    private Boolean anonymous;
    /** PUBLISHED / HIDDEN / PENDING，原样透出 */
    private String status;
    /**
     * 被隐藏的原因，仅隐藏时有值。
     * <p>
     * 这条只有作者看得到（「我的评价」是按人过滤的），所以打出去不构成泄露。
     * 隐藏评价是个能被滥用的动作（商家隐藏差评），只给结果不给原因，
     * 用户唯一能做的推断就是「平台在捂嘴」。
     */
    private String hiddenReason;
    private String replyContent;
    private LocalDateTime replyAt;
    private Integer usefulCount;
    private LocalDateTime createdAt;

    /** 商品卡片数据。product-service 不可用时为 null，界面退化成只显示评价本身 */
    private ProductSummary product;
}
