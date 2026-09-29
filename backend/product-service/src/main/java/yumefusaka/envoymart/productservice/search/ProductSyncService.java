package yumefusaka.envoymart.productservice.search;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import yumefusaka.envoymart.productservice.entity.ProductAttributeEntity;
import yumefusaka.envoymart.productservice.entity.ProductSkuEntity;
import yumefusaka.envoymart.productservice.entity.ProductSpuEntity;
import yumefusaka.envoymart.productservice.entity.SpuAttributeValueEntity;
import yumefusaka.envoymart.productservice.mapper.ProductAttributeMapper;
import yumefusaka.envoymart.productservice.mapper.ProductSkuMapper;
import yumefusaka.envoymart.productservice.mapper.ProductSpuMapper;
import yumefusaka.envoymart.productservice.mapper.SpuAttributeValueMapper;
import yumefusaka.envoymart.productservice.service.CategoryService;

import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 商品数据 ES 索引同步：启动时全量、库存或商品变更时单条。
 * <p>
 * 开关默认 <b>开</b>：本地 H2 每次启动都是空的、而 ES 是持久的，不同步的话索引里什么都没有，
 * 搜索会一直报 "index not found" —— 功能静默不可用，不实际搜一次发现不了。
 * 想关掉显式配 {@code product.search.sync-on-startup=false}。
 * <p>
 * 重复同步是安全的：{@code save} 走 upsert 语义，主键相同即覆盖。
 */
@Slf4j
@Service
@ConditionalOnProperty(value = "product.search.sync-on-startup", havingValue = "true", matchIfMissing = true)
public class ProductSyncService {

    private static final int STATUS_ON = 1;

    /** 详情正文是 HTML，入索引前要剥掉标签 */
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private final ProductSpuMapper spuMapper;
    private final ProductSkuMapper skuMapper;
    private final SpuAttributeValueMapper spuAttributeValueMapper;
    private final ProductAttributeMapper attributeMapper;
    private final CategoryService categoryService;
    private final ProductSearchRepository searchRepository;

    public ProductSyncService(ProductSpuMapper spuMapper,
                              ProductSkuMapper skuMapper,
                              SpuAttributeValueMapper spuAttributeValueMapper,
                              ProductAttributeMapper attributeMapper,
                              CategoryService categoryService,
                              ProductSearchRepository searchRepository) {
        this.spuMapper = spuMapper;
        this.skuMapper = skuMapper;
        this.spuAttributeValueMapper = spuAttributeValueMapper;
        this.attributeMapper = attributeMapper;
        this.categoryService = categoryService;
        this.searchRepository = searchRepository;
    }

    @PostConstruct
    public void syncAll() {
        List<ProductSpuEntity> spus = spuMapper.selectList(null);
        if (spus.isEmpty()) {
            log.info("商品库为空，跳过 ES 全量同步");
            return;
        }
        List<ProductIndex> indices = buildIndices(spus);
        searchRepository.saveAll(indices);
        log.info("ES 商品索引同步完成，共 {} 条", indices.size());
    }

    /**
     * 同步单个商品。库存变更、商品上下架后调用。
     * <p>
     * 只靠启动时的全量同步不够：索引里的库存与销量会停在「服务启动那一刻」，
     * 而这两者一直在变。
     */
    public void syncOne(Long spuId) {
        ProductSpuEntity spu = spuId == null ? null : spuMapper.selectById(spuId);
        if (spu == null) {
            // 商品被删了，索引里的那条也要清掉，否则搜索结果里会出现点不进去的条目
            searchRepository.deleteById(spuId);
            return;
        }
        searchRepository.save(buildIndices(List.of(spu)).get(0));
    }

    private List<ProductIndex> buildIndices(List<ProductSpuEntity> spus) {
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
        Map<Long, String> attributeTexts = buildAttributeTexts(spuIds);

        return spus.stream()
                .map(spu -> toIndex(spu,
                        skusBySpu.getOrDefault(spu.getId(), List.of()),
                        categoryNames,
                        brandNames,
                        attributeTexts.getOrDefault(spu.getId(), "")))
                .toList();
    }

    /**
     * 把商品参数拼成一段文本（"剂型:胶囊 适用人群:成人 每份含量:400IU"）。
     * <p>
     * 参数是结构化数据、默认不进全文索引，于是「胶囊」这类词搜不到任何商品 ——
     * 而用户确实会这么搜。拼成文本入索引是最省事的解法，比建一堆字段简单得多。
     */
    private Map<Long, String> buildAttributeTexts(List<Long> spuIds) {
        List<SpuAttributeValueEntity> values = spuAttributeValueMapper.selectList(
                new LambdaQueryWrapper<SpuAttributeValueEntity>()
                        .in(SpuAttributeValueEntity::getSpuId, spuIds));
        if (values.isEmpty()) {
            return Map.of();
        }

        Map<Long, ProductAttributeEntity> attributes = attributeMapper.selectByIds(
                        values.stream().map(SpuAttributeValueEntity::getAttributeId)
                                .collect(Collectors.toSet()))
                .stream()
                .collect(Collectors.toMap(ProductAttributeEntity::getId, attribute -> attribute));

        Map<Long, StringBuilder> buffers = new HashMap<>();
        for (SpuAttributeValueEntity value : values) {
            ProductAttributeEntity attribute = attributes.get(value.getAttributeId());
            if (attribute == null) {
                continue;
            }
            buffers.computeIfAbsent(value.getSpuId(), key -> new StringBuilder())
                    .append(attribute.getName())
                    .append(':')
                    .append(value.getAttrValue())
                    .append(' ');
        }

        return buffers.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().toString().trim()));
    }

    private ProductIndex toIndex(ProductSpuEntity spu,
                                 List<ProductSkuEntity> skus,
                                 Map<Long, String> categoryNames,
                                 Map<Long, String> brandNames,
                                 String attributeText) {
        Long minPrice = null;
        Long maxPrice = null;
        int totalStock = 0;
        if (!skus.isEmpty()) {
            minPrice = skus.stream().mapToLong(ProductSkuEntity::getPrice).min().orElse(0L);
            maxPrice = skus.stream().mapToLong(ProductSkuEntity::getPrice).max().orElse(0L);
            totalStock = skus.stream().mapToInt(ProductSkuEntity::getStock).sum();
        }

        return ProductIndex.builder()
                .id(spu.getId())
                .name(spu.getName())
                .subtitle(spu.getSubtitle())
                .categoryId(spu.getCategoryId())
                .categoryName(categoryNames.get(spu.getCategoryId()))
                .brandId(spu.getBrandId())
                .brandName(brandNames.get(spu.getBrandId()))
                .tags(spu.getTags())
                .minPrice(minPrice)
                .maxPrice(maxPrice)
                .totalStock(totalStock)
                .sales(spu.getSales())
                .ratingAvg(spu.getRatingAvg() == null ? null : spu.getRatingAvg().doubleValue())
                .reviewCount(spu.getReviewCount())
                .status(spu.getStatus())
                .mainImage(spu.getMainImage())
                .detailText(stripHtml(spu.getDetailHtml()))
                .attributeText(attributeText)
                .createdAt(spu.getCreatedAt() == null ? null
                        : spu.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
                .build();
    }

    private String stripHtml(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        // 标签换成空格而不是直接删：`<p>维生素</p><p>D3</p>` 删标签会粘成
        // "维生素D3"，而原文里这是两个词。换成空格才是它本来的分词结果
        return WHITESPACE.matcher(HTML_TAG.matcher(html).replaceAll(" ")).replaceAll(" ").trim();
    }
}
