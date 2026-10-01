package yumefusaka.envoymart.productservice.search;

import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Operator;
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

    /**
     * 参与匹配的字段与权重。
     * <p>
     * 抽成常量是因为它有两个使用者——命中（{@code must}）与排除（{@code mustNot}）。
     * 各写一份的话，「搜得到却排除不掉」这类不对称会悄悄出现，而且不报任何错。
     */
    private static final List<String> MATCH_FIELDS = List.of(
            "name^3", "subtitle^2", "tags^2",
            "categoryName", "brandName", "attributeText", "detailText");

    /**
     * 逐字通道的字段名。与 {@link #MATCH_FIELDS} 是同一组内容的两种切法：
     * 那边字段多、权重细、走二元组；这边合成一段、逐字切、要求全字命中。
     * <p>
     * 两条通道只能二选一地起作用——不是「哪个更好」，而是各自的盲区不一样：
     * 二元组认得「维生素」（词元 {@code 维生}/{@code 生素} 都在），逐字那条要多切一层；
     * 「钙片」反过来。所以查询侧把它们放进同一个 {@code bool.should}，
     * 命中任意一条即算命中（见 {@code search}）。
     */
    private static final String UNIGRAM_FIELD = "unigramText";

    /**
     * 联想比检索少几段：{@code detailText} / {@code attributeText} 不参与——它们是「详情里提到过」，
     * 而联想要在用户打到一半时给**像商品名或品牌名**的东西，把详情正文拉进来只会推出无关条目。
     */
    private static final List<String> SUGGEST_FIELDS =
            List.of("name^3", "subtitle", "brandName", "categoryName");

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
            // 通道一：二元组。精确、打分细，是主力
            bool.should(Query.of(q -> q.multiMatch(m -> m
                    .fields(MATCH_FIELDS)
                    .query(keyword))));
            // 通道二：逐字，且要求**查询里的每个字都命中**。兜的是二元组的盲区
            // （「钙片」在「碳酸钙 … 咀嚼片」里没有对应词元，见 ProductIndex 的说明）。
            // 用 AND 而不是默认的 OR：「钙片」OR 起来会变成「含钙就行」或「含片就行」，
            // 那会把「复合维生素矿物质片」也算成钙片——多发几条看着无害，
            // 但用户搜的是钙片，返回一堆不是钙片的东西，比返回空更糟
            bool.should(Query.of(q -> q.match(m -> m
                    .field(UNIGRAM_FIELD)
                    .query(keyword)
                    .operator(Operator.And))));
            // **这句不能省**：bool 里只要还有 filter（上面那条 status），should 的默认
            // minimum_should_match 就是 0 —— 两条通道会双双退化成「可有可无的加分项」，
            // 而 status 的 filter 足以让**所有在架商品**全部命中。症状是搜索看起来完全正常，
            // 只是搜什么都返回全部商品
            bool.minimumShouldMatch("1");
        }

        // 否定条件与命中条件**必须用同一组字段**：两边字段集一旦不同，就会出现
        // 「按这个名字搜得到、却按这个名字排除不掉」的不对称，而这种不对称不会报错
        if (StringUtils.hasText(query.getExcludeKeywords())) {
            bool.mustNot(Query.of(q -> q.multiMatch(m -> m
                    .fields(MATCH_FIELDS)
                    .query(query.getExcludeKeywords().trim()))));
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

        // 属性逐项各占一个 filter：要的是「同时满足全部」，不是「满足任意一个」。
        // 把多项收进一个 terms 查询就是把 AND 写成了 OR —— 症状是筛「孕妇能吃 + 片剂」时
        // 返回了孕妇能吃但**不是片剂**的商品，结果看着完全正常、没有任何报错
        if (query.getAttributes() != null) {
            for (String attribute : query.getAttributes()) {
                if (StringUtils.hasText(attribute)) {
                    String expected = attribute.trim();
                    bool.filter(Query.of(q -> q.term(t -> t.field("attributes").value(expected))));
                }
            }
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
        bool.should(Query.of(q -> q.multiMatch(m -> m
                .fields(SUGGEST_FIELDS)
                .query(query)
                .type(TextQueryType.BoolPrefix))));
        // 与服务端检索同一个盲区、同一个修法：输入「钙片」时二元组一条候选都出不来，
        // 联想框是空的 —— 而用户正是靠着联想确认「平台上有这个东西」才继续打字的。
        // 这里同样要求全字命中，打到「钙」出钙类、打到「钙片」收窄成钙片，
        // 中途多打一个不存在的字就该收敛到空，而不是继续给一批无关候选
        bool.should(Query.of(q -> q.match(m -> m
                .field(UNIGRAM_FIELD)
                .query(query)
                .operator(Operator.And))));
        bool.minimumShouldMatch("1");

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

    /**
     * 排序走白名单，未知值与缺省都回落到<b>综合排序</b>——排序参数写错不该让整个搜索打不开。
     * <p>
     * <b>综合排序必须把相关性排在第一位。</b>这里原先只有一个字段排序，缺省是销量降序——
     * 而<b>按字段排序时 ES 根本不算分</b>，于是「相关」这个维度被整个丢掉了：
     * 实测搜「乳清蛋白粉」，排在第一位的是<b>维生素 C 咀嚼片</b>（它详情里提到过「蛋白」），
     * 真正叫这个名字的商品排第二。用户和模型都只能看见一串「搜了等于没搜」的结果，
     * 而接口 200、日志正常、没有任何地方报错。
     * <p>
     * <b>为什么不是「把 sales 换成 _score」</b>：销量本身是有效信号（爆款优先是电商的常态），
     * 该丢的是「用销量<b>替代</b>相关性」而不是销量本身。所以综合排序是两级：
     * {@code _score} 降序，同分的再看销量。
     * <p>
     * 无关键词时（纯按类目/属性筛）所有文档得分相同，第二级销量自然接管——
     * 与改动前的行为一致，不需要为这种情况另写一条分支。
     */
    private List<SortOptions> sortOf(String sort) {
        return switch (sort == null ? "" : sort) {
            case "sales" -> List.of(fieldSort("sales", SortOrder.Desc));
            case "price_asc" -> List.of(fieldSort("minPrice", SortOrder.Asc));
            case "price_desc" -> List.of(fieldSort("minPrice", SortOrder.Desc));
            case "newest" -> List.of(fieldSort("createdAt", SortOrder.Desc));
            default -> List.of(
                    SortOptions.of(s -> s.score(score -> score.order(SortOrder.Desc))),
                    fieldSort("sales", SortOrder.Desc));
        };
    }

    private SortOptions fieldSort(String field, SortOrder order) {
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
