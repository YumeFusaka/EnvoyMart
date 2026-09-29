package yumefusaka.envoymart.productservice.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import yumefusaka.envoymart.common.result.PageResult;
import yumefusaka.envoymart.productservice.entity.ProductAttributeEntity;
import yumefusaka.envoymart.productservice.entity.ProductSkuEntity;
import yumefusaka.envoymart.productservice.entity.ProductSpecEntity;
import yumefusaka.envoymart.productservice.entity.ProductSpecValueEntity;
import yumefusaka.envoymart.productservice.entity.ProductSpuEntity;
import yumefusaka.envoymart.productservice.entity.SpuAttributeValueEntity;
import yumefusaka.envoymart.productservice.mapper.ProductAttributeMapper;
import yumefusaka.envoymart.productservice.mapper.ProductSkuMapper;
import yumefusaka.envoymart.productservice.mapper.ProductSkuSpecMapper;
import yumefusaka.envoymart.productservice.mapper.ProductSpecMapper;
import yumefusaka.envoymart.productservice.mapper.ProductSpecValueMapper;
import yumefusaka.envoymart.productservice.mapper.ProductSpuMapper;
import yumefusaka.envoymart.productservice.mapper.SpuAttributeValueMapper;
import yumefusaka.envoymart.contract.AttributeView;
import yumefusaka.envoymart.contract.ProductDetail;
import yumefusaka.envoymart.productservice.model.ProductQuery;
import yumefusaka.envoymart.contract.ProductSummary;
import yumefusaka.envoymart.productservice.model.SkuSpecView;
import yumefusaka.envoymart.contract.SkuSnapshot;
import yumefusaka.envoymart.contract.SkuView;
import yumefusaka.envoymart.contract.SpecGroup;
import yumefusaka.envoymart.productservice.service.CategoryService;
import yumefusaka.envoymart.productservice.service.ProductService;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ProductServiceImpl implements ProductService {

    private static final int STATUS_ON = 1;

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
    private final ProductSpecMapper specMapper;
    private final ProductSpecValueMapper specValueMapper;
    private final ProductSkuSpecMapper skuSpecMapper;
    private final ProductAttributeMapper attributeMapper;
    private final SpuAttributeValueMapper spuAttributeValueMapper;
    private final ProductCacheService cacheService;
    private final CategoryService categoryService;

    public ProductServiceImpl(ProductSpuMapper spuMapper,
                              ProductSkuMapper skuMapper,
                              ProductSpecMapper specMapper,
                              ProductSpecValueMapper specValueMapper,
                              ProductSkuSpecMapper skuSpecMapper,
                              ProductAttributeMapper attributeMapper,
                              SpuAttributeValueMapper spuAttributeValueMapper,
                              ProductCacheService cacheService,
                              CategoryService categoryService) {
        this.spuMapper = spuMapper;
        this.skuMapper = skuMapper;
        this.specMapper = specMapper;
        this.specValueMapper = specValueMapper;
        this.skuSpecMapper = skuSpecMapper;
        this.attributeMapper = attributeMapper;
        this.spuAttributeValueMapper = spuAttributeValueMapper;
        this.cacheService = cacheService;
        this.categoryService = categoryService;
    }

    @Override
    public PageResult<ProductSummary> list(ProductQuery query) {
        Page<ProductSpuEntity> page = new Page<>(query.safePage(), query.safeSize());
        Page<ProductSpuEntity> result = spuMapper.selectPage(page, buildWrapper(query));
        return PageResult.<ProductSummary>builder()
                .records(assemble(result.getRecords()))
                .total(result.getTotal())
                .page(query.safePage())
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

    /** 回源。返回 null 表示商品不存在，缓存层据此写入空值哨兵，挡住对不存在 id 的反复穿透 */
    private ProductDetail loadDetail(Long spuId) {
        ProductSpuEntity spu = spuMapper.selectById(spuId);
        return spu == null ? null : buildDetail(spu);
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
                .images(splitByComma(spu.getImages()))
                .detailHtml(spu.getDetailHtml())
                .status(spu.getStatus())
                .tags(ProductSummary.parseTags(spu.getTags()))
                .sales(spu.getSales())
                .ratingAvg(spu.getRatingAvg())
                .reviewCount(spu.getReviewCount())
                .specs(buildSpecGroups(spuId))
                .skus(buildSkuViews(skus))
                .attributes(buildAttributes(spuId))
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
     */
    private String priceSubQuery(ProductQuery query) {
        if (query.getMinPrice() == null && query.getMaxPrice() == null) {
            return null;
        }
        StringBuilder sql = new StringBuilder(
                "select distinct spu_id from product_sku where status = " + STATUS_ON);
        if (query.getMinPrice() != null) {
            sql.append(" and price >= ").append(query.getMinPrice());
        }
        if (query.getMaxPrice() != null) {
            sql.append(" and price <= ").append(query.getMaxPrice());
        }
        return sql.toString();
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

    private List<SpecGroup> buildSpecGroups(Long spuId) {
        List<ProductSpecEntity> specs = specMapper.selectList(new LambdaQueryWrapper<ProductSpecEntity>()
                .eq(ProductSpecEntity::getSpuId, spuId)
                .orderByAsc(ProductSpecEntity::getSort)
                .orderByAsc(ProductSpecEntity::getId));
        if (specs.isEmpty()) {
            return List.of();
        }

        List<Long> specIds = specs.stream().map(ProductSpecEntity::getId).toList();
        Map<Long, List<ProductSpecValueEntity>> valuesBySpec = specValueMapper.selectList(
                        new LambdaQueryWrapper<ProductSpecValueEntity>()
                                .in(ProductSpecValueEntity::getSpecId, specIds)
                                .orderByAsc(ProductSpecValueEntity::getSort)
                                .orderByAsc(ProductSpecValueEntity::getId))
                .stream()
                .collect(Collectors.groupingBy(ProductSpecValueEntity::getSpecId));

        return specs.stream()
                .map(spec -> SpecGroup.builder()
                        .specId(spec.getId())
                        .name(spec.getName())
                        .values(valuesBySpec.getOrDefault(spec.getId(), List.of()).stream()
                                .map(value -> SpecGroup.Value.builder()
                                        .id(value.getId())
                                        .value(value.getSpecValue())
                                        .build())
                                .toList())
                        .build())
                .toList();
    }

    private List<SkuView> buildSkuViews(List<ProductSkuEntity> skus) {
        if (skus.isEmpty()) {
            return List.of();
        }
        List<Long> skuIds = skus.stream().map(ProductSkuEntity::getId).toList();
        Map<Long, List<SkuSpecView>> specsBySku = skuSpecMapper.selectBySkuIds(skuIds).stream()
                .collect(Collectors.groupingBy(SkuSpecView::getSkuId));

        return skus.stream()
                .map(sku -> {
                    List<SkuSpecView> specs = specsBySku.getOrDefault(sku.getId(), List.of());
                    return SkuView.builder()
                            .id(sku.getId())
                            .skuCode(sku.getSkuCode())
                            .price(sku.getPrice())
                            .originalPrice(sku.getOriginalPrice())
                            .stock(sku.getStock())
                            .image(sku.getImage())
                            .specValueIds(specs.stream().map(SkuSpecView::getSpecValueId).toList())
                            .specText(specs.stream()
                                    .map(s -> s.getSpecName() + ":" + s.getSpecValue())
                                    .collect(Collectors.joining(";")))
                            .build();
                })
                .toList();
    }

    private List<AttributeView> buildAttributes(Long spuId) {
        List<SpuAttributeValueEntity> values = spuAttributeValueMapper.selectList(
                new LambdaQueryWrapper<SpuAttributeValueEntity>()
                        .eq(SpuAttributeValueEntity::getSpuId, spuId));
        if (values.isEmpty()) {
            return List.of();
        }

        Map<Long, ProductAttributeEntity> attributes = attributeMapper.selectByIds(
                        values.stream().map(SpuAttributeValueEntity::getAttributeId)
                                .collect(Collectors.toSet()))
                .stream()
                .collect(Collectors.toMap(ProductAttributeEntity::getId, attribute -> attribute));

        return values.stream()
                .map(value -> {
                    ProductAttributeEntity attribute = attributes.get(value.getAttributeId());
                    if (attribute == null) {
                        // 属性定义被删了但取值还留着。跳过而不是抛错：
                        // 一条脏数据不该让整个详情页打不开
                        return null;
                    }
                    return AttributeView.builder()
                            .attributeId(attribute.getId())
                            .name(attribute.getName())
                            .value(value.getAttrValue())
                            .unit(attribute.getUnit())
                            .build();
                })
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(AttributeView::getAttributeId))
                .toList();
    }

    private List<String> splitByComma(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(part -> !part.isEmpty())
                .toList();
    }
}
