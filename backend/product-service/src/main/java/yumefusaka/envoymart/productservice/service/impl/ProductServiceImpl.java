package yumefusaka.envoymart.productservice.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import yumefusaka.envoymart.common.result.PageResult;
import yumefusaka.envoymart.productservice.content.HtmlSanitizer;
import yumefusaka.envoymart.productservice.entity.ProductSkuEntity;
import yumefusaka.envoymart.productservice.entity.ProductSpuEntity;
import yumefusaka.envoymart.productservice.mapper.ProductSkuMapper;
import yumefusaka.envoymart.productservice.mapper.ProductSkuSpecMapper;
import yumefusaka.envoymart.productservice.mapper.ProductSpuMapper;
import yumefusaka.envoymart.contract.ProductDetail;
import yumefusaka.envoymart.productservice.model.ProductQuery;
import yumefusaka.envoymart.contract.ProductSummary;
import yumefusaka.envoymart.productservice.model.SkuSpecView;
import yumefusaka.envoymart.contract.SkuSnapshot;
import yumefusaka.envoymart.contract.SkuView;
import yumefusaka.envoymart.productservice.service.CategoryService;
import yumefusaka.envoymart.productservice.service.ProductService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ProductServiceImpl implements ProductService {

    private static final int STATUS_ON = 1;

    /**
     * 目录一次最多取多少条。
     * <p>
     * 取值等于 {@link ProductQuery#safeSize()} 的上限。写死在这里而不是各调各的，
     * 是为了让「目录有没有被截断」这件事有一个能被检查的数字——
     * 真要超过这条线，得改的是取数方式（流式 / 内部专用查询），不是把数字调大。
     */
    private static final int MAX_CATALOG_SIZE = 100;

    /**
     * 排序白名单。
     * <p>
     * 排序子句**必须**走白名单映射：那个位置没法用参数占位符，把请求里的字符串直接拼进去
     * 就是注入点。白名单之外的值一律回落到默认排序而不是报错 —— 排序参数写错
     * 不该让整个列表打不开。
     */
    private static final Map<String, String> SORT_CLAUSES = Map.of(
            "sales", "sales desc",
            "price_asc", "(select min(s.price) from product_sku s where s.spu_id = product_spu.id) asc",
            "price_desc", "(select min(s.price) from product_sku s where s.spu_id = product_spu.id) desc",
            "newest", "created_at desc");

    private static final String DEFAULT_SORT = "sales desc";

    private final ProductSpuMapper spuMapper;
    private final ProductSkuMapper skuMapper;
    private final ProductSkuSpecMapper skuSpecMapper;
    private final ProductCacheService cacheService;
    private final CategoryService categoryService;
    /** 规格组、参数、SKU 视图的组装 —— 与管理端的详情共用同一份实现 */
    private final ProductAssembler assembler;

    public ProductServiceImpl(ProductSpuMapper spuMapper,
                              ProductSkuMapper skuMapper,
                              ProductSkuSpecMapper skuSpecMapper,
                              ProductCacheService cacheService,
                              CategoryService categoryService,
                              ProductAssembler assembler) {
        this.spuMapper = spuMapper;
        this.skuMapper = skuMapper;
        this.skuSpecMapper = skuSpecMapper;
        this.cacheService = cacheService;
        this.categoryService = categoryService;
        this.assembler = assembler;
    }

    @Override
    public PageResult<ProductSummary> list(ProductQuery query) {
        Page<ProductSpuEntity> page = new Page<>(query.mpCurrent(), query.safeSize());
        Page<ProductSpuEntity> result = spuMapper.selectPage(page, buildWrapper(query));
        return PageResult.<ProductSummary>builder()
                .records(assemble(result.getRecords()))
                .total(result.getTotal())
                .page(query.zeroBasedPage())
                .size(query.safeSize())
                .build();
    }

    @Override
    public ProductDetail detail(Long spuId) {
        ProductDetail cached = cacheService.getOrLoad(spuId, () -> loadDetail(spuId));
        if (cached == null) {
            throw new IllegalArgumentException("商品不存在");
        }
        return withLiveStock(cached);
    }

    /**
     * 回源。返回 null 表示商品不存在，缓存层据此写入空值哨兵，挡住对不存在 id 的反复穿透。
     * <p>
     * <b>下架商品在这里等同于「不存在」</b>。此前这个方法不看 status，于是上下架只是一个
     * 存在库里、没有任何作用的字段——下架商品照样能被直接 URL 打开。把判定放在这一层，
     * 是因为它是公开读的<b>唯一入口</b>：列表、搜索、详情都从这里或同类条件过；
     * 只在控制器上加判断的话，将来任何一个新的读入口都会绕过它。
     * <p>
     * 注意缓存键不变：状态一变就 evict（见 {@code ProductAdminService}），
     * 所以「下架后读到旧详情」的窗口就是那次 evict 之前，不存在长期悬挂的旧值。
     */
    private ProductDetail loadDetail(Long spuId) {
        ProductSpuEntity spu = spuMapper.selectById(spuId);
        if (spu == null || !Integer.valueOf(STATUS_ON).equals(spu.getStatus())) {
            return null;
        }
        return buildDetail(spu);
    }

    private ProductDetail buildDetail(ProductSpuEntity spu) {
        Long spuId = spu.getId();

        List<ProductSkuEntity> skus = skuMapper.selectList(new LambdaQueryWrapper<ProductSkuEntity>()
                .eq(ProductSkuEntity::getSpuId, spuId)
                .eq(ProductSkuEntity::getStatus, STATUS_ON)
                .orderByAsc(ProductSkuEntity::getId));

        Map<Long, String> categoryNames = categoryService.categoryNames(List.of(spu.getCategoryId()));
        Map<Long, String> brandNames = categoryService.brandNames(
                spu.getBrandId() == null ? List.of() : List.of(spu.getBrandId()));

        return ProductDetail.builder()
                .id(spu.getId())
                .spuCode(spu.getSpuCode())
                .name(spu.getName())
                .subtitle(spu.getSubtitle())
                .categoryId(spu.getCategoryId())
                .categoryName(categoryNames.get(spu.getCategoryId()))
                .brandId(spu.getBrandId())
                .brandName(brandNames.get(spu.getBrandId()))
                .mainImage(spu.getMainImage())
                .images(ProductAssembler.splitByComma(spu.getImages()))
                // 读路径也过一遍净化：写入侧已经净化，这一层兜的是「净化上线前写进去的数据」
                // 和「绕过接口直接改库」。详情走缓存，所以它随缓存重建发生，不是每请求一次
                .detailHtml(HtmlSanitizer.clean(spu.getDetailHtml()))
                .status(spu.getStatus())
                .tags(ProductSummary.parseTags(spu.getTags()))
                .sales(spu.getSales())
                .ratingAvg(spu.getRatingAvg())
                .reviewCount(spu.getReviewCount())
                .specs(assembler.specGroups(spuId))
                .skus(assembler.skuViews(skus))
                .attributes(assembler.attributes(spuId))
                .build();
    }

    /**
     * 用实时库存复制一份详情返回。
     * <p>
     * <b>不能就地修改传入对象。</b>它可能是一级缓存（Caffeine）里的那个实例 ——
     * 就地改字段会把缓存写脏，此后所有请求读到的都是这一次的库存，直到 TTL 到期。
     * 「缓存里的库存会过期」和「被某次请求永久写脏」是两回事：前者是可预期的，
     * 后者只在那一次请求之后出现，排查时几乎想不到是这里。
     * <p>
     * 库存单独回查而不是整体不缓存：SPU 主体、规格、属性、图文详情都不常变，
     * 一次主键查询换掉它们全部的重建成本，很划算。
     */
    private ProductDetail withLiveStock(ProductDetail detail) {
        if (detail.getSkus() == null || detail.getSkus().isEmpty()) {
            return detail;
        }

        List<Long> skuIds = detail.getSkus().stream().map(SkuView::getId).toList();
        Map<Long, Integer> liveStock = skuMapper.selectList(new LambdaQueryWrapper<ProductSkuEntity>()
                        .in(ProductSkuEntity::getId, skuIds))
                .stream()
                .collect(Collectors.toMap(ProductSkuEntity::getId, ProductSkuEntity::getStock));

        ProductDetail copy = new ProductDetail();
        BeanUtils.copyProperties(detail, copy);
        copy.setSkus(detail.getSkus().stream()
                .map(sku -> SkuView.builder()
                        .id(sku.getId())
                        .skuCode(sku.getSkuCode())
                        .price(sku.getPrice())
                        .originalPrice(sku.getOriginalPrice())
                        .stock(liveStock.getOrDefault(sku.getId(), 0))
                        .image(sku.getImage())
                        .specValueIds(sku.getSpecValueIds())
                        .specText(sku.getSpecText())
                        .build())
                .toList());
        return copy;
    }

    @Override
    public List<ProductSummary> recommend(String query, int limit) {
        ProductQuery productQuery = new ProductQuery();
        productQuery.setKeyword(query);
        productQuery.setSort("sales");
        productQuery.setSize(limit);
        return list(productQuery).getRecords();
    }

    @Override
    public List<ProductSummary> catalog() {
        ProductQuery productQuery = new ProductQuery();
        // 分页上限是 100，而这里要的是「全部」。目录规模是几十条，一次拿完；
        // 真涨过 100 条时应当改成流式或加内部专用查询，而不是把 size 悄悄调大后
        // 拿到一个**被静默截断**的目录——截断的商品在图上的表现是「查不到任何关系」
        productQuery.setSize(MAX_CATALOG_SIZE);
        return list(productQuery).getRecords();
    }

    @Override
    public List<SkuSnapshot> skus(List<Long> skuIds) {
        if (skuIds == null || skuIds.isEmpty()) {
            return List.of();
        }
        List<ProductSkuEntity> skus = skuMapper.selectByIds(skuIds.stream().distinct().toList());
        if (skus.isEmpty()) {
            return List.of();
        }

        Map<Long, ProductSpuEntity> spus = spuMapper.selectByIds(
                        skus.stream().map(ProductSkuEntity::getSpuId).collect(Collectors.toSet()))
                .stream()
                .collect(Collectors.toMap(ProductSpuEntity::getId, Function.identity()));

        // 规格文本拼一次就够：购物车每行都要展示它，而组装每一行时再查一次
        // 就是把 N+1 从外层挪到了里层
        Map<Long, String> specTexts = skuSpecMapper.selectBySkuIds(
                        skus.stream().map(ProductSkuEntity::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(
                        SkuSpecView::getSkuId,
                        Collectors.mapping(
                                view -> view.getSpecName() + ":" + view.getSpecValue(),
                                Collectors.joining(";"))));

        return skus.stream()
                .map(sku -> SkuSnapshot.builder()
                        .id(sku.getId())
                        .spuId(sku.getSpuId())
                        .categoryId(spus.containsKey(sku.getSpuId())
                                ? spus.get(sku.getSpuId()).getCategoryId() : null)
                        .spuName(spus.containsKey(sku.getSpuId())
                                ? spus.get(sku.getSpuId()).getName() : null)
                        .specText(specTexts.get(sku.getId()))
                        .image(sku.getImage())
                        .price(sku.getPrice())
                        .stock(sku.getStock())
                        .status(sku.getStatus())
                        .build())
                .toList();
    }

    @Override
    public List<ProductSummary> summaries(List<Long> spuIds) {
        if (spuIds == null || spuIds.isEmpty()) {
            return List.of();
        }
        return assemble(spuMapper.selectByIds(spuIds.stream().distinct().toList()));
    }

    // ==================== 查询条件 ====================

    private LambdaQueryWrapper<ProductSpuEntity> buildWrapper(ProductQuery query) {
        LambdaQueryWrapper<ProductSpuEntity> wrapper = new LambdaQueryWrapper<ProductSpuEntity>()
                .eq(ProductSpuEntity::getStatus, STATUS_ON);

        if (StringUtils.hasText(query.getKeyword())) {
            String keyword = query.getKeyword().trim();
            // 关键词在两个字段里任一命中即可，所以整组必须用 and(...) 包起来。
            // 不包的话生成的是 `status = 1 and name like ? or subtitle like ?`——
            // or 的优先级低于 and，前半段条件会被短路掉，已下架商品照样被查出来
            wrapper.and(w -> w.like(ProductSpuEntity::getName, keyword)
                    .or()
                    .like(ProductSpuEntity::getSubtitle, keyword));
        }

        if (query.getCategoryId() != null) {
            // 展开子树：用户在二级类目下要能看到挂在三级类目上的商品
            List<Long> categoryIds = categoryService.selfAndDescendantIds(query.getCategoryId());
            if (categoryIds.isEmpty()) {
                // 类目不存在时直接给一个不可能命中的条件，而不是跳过筛选 ——
                // 跳过的话用户会看到「全部商品」，以为筛选坏了
                return wrapper.eq(ProductSpuEntity::getId, -1L);
            }
            wrapper.in(ProductSpuEntity::getCategoryId, categoryIds);
        }

        if (query.getBrandId() != null) {
            wrapper.eq(ProductSpuEntity::getBrandId, query.getBrandId());
        }

        String priceSubQuery = priceSubQuery(query);
        if (priceSubQuery != null) {
            wrapper.inSql(ProductSpuEntity::getId, priceSubQuery);
        }

        // 先判空再进 Map：SORT_CLAUSES 是 Map.of 造的**不可变 Map**，
        // 它的 get(null) 直接抛 NPE，不像 HashMap 那样安静地返回 null。
        // URL 里没带 sort 时这个值就是 null —— 于是「按销量排序」这个默认路径
        // 反而成了唯一会崩的路径
        String sortKey = query.getSort();
        String orderBy = sortKey == null
                ? DEFAULT_SORT
                : SORT_CLAUSES.getOrDefault(sortKey, DEFAULT_SORT);
        wrapper.last("order by " + orderBy);
        return wrapper;
    }

    /**
     * 价格区间的子查询。
     * <p>
     * 这里是拼字符串而不是用参数占位符 —— 子查询要整体作为 {@code inSql} 的实参传入，
     * 那个位置放不了 {@code #{}}。之所以没有注入风险，是因为<b>两个值都是 Long</b>：
     * 类型决定了它们不可能携带 SQL 片段。将来若要支持「价格区间带单位」（如 "2k"），
     * 解析必须在这一层之外完成，不能把原始字符串放进来。
     * <p>
     * <b>语义必须与 ES 那条路一致</b>（{@code ProductSearchService} 的 range 查询）：
     * SPU 的价格本身就是个区间 {@code [min(price), max(price)]}，只要它与查询区间<b>沾边</b>就命中。
     * 这里曾经写的是「存在某个 SKU 落在区间内」，两者在一个 SKU 卖 59、另一个卖 299 的 SPU 上
     * 筛 100~200 时给出<b>相反</b>的答案：带关键词（走 ES）搜得到，点类目浏览（走这里）搜不到。
     * 两条路看起来只是同一份数据的两种取法，结果不一致时没人会怀疑到语义上。
     */
    private String priceSubQuery(ProductQuery query) {
        if (query.getMinPrice() == null && query.getMaxPrice() == null) {
            return null;
        }
        List<String> ranges = new ArrayList<>(2);
        if (query.getMinPrice() != null) {
            ranges.add("max(price) >= " + query.getMinPrice());
        }
        if (query.getMaxPrice() != null) {
            ranges.add("min(price) <= " + query.getMaxPrice());
        }
        return "select spu_id from product_sku where status = " + STATUS_ON
                + " group by spu_id having " + String.join(" and ", ranges);
    }

    // ==================== 组装 ====================

    /** 把一批 SPU 组装成列表项。SKU、类目名、品牌名各批量查一次，避免逐条查的 N+1 */
    private List<ProductSummary> assemble(List<ProductSpuEntity> spus) {
        if (spus.isEmpty()) {
            return List.of();
        }

        List<Long> spuIds = spus.stream().map(ProductSpuEntity::getId).toList();
        Map<Long, List<ProductSkuEntity>> skusBySpu = skuMapper.selectList(
                        new LambdaQueryWrapper<ProductSkuEntity>()
                                .in(ProductSkuEntity::getSpuId, spuIds)
                                .eq(ProductSkuEntity::getStatus, STATUS_ON))
                .stream()
                .collect(Collectors.groupingBy(ProductSkuEntity::getSpuId));

        Map<Long, String> categoryNames = categoryService.categoryNames(
                spus.stream().map(ProductSpuEntity::getCategoryId).toList());
        Map<Long, String> brandNames = categoryService.brandNames(
                spus.stream().map(ProductSpuEntity::getBrandId).toList());

        return spus.stream()
                .map(spu -> toSummary(spu, skusBySpu.getOrDefault(spu.getId(), List.of()),
                        categoryNames, brandNames))
                .toList();
    }

    private ProductSummary toSummary(ProductSpuEntity spu,
                                     List<ProductSkuEntity> skus,
                                     Map<Long, String> categoryNames,
                                     Map<Long, String> brandNames) {
        long minPrice = 0;
        long maxPrice = 0;
        int totalStock = 0;
        if (!skus.isEmpty()) {
            minPrice = skus.stream().mapToLong(ProductSkuEntity::getPrice).min().orElse(0L);
            maxPrice = skus.stream().mapToLong(ProductSkuEntity::getPrice).max().orElse(0L);
            totalStock = skus.stream().mapToInt(ProductSkuEntity::getStock).sum();
        }

        return ProductSummary.builder()
                .id(spu.getId())
                .name(spu.getName())
                .subtitle(spu.getSubtitle())
                .categoryId(spu.getCategoryId())
                .categoryName(categoryNames.get(spu.getCategoryId()))
                .brandId(spu.getBrandId())
                .brandName(brandNames.get(spu.getBrandId()))
                .mainImage(spu.getMainImage())
                .minPrice(minPrice)
                .maxPrice(maxPrice)
                .sales(spu.getSales())
                .ratingAvg(spu.getRatingAvg())
                .reviewCount(spu.getReviewCount())
                .totalStock(totalStock)
                .tags(ProductSummary.parseTags(spu.getTags()))
                .build();
    }

}
