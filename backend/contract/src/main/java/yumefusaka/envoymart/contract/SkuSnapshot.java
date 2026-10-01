package yumefusaka.envoymart.contract;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * SKU 快照：按 SKU id 批量取，给购物车与订单组装用。
 * <p>
 * 与 {@link ProductDetail} 的区别是粒度——详情是「一个 SPU 的全部信息」，
 * 而购物车里放的是一行行具体的规格（"维生素 D3 400IU×90粒"），
 * 需要的正是 SKU 粒度。少了这个接口，购物车只能对每个 SKU 调一次详情，
 * 一个五件商品的购物车就是五次查询。
 * <p>
 * 商品名与规格文本一并带回：调用方（购物车、订单）要展示它们，
 * 让它自己再查一次等于把 N+1 挪个地方。
 * <p>
 * <b>由 product-service 发出，order-service 消费。</b>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SkuSnapshot {

    private Long id;

    /**
     * SPU 所属类目。
     * <p>
     * 调用方（订单）要把它快照进订单行 —— 售后政策按类目判定，而订单行不引用商品表。
     * 少了这个字段，政策引擎就只能按全类目默认政策走。
     */
    private Long categoryId;

    /**
     * 所属类目及其全部祖先，由近及远（见 {@link RedeemItem#getCategoryPath()}）。
     * <p>
     * 与 {@link #categoryId} 并存而不是取代它：订单行存的是「挂在哪个类目」这一个事实
     * （售后政策按它等值匹配），而优惠券作用域要判「落在这棵子树的哪儿」。
     * 这条路径由 product-service 用自己的类目表算，调用方不必知道类目树长什么样。
     */
    private List<Long> categoryPath;

    private Long spuId;

    /** SPU 名称，如「维生素 D3 软胶囊」 */
    private String spuName;

    /** 规格文本，如「规格:400IU×90粒;包装:瓶装」 */
    private String specText;

    private String image;

    /** 单价，单位「分」 */
    private Long price;

    /** 1 在售 / 0 下架。购物车据此把失效商品标出来，而不是直接删掉 */
    private Integer status;

    private Integer stock;
}
