package yumefusaka.envoymart.productservice.service;

import yumefusaka.envoymart.common.result.PageResult;
import yumefusaka.envoymart.productservice.model.ProductDetail;
import yumefusaka.envoymart.productservice.model.ProductQuery;
import yumefusaka.envoymart.productservice.model.ProductSummary;

import java.util.List;

public interface ProductService {

    PageResult<ProductSummary> list(ProductQuery query);

    /** 详情。商品不存在时抛 {@code IllegalArgumentException} */
    ProductDetail detail(Long spuId);

    /** 按关键词宽松召回、按销量排序，供智能助手的商品推荐工具使用 */
    List<ProductSummary> recommend(String query, int limit);
}
