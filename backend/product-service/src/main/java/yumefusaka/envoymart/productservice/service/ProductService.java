package yumefusaka.envoymart.productservice.service;

import yumefusaka.envoymart.common.result.PageResult;
import yumefusaka.envoymart.contract.ProductDetail;
import yumefusaka.envoymart.productservice.model.ProductQuery;
import yumefusaka.envoymart.contract.ProductSummary;
import yumefusaka.envoymart.contract.SkuSnapshot;

import java.util.List;

public interface ProductService {

    PageResult<ProductSummary> list(ProductQuery query);

    /** 详情。商品不存在时抛 {@code IllegalArgumentException} */
    ProductDetail detail(Long spuId);

    /** 按关键词宽松召回、按销量排序，供智能助手的商品推荐工具使用 */
    List<ProductSummary> recommend(String query, int limit);

    /**
     * 按 SKU id 批量取快照，供购物车与订单组装。
     * <p>
     * 返回顺序与入参无关，调用方按 id 自行匹配；找不到的 id 会**直接缺席**
     * 而不是补一个空对象——缺席是明确信号（商品可能已被删除），
     * 补空对象会让调用方以为拿到了数据。
     */
    List<SkuSnapshot> skus(List<Long> skuIds);
}
