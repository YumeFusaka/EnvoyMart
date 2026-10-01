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

    /**
     * 全量在售商品，供 ai-service 建知识图谱时做实体链接（文档里的「本品」要落到具体商品）。
     * <p>
     * 不分页：目录是几十条的规模，而分页会把一次完整的链接变成若干次可以中途失败的调用，
     * 漏掉的那一页在图上的表现是「这几个商品查不到任何关系」——一个不报错的错误。
     */
    List<ProductSummary> catalog();

    /**
     * 按 SKU id 批量取快照，供购物车与订单组装。
     * <p>
     * 返回顺序与入参无关，调用方按 id 自行匹配；找不到的 id 会**直接缺席**
     * 而不是补一个空对象——缺席是明确信号（商品可能已被删除），
     * 补空对象会让调用方以为拿到了数据。
     */
    List<SkuSnapshot> skus(List<Long> skuIds);

    /**
     * 按 SPU id 批量取列表项，供收藏夹这类「手上只有一批 id、要展示成商品卡片」的场景。
     * <p>
     * 与 {@link #list} 的区别只有一个：**不按 status 过滤**。收藏夹要在商品下架后
     * 依然显示它——下架不等于这条收藏没发生过，直接把它从列表里抹掉，用户会以为
     * 「我的收藏丢了」。是否可购买由调用方按 {@code status} 判断并如实标注。
     * <p>
     * 返回顺序与入参无关（按 id 查出来是什么顺序就是什么顺序），调用方自己按
     * 收藏时间排。找不到的 id 直接缺席。
     */
    List<ProductSummary> summaries(List<Long> spuIds);
}
