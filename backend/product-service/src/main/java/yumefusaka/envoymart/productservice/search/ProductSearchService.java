package yumefusaka.envoymart.productservice.search;

import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;
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
import yumefusaka.envoymart.productservice.model.SuggestItem;
import yumefusaka.envoymart.contract.ProductSummary;
import yumefusaka.envoymart.productservice.service.CategoryService;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Slf4j
@Service
public class ProductSearchService {

    /** ES 的 max_result_window 默认就是 10000，from+size 超过它查询直接失败 */
    private static final int MAX_RESULT_WINDOW = 10_000;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int STATUS_ON = 1;

    /** 联想条数上限。候选是给眼睛扫的，超过一屏就没有意义了 */
    private static final int MAX_SUGGEST = 20;

    /** 超过这个长度的输入不是联想，是往参数里灌东西 */
    private static final int MAX_SUGGEST_QUERY_LENGTH = 64;

    private final ElasticsearchOperations elasticsearchOperations;
    private final CategoryService categoryService;
    private final HotKeywordService hotKeywordService;

    public ProductSearchService(ElasticsearchOperations elasticsearchOperations,
                                CategoryService categoryService,
                                HotKeywordService hotKeywordService) {
        this.elasticsearchOperations = elasticsearchOperations;
        this.categoryService = categoryService;
        this.hotKeywordService = hotKeywordService;
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
        // 这里要的是对外那个 0 基页码：ES 的 PageRequest.of 与它同基准
        int page = query.zeroBasedPage();
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

        // 只记第一页：翻页请求带的是同一个词，跟着记会把词频变成「这个人翻了多少页」
        if (page == 0 && StringUtils.hasText(query.getKeyword())) {
            hotKeywordService.record(query.getKeyword());
        }

        return PageResult.<ProductSummary>builder()
                .records(records)
                .total(hits.getTotalHits())
                .page(page)
                .size(size)
                .build();
    }

    /**
     * 搜索联想：把输入当作**前缀**去找，而不是当作完整词去搜。
     * <p>
     * 用 {@code bool_prefix} 而不是普通 {@code multi_match}：后者要求倒排表里有完整的分词
     * 才命中，输入「乳清蛋」时词表里只有「乳清」「清蛋」这类二元组，完整词查不到东西——
     * 而联想恰恰发生在用户还没打完的时候。
     * <p>
     * 候选从命中的商品里**就地取材**：商品名进 PRODUCT，品牌名与类目名进 BRAND / CATEGORY。
     * 后两者要求文本本身包含输入（{@code contains}）才收录——只按「命中了这条商品」就把它的
     * 品牌一并推出去，会让搜「钙片」的人看到一堆无关品牌。
     * <p>
     * 排序不指定，即按 ES 相关性得分——这里要的是「像不像用户在找的」，不是销量。
     */
    public List<SuggestItem> suggest(String rawQuery, int limit) {
        if (!StringUtils.hasText(rawQuery)) {
            return List.of();
        }
        String keyword = rawQuery.trim();
        if (keyword.length() > MAX_SUGGEST_QUERY_LENGTH) {
            keyword = keyword.substring(0, MAX_SUGGEST_QUERY_LENGTH);
        }
        int size = Math.max(1, Math.min(limit, MAX_SUGGEST));

        BoolQuery.Builder bool = new BoolQuery.Builder();
        bool.filter(Query.of(q -> q.term(t -> t.field("status").value(STATUS_ON))));
        String query = keyword;
        bool.must(Query.of(q -> q.multiMatch(m -> m
                .fields("name^3", "subtitle", "brandName", "categoryName")
                .query(query)
                .type(TextQueryType.BoolPrefix))));

        NativeQueryBuilder builder = new NativeQueryBuilder()
                .withQuery(bool.build()._toQuery())
                // 多取几倍：一条商品最多贡献三条候选，去重与品牌/类目行会吃掉名额
                .withPageable(PageRequest.of(0, size * 3));

        SearchHits<ProductIndex> hits = elasticsearchOperations.search(builder.build(), ProductIndex.class);

        List<SuggestItem> items = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String lower = keyword.toLowerCase(Locale.ROOT);
        for (SearchHit<ProductIndex> hit : hits) {
            ProductIndex product = hit.getContent();
            add(items, seen, SuggestItem.Kind.PRODUCT, product.getName(), product.getId(), size);
            if (containsIgnoreCase(product.getBrandName(), lower)) {
                add(items, seen, SuggestItem.Kind.BRAND, product.getBrandName(), product.getBrandId(), size);
            }
            if (containsIgnoreCase(product.getCategoryName(), lower)) {
                add(items, seen, SuggestItem.Kind.CATEGORY,
                        product.getCategoryName(), product.getCategoryId(), size);
            }
            if (items.size() >= size) {
                break;
            }
        }

        log.debug("ES 联想: q={}, 候选={}", keyword, items.size());
        return items;
    }

    /** 去重按「类型 + 文本」：不同品牌下的同名商品是两条候选，同名商品与品牌也是两条 */
    private void add(List<SuggestItem> items, Set<String> seen, SuggestItem.Kind kind,
                     String text, Long id, int limit) {
        if (text == null || text.isBlank() || id == null || items.size() >= limit) {
            return;
        }
        if (seen.add(kind + ":" + text)) {
            items.add(new SuggestItem(text, kind, id));
        }
    }

    private boolean containsIgnoreCase(String text, String lowerKeyword) {
        return text != null && text.toLowerCase(Locale.ROOT).contains(lowerKeyword);
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
