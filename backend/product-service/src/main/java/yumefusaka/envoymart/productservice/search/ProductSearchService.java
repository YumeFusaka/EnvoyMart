package yumefusaka.envoymart.productservice.search;

import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.json.JsonData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.client.elc.NativeQueryBuilder;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import yumefusaka.envoymart.common.result.PageResult;
import yumefusaka.envoymart.productservice.model.ProductQuery;
import yumefusaka.envoymart.contract.ProductSummary;
import yumefusaka.envoymart.productservice.service.CategoryService;

import java.math.BigDecimal;
import java.util.List;

@Slf4j
@Service
public class ProductSearchService {

    /** ES 的 max_result_window 默认就是 10000，from+size 超过它查询直接失败 */
    private static final int MAX_RESULT_WINDOW = 10_000;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int STATUS_ON = 1;

    private final ElasticsearchOperations elasticsearchOperations;
    private final CategoryService categoryService;

    public ProductSearchService(ElasticsearchOperations elasticsearchOperations,
                                CategoryService categoryService) {
        this.elasticsearchOperations = elasticsearchOperations;
        this.categoryService = categoryService;
    }

    /**
     * 多字段检索 + 类目 / 品牌 / 价格区间筛选。
     * <p>
     * 分页参数在这里校验，而不是直接交给 {@code PageRequest.of} 和 ES。原先两个参数都是裸的：
     * {@code page=-1} / {@code size=0} 会被 Spring Data 拒绝并抛出它自己的文案，
     * {@code size=10001} 又会撞上 ES 的 max_result_window 报 "all shards failed"。
     * 两种情况都以 code=500 回给调用方，而这个接口<b>匿名可达</b> —— 调用方既分不清
     * 是自己参数写错了还是服务端挂了，还顺带把 Spring Data 与 ES 的内部消息读了出去。
     */
    public PageResult<ProductSummary> search(ProductQuery query) {
        int page = query.safePage();
        int size = query.safeSize();
        validatePaging(page, size);

        BoolQuery.Builder bool = new BoolQuery.Builder();
        // 下架商品一律不出现在搜索结果里。放在 filter 而不是 must：
        // 它不参与打分，只是硬条件
        bool.filter(Query.of(q -> q.term(t -> t.field("status").value(STATUS_ON))));

        if (StringUtils.hasText(query.getKeyword())) {
            String keyword = query.getKeyword().trim();
            bool.must(Query.of(q -> q.multiMatch(m -> m
                    .fields("name^3", "subtitle^2", "tags^2",
                            "categoryName", "brandName", "attributeText", "detailText")
                    .query(keyword))));
        }

        if (query.getCategoryId() != null) {
            List<Long> categoryIds = categoryService.selfAndDescendantIds(query.getCategoryId());
            if (categoryIds.isEmpty()) {
                // 类目不存在时返回空，而不是忽略这个筛选条件 ——
                // 忽略的话用户会看到「全部商品」，以为筛选坏了
                return PageResult.<ProductSummary>builder()
                        .records(List.of()).total(0L).page(page).size(size).build();
            }
            List<FieldValue> values = categoryIds.stream().map(FieldValue::of).toList();
            bool.filter(Query.of(q -> q.terms(t -> t.field("categoryId").terms(v -> v.value(values)))));
        }

        if (query.getBrandId() != null) {
            bool.filter(Query.of(q -> q.term(t -> t.field("brandId").value(query.getBrandId()))));
        }

        // 区间**有交集**即算命中：SPU 的价格是一个区间（不同规格不同价），
        // 只要它与查询区间沾边就该出现。写成「minPrice 落在区间内」会把
        // 「低价规格在区间内、高价规格超出」的商品整条漏掉
        if (query.getMinPrice() != null) {
            bool.filter(Query.of(q -> q.range(r -> r.untyped(u -> u
                    .field("maxPrice")
                    .gte(JsonData.of(query.getMinPrice()))))));
        }
        if (query.getMaxPrice() != null) {
            bool.filter(Query.of(q -> q.range(r -> r.untyped(u -> u
                    .field("minPrice")
                    .lte(JsonData.of(query.getMaxPrice()))))));
        }

        NativeQueryBuilder builder = new NativeQueryBuilder()
                .withQuery(bool.build()._toQuery())
                .withPageable(PageRequest.of(page, size))
                .withSort(sortOf(query.getSort()));

        SearchHits<ProductIndex> hits = elasticsearchOperations.search(builder.build(), ProductIndex.class);
        List<ProductSummary> records = hits.stream()
                .map(SearchHit::getContent)
                .map(this::toSummary)
                .toList();

        log.debug("ES 搜索: keyword={}, categoryId={}, brandId={}, hits={}",
                query.getKeyword(), query.getCategoryId(), query.getBrandId(), hits.getTotalHits());

        return PageResult.<ProductSummary>builder()
                .records(records)
                .total(hits.getTotalHits())
                .page(page)
                .size(size)
                .build();
    }

    private void validatePaging(int page, int size) {
        if (page < 0) {
            throw new IllegalArgumentException("页码不能为负");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("每页条数需在 1 到 " + MAX_PAGE_SIZE + " 之间");
        }
        if ((long) page * size >= MAX_RESULT_WINDOW) {
            throw new IllegalArgumentException("页码超出可检索范围，请缩小范围或改用分类筛选");
        }
    }

    /** 排序走白名单，未知值回落到销量降序 —— 排序参数写错不该让整个搜索打不开 */
    private SortOptions sortOf(String sort) {
        String field = switch (sort == null ? "" : sort) {
            case "price_asc" -> "minPrice";
            case "price_desc" -> "minPrice";
            case "newest" -> "createdAt";
            default -> "sales";
        };
        SortOrder order = "price_asc".equals(sort) ? SortOrder.Asc : SortOrder.Desc;
        return SortOptions.of(s -> s.field(f -> f.field(field).order(order)));
    }

    private ProductSummary toSummary(ProductIndex index) {
        return ProductSummary.builder()
                .id(index.getId())
                .name(index.getName())
                .subtitle(index.getSubtitle())
                .categoryId(index.getCategoryId())
                .categoryName(index.getCategoryName())
                .brandId(index.getBrandId())
                .brandName(index.getBrandName())
                .mainImage(index.getMainImage())
                .minPrice(index.getMinPrice())
                .maxPrice(index.getMaxPrice())
                .sales(index.getSales())
                .ratingAvg(index.getRatingAvg() == null
                        ? null : BigDecimal.valueOf(index.getRatingAvg()))
                .reviewCount(index.getReviewCount())
                .totalStock(index.getTotalStock())
                .tags(ProductSummary.parseTags(index.getTags()))
                .build();
    }
}
