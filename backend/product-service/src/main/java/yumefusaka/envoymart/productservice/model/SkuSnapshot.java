package yumefusaka.envoymart.productservice.model;

import lombok.Builder;
import lombok.Data;

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
 */
@Data
@Builder
public class SkuSnapshot {

    private Long id;
    private Long spuId;
    /** SPU 名称，如「维生素 D3 软胶囊」 */
    private String spuName;
    /** 规格文本，如「规格:400IU×90粒;包装:瓶装」 */
    private String specText;
    private String image;
    /** 单位「分」 */
    private Long price;
    private Integer stock;
    /** 1 在售 / 0 下架。购物车据此把失效商品标出来，而不是直接删掉 */
    private Integer status;
}
