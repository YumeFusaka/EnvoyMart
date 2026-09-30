package yumefusaka.envoymart.productservice.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Component;
import yumefusaka.envoymart.contract.AttributeView;
import yumefusaka.envoymart.contract.SkuView;
import yumefusaka.envoymart.contract.SpecGroup;
import yumefusaka.envoymart.productservice.entity.ProductAttributeEntity;
import yumefusaka.envoymart.productservice.entity.ProductSkuEntity;
import yumefusaka.envoymart.productservice.entity.ProductSpecEntity;
import yumefusaka.envoymart.productservice.entity.ProductSpecValueEntity;
import yumefusaka.envoymart.productservice.entity.SpuAttributeValueEntity;
import yumefusaka.envoymart.productservice.mapper.ProductAttributeMapper;
import yumefusaka.envoymart.productservice.mapper.ProductSkuSpecMapper;
import yumefusaka.envoymart.productservice.mapper.ProductSpecMapper;
import yumefusaka.envoymart.productservice.mapper.ProductSpecValueMapper;
import yumefusaka.envoymart.productservice.mapper.SpuAttributeValueMapper;
import yumefusaka.envoymart.productservice.model.SkuSpecView;

import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 商品详情的组装 —— 公开详情与管理详情共用。
 * <p>
 * <b>为什么把它抽出来而不是两边各写一份</b>：规格组、参数、SKU 视图这三块的组装逻辑，
 * 在「买家看的详情」和「运营看的详情」里是同一个东西，差别只在<b>取哪些 SKU</b>
 * （买家只看启用中的，运营要看全部）。这个差别在调用方一行就能表达；
 * 而如果为此把组装整体复制一份，两边就会各自演化——加一个规格字段时改了一边忘了另一边，
 * 症状是「管理台有的字段前台没有」，没有报错、只有排查时的一次意外。
 */
@Component
public class ProductAssembler {

    /** 属性被删但取值还在时跳过，不能让一条脏数据把整个详情页打不开——这里定义这个哨兵 */
    private static final Comparator<AttributeView> BY_ATTRIBUTE_ID =
            Comparator.comparing(AttributeView::getAttributeId);

    private final ProductSpecMapper specMapper;
    private final ProductSpecValueMapper specValueMapper;
    private final ProductSkuSpecMapper skuSpecMapper;
    private final ProductAttributeMapper attributeMapper;
    private final SpuAttributeValueMapper spuAttributeValueMapper;

    public ProductAssembler(ProductSpecMapper specMapper,
                            ProductSpecValueMapper specValueMapper,
                            ProductSkuSpecMapper skuSpecMapper,
                            ProductAttributeMapper attributeMapper,
                            SpuAttributeValueMapper spuAttributeValueMapper) {
        this.specMapper = specMapper;
        this.specValueMapper = specValueMapper;
        this.skuSpecMapper = skuSpecMapper;
        this.attributeMapper = attributeMapper;
        this.spuAttributeValueMapper = spuAttributeValueMapper;
    }

    /** 规格组：规格名 + 它的全部可选值。前台的选择器与管理台的规格编辑器用的是同一份数据 */
    public List<SpecGroup> specGroups(Long spuId) {
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

    /** SKU 的展示视图。{@code specText} 在这里拼好，购物车与订单详情直接复用 */
    public List<SkuView> skuViews(List<ProductSkuEntity> skus) {
        if (skus.isEmpty()) {
            return List.of();
        }
        Map<Long, List<SkuSpecView>> specsBySku = specsBySku(skus.stream().map(ProductSkuEntity::getId).toList());

        return skus.stream()
                .map(sku -> SkuView.builder()
                        .id(sku.getId())
                        .skuCode(sku.getSkuCode())
                        .price(sku.getPrice())
                        .originalPrice(sku.getOriginalPrice())
                        .stock(sku.getStock())
                        .image(sku.getImage())
                        .specValueIds(specsBySku.getOrDefault(sku.getId(), List.of()).stream()
                                .map(SkuSpecView::getSpecValueId)
                                .toList())
                        .specText(specTextOf(specsBySku.getOrDefault(sku.getId(), List.of())))
                        .build())
                .toList();
    }

    /**
     * SKU → 「规格名 → 规格值名」。
     * <p>
     * 管理端的编辑表单要的是名字而不是 id：提交时用的也是名字（新规格值此刻还没有 id）。
     * 回显与提交用同一套表达，前端不必在两套标识之间来回翻译。
     */
    public Map<Long, Map<String, String>> specValuesBySku(List<Long> skuIds) {
        if (skuIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Map<String, String>> out = new LinkedHashMap<>();
        specsBySku(skuIds).forEach((skuId, specs) -> {
            Map<String, String> byName = new LinkedHashMap<>();
            specs.forEach(spec -> byName.put(spec.getSpecName(), spec.getSpecValue()));
            out.put(skuId, byName);
        });
        return out;
    }

    /** 商品参数（属性名 + 值 + 单位），按属性 id 排序，保证同一商品的展示顺序稳定 */
    public List<AttributeView> attributes(Long spuId) {
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
                .sorted(BY_ATTRIBUTE_ID)
                .toList();
    }

    /** 逗号分隔字段（轮播图、标签）转列表 */
    public static List<String> splitByComma(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(part -> !part.isEmpty())
                .toList();
    }

    private Map<Long, List<SkuSpecView>> specsBySku(List<Long> skuIds) {
        return skuSpecMapper.selectBySkuIds(skuIds).stream()
                .collect(Collectors.groupingBy(SkuSpecView::getSkuId));
    }

    private String specTextOf(List<SkuSpecView> specs) {
        return specs.stream()
                .map(spec -> spec.getSpecName() + ":" + spec.getSpecValue())
                .collect(Collectors.joining(";"));
    }
}
