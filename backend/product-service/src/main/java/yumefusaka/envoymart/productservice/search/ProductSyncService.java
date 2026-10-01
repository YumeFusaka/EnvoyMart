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
import java.util.ArrayList;
import java.util.Arrays;
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

    /**
     * 启动时全量同步。
     * <p>
     * <b>失败不能拖垮启动。</b>ES 只是派生存储：索引没同步的代价是「搜不到新商品」，
     * 而商品服务起不来的代价是「整个下单链路不可用」—— 后者严重得多。
     * 此前这里没有 try/catch，ES 一挂，服务直接起不来。
     */
    @PostConstruct
    public void syncAll() {
        try {
            List<ProductSpuEntity> spus = spuMapper.selectList(null);
            if (spus.isEmpty()) {
                log.info("商品库为空，跳过 ES 全量同步");
                return;
            }
            List<ProductIndex> indices = buildIndices(spus);
            searchRepository.saveAll(indices);
            log.info("ES 商品索引同步完成，共 {} 条", indices.size());
        } catch (Exception e) {
            // 留 ERROR 而不是静默：索引为空时搜索会一直返回空结果，那很容易被当成业务问题
            log.error("[ES] 启动全量同步失败，搜索功能将不可用直到下次商品变更触发单条同步", e);
        }
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
        Map<Long, List<String>> attributes = buildAttributes(spuIds);

        return spus.stream()
                .map(spu -> toIndex(spu,
                        skusBySpu.getOrDefault(spu.getId(), List.of()),
                        categoryNames,
                        brandNames,
                        attributes.getOrDefault(spu.getId(), List.of())))
                .toList();
    }

    /**
     * 商品参数的两种表示一次算出来：全文检索用的文本，与筛选用的 {@code 属性名:属性值} 列表。
     * <p>
     * 参数是结构化数据、默认不进全文索引，于是「胶囊」这类词搜不到任何商品 ——
     * 而用户确实会这么搜。拼成文本入索引解决的是这一半。
     * <p>
     * 另一半是筛选：用户说「孕妇能吃的」，要判断的是「适用人群里有孕妇」，
     * 而这在拼好的文本上做不了——用短语匹配 {@code 适用人群:孕妇} 时，
     * 同一字段里写着 {@code 适用人群:成人,老年人} 的商品会因为中间隔了别的字而不命中，
     * 但「适用人群:成人」又会命中。**同一件事对不同的值给出相反答案**，
     * 所以筛选走结构化那份。两处出自同一次遍历，不会各说各话。
     */
    private Map<Long, List<String>> buildAttributes(List<Long> spuIds) {
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

        Map<Long, List<String>> result = new HashMap<>();
        for (SpuAttributeValueEntity value : values) {
            ProductAttributeEntity attribute = attributes.get(value.getAttributeId());
            if (attribute == null) {
                continue;
            }
            List<String> items = result.computeIfAbsent(value.getSpuId(), key -> new ArrayList<>());
            for (String single : splitValues(attribute, value.getAttrValue())) {
                items.add(attribute.getName() + ":" + single);
            }
        }
        return result;
    }

    /**
     * 拆多选属性的取值。**只有 MULTI_SELECT 拆**：TEXT 类型里逗号是正文的一部分，
     * 拆开就成了两个不存在的取值（「进食受限人群，需医师指导」会被拆成两条）。
     * 中英文逗号都认：运营在两个输入框里都可能打出来。
     */
    private List<String> splitValues(ProductAttributeEntity attribute, String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        if (!"MULTI_SELECT".equals(attribute.getInputType())) {
            return List.of(raw.trim());
        }
        return Arrays.stream(raw.split("[,，]"))
                .map(String::trim)
                .filter(single -> !single.isEmpty())
                .toList();
    }

    /**
     * 单字通道的文本 = 参与检索的那些字段拼在一起。
     * <p>
     * 逐字切分会把标点与空白当分隔符丢掉，所以这里用什么分隔符不影响词元；
     * 用空格只是为了让索引里这份文本还读得懂（排查时要人去翻 {@code _source}）。
     * <p>
     * <b>空值必须先滤掉</b>：{@code String.join} 遇到 null 会把它写成字面量 "null"，
     * 那四个字母会变成一个真实词元——搜「null」能搜到商品，而没人会想到是这里来的。
     */
    private String unigramSource(String... texts) {
        return Arrays.stream(texts)
                .filter(text -> text != null && !text.isBlank())
                .collect(Collectors.joining(" "));
    }

    private ProductIndex toIndex(ProductSpuEntity spu,
                                 List<ProductSkuEntity> skus,
                                 Map<Long, String> categoryNames,
                                 Map<Long, String> brandNames,
                                 List<String> attributes) {
        Long minPrice = null;
        Long maxPrice = null;
        int totalStock = 0;
        if (!skus.isEmpty()) {
            minPrice = skus.stream().mapToLong(ProductSkuEntity::getPrice).min().orElse(0L);
            maxPrice = skus.stream().mapToLong(ProductSkuEntity::getPrice).max().orElse(0L);
            totalStock = skus.stream().mapToInt(ProductSkuEntity::getStock).sum();
        }

        String detailText = stripHtml(spu.getDetailHtml());
        String attributeText = String.join(" ", attributes);
        String categoryName = categoryNames.get(spu.getCategoryId());
        String brandName = brandNames.get(spu.getBrandId());

        return ProductIndex.builder()
                .id(spu.getId())
                .name(spu.getName())
                .subtitle(spu.getSubtitle())
                .categoryId(spu.getCategoryId())
                .categoryName(categoryName)
                .brandId(spu.getBrandId())
                .brandName(brandName)
                .tags(spu.getTags())
                .minPrice(minPrice)
                .maxPrice(maxPrice)
                .totalStock(totalStock)
                .sales(spu.getSales())
                .ratingAvg(spu.getRatingAvg() == null ? null : spu.getRatingAvg().doubleValue())
                .reviewCount(spu.getReviewCount())
                .status(spu.getStatus())
                .mainImage(spu.getMainImage())
                .detailText(detailText)
                .attributeText(attributeText)
                .attributes(attributes)
                // 与 ProductSearchService 的 MATCH_FIELDS 是同一组字段：两个通道要能覆盖同样的范围，
                // 否则会出现「二元组那条能搜到的、单字这条搜不到」，而排查时只会看到「有时候好使」
                .unigramText(unigramSource(spu.getName(), spu.getSubtitle(), spu.getTags(),
                        categoryName, brandName, attributeText, detailText))
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
