package yumefusaka.envoymart.productservice.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import yumefusaka.envoymart.common.result.PageResult;
import yumefusaka.envoymart.common.util.Times;
import yumefusaka.envoymart.productservice.content.HtmlSanitizer;
import yumefusaka.envoymart.productservice.entity.ProductAttributeEntity;
import yumefusaka.envoymart.productservice.entity.ProductSkuEntity;
import yumefusaka.envoymart.productservice.entity.ProductSkuSpecEntity;
import yumefusaka.envoymart.productservice.entity.ProductSpecEntity;
import yumefusaka.envoymart.productservice.entity.ProductSpecValueEntity;
import yumefusaka.envoymart.productservice.entity.ProductSpuEntity;
import yumefusaka.envoymart.productservice.entity.SpuAttributeValueEntity;
import yumefusaka.envoymart.productservice.entity.StockLogEntity;
import yumefusaka.envoymart.productservice.mapper.ProductAttributeMapper;
import yumefusaka.envoymart.productservice.mapper.ProductSkuMapper;
import yumefusaka.envoymart.productservice.mapper.ProductSkuSpecMapper;
import yumefusaka.envoymart.productservice.mapper.ProductSpecMapper;
import yumefusaka.envoymart.productservice.mapper.ProductSpecValueMapper;
import yumefusaka.envoymart.productservice.mapper.ProductSpuMapper;
import yumefusaka.envoymart.productservice.mapper.SpuAttributeValueMapper;
import yumefusaka.envoymart.productservice.mapper.StockLogMapper;
import yumefusaka.envoymart.productservice.model.admin.AdminSpuDetail;
import yumefusaka.envoymart.productservice.model.admin.AdminSpuQuery;
import yumefusaka.envoymart.productservice.model.admin.AdminSpuSummary;
import yumefusaka.envoymart.productservice.model.admin.AttributeRequest;
import yumefusaka.envoymart.productservice.model.admin.SkuRequest;
import yumefusaka.envoymart.productservice.model.admin.SpecRequest;
import yumefusaka.envoymart.productservice.model.admin.SpuUpsertRequest;
import yumefusaka.envoymart.productservice.model.admin.StockAdjustRequest;
import yumefusaka.envoymart.productservice.service.CategoryService;
import yumefusaka.envoymart.productservice.service.ProductAdminService;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 商品域管理侧的实现。
 *
 * <h3>一次保存里做的四件事</h3>
 * SPU 行、规格与规格值、SKU、商品参数，加上「删掉请求里不再存在的那些」。
 * 全部在<b>一个事务</b>里完成：管理台是一张表单，分步保存意味着中途失败会留下
 * 一个规格改了、SKU 没改的商品——那个状态谁也看不出是坏的。
 *
 * <h3>写完之后必然跟着两件事</h3>
 * 失效商品缓存、同步 ES 索引，且都注册在<b>事务提交之后</b>。
 * 在事务里同步的话，一旦回滚，索引已经被改成「改过之后」的样子而库里没有——
 * 这种不一致不会报错，只会在某天有人对着搜索结果发懵时暴露。
 */
@Slf4j
@Service
public class ProductAdminServiceImpl implements ProductAdminService {

    private static final int STATUS_ON = 1;
    private static final int STATUS_OFF = 0;

    private static final String CHANGE_INBOUND = "INBOUND";
    private static final String CHANGE_DEDUCT = "DEDUCT";
    /** 人工调整。订单与售后的流转各自有自己的取值，管理台的手工改动一律归到这里 */
    private static final String BIZ_MANUAL = "MANUAL";

    /**
     * 管理端列表的排序白名单。
     * <p>
     * 只给两个：管理列表的默认诉求是「最近改过的排前面」，其次是「卖得好的排前面」。
     * 公开列表那套价格排序在这里没有意义（运营不会按价格翻商品），
     * 而复用它要把带子查询的排序子句也一起搬过来——白名单越大，越容易有人往里塞新值而忘了它会被拼进 SQL。
     */
    private static final Map<String, String> SORT_CLAUSES = Map.of(
            "sales", "sales desc",
            "updated", "updated_at desc");

    private static final String DEFAULT_SORT = "updated_at desc";

    private final ProductSpuMapper spuMapper;
    private final ProductSkuMapper skuMapper;
    private final ProductSpecMapper specMapper;
    private final ProductSpecValueMapper specValueMapper;
    private final ProductSkuSpecMapper skuSpecMapper;
    private final ProductAttributeMapper attributeMapper;
    private final SpuAttributeValueMapper spuAttributeValueMapper;
    private final StockLogMapper stockLogMapper;
    private final ProductAssembler assembler;
    private final CategoryService categoryService;
    /** 写完之后必然要刷新的两份派生副本（缓存、ES 索引） */
    private final ProductDerivedRefresh derivedRefresh;

    public ProductAdminServiceImpl(ProductSpuMapper spuMapper,
                                   ProductSkuMapper skuMapper,
                                   ProductSpecMapper specMapper,
                                   ProductSpecValueMapper specValueMapper,
                                   ProductSkuSpecMapper skuSpecMapper,
                                   ProductAttributeMapper attributeMapper,
                                   SpuAttributeValueMapper spuAttributeValueMapper,
                                   StockLogMapper stockLogMapper,
                                   ProductAssembler assembler,
                                   CategoryService categoryService,
                                   ProductDerivedRefresh derivedRefresh) {
        this.spuMapper = spuMapper;
        this.skuMapper = skuMapper;
        this.specMapper = specMapper;
        this.specValueMapper = specValueMapper;
        this.skuSpecMapper = skuSpecMapper;
        this.attributeMapper = attributeMapper;
        this.spuAttributeValueMapper = spuAttributeValueMapper;
        this.stockLogMapper = stockLogMapper;
        this.assembler = assembler;
        this.categoryService = categoryService;
        this.derivedRefresh = derivedRefresh;
    }

    // ==================== 读 ====================

    @Override
    public PageResult<AdminSpuSummary> list(AdminSpuQuery query) {
        Page<ProductSpuEntity> page = new Page<>(query.mpCurrent(), query.safeSize());
        Page<ProductSpuEntity> result = spuMapper.selectPage(page, buildWrapper(query));
        return PageResult.<AdminSpuSummary>builder()
                .records(assembleSummaries(result.getRecords()))
                .total(result.getTotal())
                .page(query.zeroBasedPage())
                .size(query.safeSize())
                .build();
    }

    @Override
    public AdminSpuDetail detail(Long spuId) {
        ProductSpuEntity spu = requireSpu(spuId);
        // 不过滤 status：停用中的规格组合必须能在管理台看到，否则停用之后没有入口改回来
        List<ProductSkuEntity> skus = skuMapper.selectList(new LambdaQueryWrapper<ProductSkuEntity>()
                .eq(ProductSkuEntity::getSpuId, spuId)
                .orderByAsc(ProductSkuEntity::getId));
        Map<Long, Map<String, String>> specValues = assembler.specValuesBySku(
                skus.stream().map(ProductSkuEntity::getId).toList());

        return AdminSpuDetail.builder()
                .id(spu.getId())
                .spuCode(spu.getSpuCode())
                .name(spu.getName())
                .subtitle(spu.getSubtitle())
                .categoryId(spu.getCategoryId())
                .brandId(spu.getBrandId())
                .mainImage(spu.getMainImage())
                .images(ProductAssembler.splitByComma(spu.getImages()))
                // 回显的是净化后的内容：管理员在编辑框里看到什么、前台就渲染什么，
                // 避免「保存时被净化、界面上一刷新发现内容少了」这种莫名现象
                .detailHtml(HtmlSanitizer.clean(spu.getDetailHtml()))
                .tags(ProductAssembler.splitByComma(spu.getTags()))
                .status(spu.getStatus())
                .sales(spu.getSales())
                .ratingAvg(spu.getRatingAvg())
                .reviewCount(spu.getReviewCount())
                .createdAt(spu.getCreatedAt())
                .updatedAt(spu.getUpdatedAt())
                .specs(assembler.specGroups(spuId))
                .skus(skus.stream()
                        .map(sku -> AdminSpuDetail.AdminSku.builder()
                                .id(sku.getId())
                                .skuCode(sku.getSkuCode())
                                .price(sku.getPrice())
                                .originalPrice(sku.getOriginalPrice())
                                .stock(sku.getStock())
                                .image(sku.getImage())
                                .status(sku.getStatus())
                                .specValues(specValues.getOrDefault(sku.getId(), Map.of()))
                                .build())
                        .toList())
                .attributes(assembler.attributes(spuId))
                .build();
    }

    // ==================== 写 ====================

    @Override
    @Transactional
    public Long create(SpuUpsertRequest request, String operatorId) {
        validateCategory(request.getCategoryId());
        validateBrand(request.getBrandId());

        ProductSpuEntity spu = new ProductSpuEntity();
        applySpuFields(spu, request);
        spu.setSpuCode(StringUtils.hasText(request.getSpuCode())
                ? request.getSpuCode().trim() : generateCode("SPU"));
        LocalDateTime now = Times.now();
        spu.setCreatedAt(now);
        spu.setUpdatedAt(now);
        insertSpu(spu);

        Long spuId = spu.getId();
        Map<String, SpecEntry> specIndex = reconcileSpecs(spuId, request.getSpecs());
        reconcileSkus(spuId, request.getSkus(), specIndex, operatorId);
        reconcileAttributes(spuId, request.getAttributes());

        derivedRefresh.afterCommit(spuId);
        log.info("[管理] 新建商品 spuId={} code={} operator={}", spuId, spu.getSpuCode(), operatorId);
        return spuId;
    }

    @Override
    @Transactional
    public void update(Long spuId, SpuUpsertRequest request, String operatorId) {
        ProductSpuEntity spu = requireSpu(spuId);
        validateCategory(request.getCategoryId());
        validateBrand(request.getBrandId());

        applySpuFields(spu, request);
        // 编辑时留空表示「不动原编码」，而不是「把编码清掉」——它是对外可见的业务标识
        if (StringUtils.hasText(request.getSpuCode())) {
            spu.setSpuCode(request.getSpuCode().trim());
        }
        spu.setUpdatedAt(Times.now());
        updateSpu(spu);

        Map<String, SpecEntry> specIndex = reconcileSpecs(spuId, request.getSpecs());
        reconcileSkus(spuId, request.getSkus(), specIndex, operatorId);
        reconcileAttributes(spuId, request.getAttributes());

        derivedRefresh.afterCommit(spuId);
        log.info("[管理] 编辑商品 spuId={} operator={}", spuId, operatorId);
    }

    @Override
    @Transactional
    public void changeStatus(Long spuId, Integer status) {
        if (status == null || (status != STATUS_ON && status != STATUS_OFF)) {
            throw new IllegalArgumentException("上下架状态只能是 0（下架）或 1（上架）");
        }
        ProductSpuEntity spu = requireSpu(spuId);
        if (Objects.equals(spu.getStatus(), status)) {
            // 幂等：重复点同一个按钮不该报错，也没有任何需要重建的东西
            return;
        }
        spu.setStatus(status);
        spu.setUpdatedAt(Times.now());
        updateSpu(spu);
        derivedRefresh.afterCommit(spuId);
        log.info("[管理] 商品 {} spuId={}", status == STATUS_ON ? "上架" : "下架", spuId);
    }

    @Override
    @Transactional
    public void adjustStock(Long skuId, StockAdjustRequest request, String operatorId) {
        ProductSkuEntity sku = skuMapper.selectById(skuId);
        if (sku == null) {
            throw new IllegalArgumentException("商品规格不存在");
        }
        int before = sku.getStock() == null ? 0 : sku.getStock();
        int after = request.getStock();
        if (before == after) {
            // 没变就不写流水：流水是「库存为什么变了」的答案，没有变化就没有问题要回答，
            // 而一条 0 增量的记录会让真正的变动淹没在噪音里
            return;
        }

        // 条件更新而不是 updateById：这是「读-改-写」的另一条路径，
        // 商家看到 10、点保存的瞬间买家买走 1 件，整行覆盖会把那笔扣减抹掉。
        // 盘库录入的是目标值，所以条件写在「旧值没变过」上，冲突就报出来让人刷新重来
        if (skuMapper.updateStockIfUnchanged(skuId, before, after) == 0) {
            // 与下单扣减的「库存不足」区分开：那是库存真不够，这是数字在你看的时候变了。
            // 提示里必须带「刷新」这个动作，否则运营只会反复点同一个按钮。
            // 不回显当前值：本事务的快照还停在 before，这里再查一次读到的是旧数字，
            // 报给运营只会让他更困惑
            throw new IllegalStateException("库存已被其他操作变更，请刷新后重试");
        }
        writeStockLog(skuId, after > before ? CHANGE_INBOUND : CHANGE_DEDUCT,
                Math.abs(after - before), before, after, operatorId, request.getRemark());
        derivedRefresh.afterCommit(sku.getSpuId());
        log.info("[管理] 调整库存 skuId={} {}→{} operator={}", skuId, before, after, operatorId);
    }

    @Override
    @Transactional
    public void delete(Long spuId) {
        ProductSpuEntity spu = requireSpu(spuId);
        List<ProductSkuEntity> skus = skuMapper.selectList(new LambdaQueryWrapper<ProductSkuEntity>()
                .eq(ProductSkuEntity::getSpuId, spuId));
        List<Long> skuIds = skus.stream().map(ProductSkuEntity::getId).toList();

        if (!skuIds.isEmpty()) {
            int ordered = stockLogMapper.countOrderedSkus(skuIds);
            if (ordered > 0) {
                // 历史订单引用的是 SKU id：删掉之后那些订单会变成查不到商品的空壳，
                // 而这个问题要到用户翻旧订单时才暴露。卖过的商品只能下架
                throw new IllegalStateException(
                        "该商品已有 " + ordered + " 个规格产生过订单，不能删除。请改为「下架」——"
                                + "历史订单还需要能查到它。");
            }
            skuSpecMapper.delete(new LambdaQueryWrapper<ProductSkuSpecEntity>()
                    .in(ProductSkuSpecEntity::getSkuId, skuIds));
            skuMapper.deleteByIds(skuIds);
        }

        spuAttributeValueMapper.delete(new LambdaQueryWrapper<SpuAttributeValueEntity>()
                .eq(SpuAttributeValueEntity::getSpuId, spuId));
        List<ProductSpecEntity> specs = specMapper.selectList(new LambdaQueryWrapper<ProductSpecEntity>()
                .eq(ProductSpecEntity::getSpuId, spuId));
        for (ProductSpecEntity spec : specs) {
            specValueMapper.delete(new LambdaQueryWrapper<ProductSpecValueEntity>()
                    .eq(ProductSpecValueEntity::getSpecId, spec.getId()));
        }
        specMapper.delete(new LambdaQueryWrapper<ProductSpecEntity>()
                .eq(ProductSpecEntity::getSpuId, spuId));
        spuMapper.deleteById(spuId);

        derivedRefresh.afterCommit(spuId);
        log.info("[管理] 删除商品 spuId={} code={}", spuId, spu.getSpuCode());
    }

    // ==================== SPU 字段 ====================

    private void applySpuFields(ProductSpuEntity spu, SpuUpsertRequest request) {
        Integer status = request.getStatus();
        if (status != null && status != STATUS_ON && status != STATUS_OFF) {
            throw new IllegalArgumentException("上下架状态只能是 0（下架）或 1（上架）");
        }
        spu.setName(request.getName().trim());
        spu.setSubtitle(trimToNull(request.getSubtitle()));
        spu.setCategoryId(request.getCategoryId());
        spu.setBrandId(request.getBrandId());
        spu.setMainImage(trimToNull(request.getMainImage()));
        spu.setImages(joinNonBlank(request.getImages()));
        // 写入侧净化：库里的内容从这一刻起就是干净的，而不是等到渲染时才处理
        spu.setDetailHtml(HtmlSanitizer.clean(request.getDetailHtml()));
        spu.setTags(joinNonBlank(request.getTags()));
        spu.setStatus(status == null ? STATUS_OFF : status);
    }

    private void insertSpu(ProductSpuEntity spu) {
        try {
            spuMapper.insert(spu);
        } catch (DuplicateKeyException e) {
            // 自动生成的编码理论上不撞，手工填的会。把它翻成一句能指导下一步的话，
            // 而不是让唯一约束异常原样冒到接口上
            throw new IllegalArgumentException("商品编码「" + spu.getSpuCode() + "」已存在，请换一个", e);
        }
    }

    private void updateSpu(ProductSpuEntity spu) {
        try {
            spuMapper.updateById(spu);
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException("商品编码「" + spu.getSpuCode() + "」已存在，请换一个", e);
        }
    }

    // ==================== 规格 ====================

    /** 一组规格在库里的落点：规格 id 与「规格值名 → 规格值 id」 */
    private record SpecEntry(Long specId, Map<String, Long> valueIds) {
    }

    /**
     * 把提交的规格整组落到库里，返回「规格名 → 落点」供 SKU 关联使用。
     * <p>
     * <b>整组替换，不是按 id 打补丁</b>：表单上看到的就是全部。按 id 打补丁的话，
     * 「删掉一个规格」在协议里没有表达方式——调用方只能靠「这次没提交它」来暗示，
     * 而那与「这次没打算改它」长得一模一样。
     * <p>
     * 顺序上<b>先建后删</b>：先把请求里的规格与规格值都建出来，再删掉多余的。
     * 反过来的话，一个「删掉 90 粒、加上 100 粒」的编辑会在中间经过一个
     * 「90 粒已删、100 粒未建」的状态，此刻若 SKU 还引用着 90 粒，关联就断了。
     */
    private Map<String, SpecEntry> reconcileSpecs(Long spuId, List<SpecRequest> requests) {
        List<ProductSpecEntity> existing = specMapper.selectList(new LambdaQueryWrapper<ProductSpecEntity>()
                .eq(ProductSpecEntity::getSpuId, spuId));
        Map<String, ProductSpecEntity> byName = existing.stream()
                .collect(Collectors.toMap(ProductSpecEntity::getName, Function.identity(),
                        (first, second) -> first, LinkedHashMap::new));

        Map<String, SpecRequest> requested = new LinkedHashMap<>();
        for (SpecRequest spec : requests) {
            String name = spec.getName().trim();
            if (requested.put(name, spec) != null) {
                throw new IllegalArgumentException("规格「" + name + "」重复了");
            }
        }

        Map<String, SpecEntry> index = new LinkedHashMap<>();
        int specSort = 0;
        for (Map.Entry<String, SpecRequest> entry : requested.entrySet()) {
            String name = entry.getKey();
            ProductSpecEntity spec = byName.get(name);
            if (spec == null) {
                spec = new ProductSpecEntity();
                spec.setSpuId(spuId);
                spec.setName(name);
                spec.setSort(specSort);
                specMapper.insert(spec);
            } else if (!Objects.equals(spec.getSort(), specSort)) {
                spec.setSort(specSort);
                specMapper.updateById(spec);
            }

            Set<String> wanted = new LinkedHashSet<>();
            for (String value : entry.getValue().getValues()) {
                if (StringUtils.hasText(value)) {
                    wanted.add(value.trim());
                }
            }
            if (wanted.isEmpty()) {
                throw new IllegalArgumentException("规格「" + name + "」至少需要一个可选值");
            }

            Map<String, ProductSpecValueEntity> existingValues = specValueMapper
                    .selectList(new LambdaQueryWrapper<ProductSpecValueEntity>()
                            .eq(ProductSpecValueEntity::getSpecId, spec.getId()))
                    .stream()
                    .collect(Collectors.toMap(ProductSpecValueEntity::getSpecValue, Function.identity(),
                            (first, second) -> first));

            Map<String, Long> valueIds = new LinkedHashMap<>();
            int valueSort = 0;
            for (String value : wanted) {
                ProductSpecValueEntity entity = existingValues.get(value);
                if (entity == null) {
                    entity = new ProductSpecValueEntity();
                    entity.setSpecId(spec.getId());
                    entity.setSpecValue(value);
                    entity.setSort(valueSort);
                    specValueMapper.insert(entity);
                } else if (!Objects.equals(entity.getSort(), valueSort)) {
                    entity.setSort(valueSort);
                    specValueMapper.updateById(entity);
                }
                valueIds.put(value, entity.getId());
                valueSort++;
            }
            // 建完之后再删多余的规格值：此刻 SKU 的关联还没重建，删掉的这些值不会再被引用
            for (ProductSpecValueEntity leftover : existingValues.values()) {
                if (!wanted.contains(leftover.getSpecValue())) {
                    deleteSkuSpecByValue(leftover.getId());
                    specValueMapper.deleteById(leftover.getId());
                }
            }
            index.put(name, new SpecEntry(spec.getId(), valueIds));
            specSort++;
        }

        // 请求里没有的规格整个删掉（含它的规格值，以及引用这些值的关联）
        for (ProductSpecEntity leftover : existing) {
            if (!requested.containsKey(leftover.getName())) {
                for (ProductSpecValueEntity value : specValueMapper
                        .selectList(new LambdaQueryWrapper<ProductSpecValueEntity>()
                                .eq(ProductSpecValueEntity::getSpecId, leftover.getId()))) {
                    deleteSkuSpecByValue(value.getId());
                    specValueMapper.deleteById(value.getId());
                }
                specMapper.deleteById(leftover.getId());
            }
        }
        return index;
    }

    private void deleteSkuSpecByValue(Long specValueId) {
        skuSpecMapper.delete(new LambdaQueryWrapper<ProductSkuSpecEntity>()
                .eq(ProductSkuSpecEntity::getSpecValueId, specValueId));
    }

    // ==================== SKU ====================

    /**
     * 按提交重建这个商品的 SKU 集合。
     * <p>
     * <b>带 id 的是「改」，不带的才是「新建」，请求里没有的删掉。</b>不能写成
     * 「全删再全建」——订单引用的是 SKU id，重建会让所有历史订单指向不存在的规格，
     * 而且这个错误在保存成功的那一刻完全不可见。
     */
    private void reconcileSkus(Long spuId,
                               List<SkuRequest> requests,
                               Map<String, SpecEntry> specIndex,
                               String operatorId) {
        Map<Long, ProductSkuEntity> existing = skuMapper
                .selectList(new LambdaQueryWrapper<ProductSkuEntity>()
                        .eq(ProductSkuEntity::getSpuId, spuId)
                        .orderByAsc(ProductSkuEntity::getId))
                .stream()
                .collect(Collectors.toMap(ProductSkuEntity::getId, Function.identity(),
                        (first, second) -> first, LinkedHashMap::new));

        Set<Long> keptIds = new LinkedHashSet<>();
        for (SkuRequest request : requests) {
            if (request.getId() != null) {
                if (!existing.containsKey(request.getId())) {
                    throw new IllegalArgumentException(
                            "规格 " + request.getId() + " 不属于这个商品，不能在这里修改");
                }
                keptIds.add(request.getId());
            }
        }

        List<Long> removedIds = existing.keySet().stream()
                .filter(id -> !keptIds.contains(id))
                .toList();
        if (!removedIds.isEmpty()) {
            int ordered = stockLogMapper.countOrderedSkus(removedIds);
            if (ordered > 0) {
                throw new IllegalStateException("有 " + ordered + " 个规格已被订单引用，不能删除。"
                        + "请把它们改为「停用」——历史订单还需要能查到它们。");
            }
            skuSpecMapper.delete(new LambdaQueryWrapper<ProductSkuSpecEntity>()
                    .in(ProductSkuSpecEntity::getSkuId, removedIds));
            skuMapper.deleteByIds(removedIds);
        }

        // skuId → 提交上来的规格名到规格值名
        List<Map.Entry<Long, Map<String, String>>> specAssignments = new ArrayList<>();
        for (SkuRequest request : requests) {
            Map<String, String> assignment = normalizeSpecValues(request.getSpecValues(), specIndex);
            ProductSkuEntity sku;
            if (request.getId() == null) {
                sku = new ProductSkuEntity();
                sku.setSpuId(spuId);
                sku.setSkuCode(StringUtils.hasText(request.getSkuCode())
                        ? request.getSkuCode().trim() : generateCode("SKU"));
                applySkuFields(sku, request);
                insertSku(sku);
                // 期初库存也是一次入库：不留流水的话，这个 SKU 的库存就凭空出现了，
                // 而流水表存在的意义正是「每一次变动都有来源」
                if (sku.getStock() > 0) {
                    writeStockLog(sku.getId(), CHANGE_INBOUND, sku.getStock(), 0, sku.getStock(),
                            operatorId, "新建商品时录入");
                }
            } else {
                sku = existing.get(request.getId());
                int before = sku.getStock() == null ? 0 : sku.getStock();
                if (StringUtils.hasText(request.getSkuCode())) {
                    sku.setSkuCode(request.getSkuCode().trim());
                }
                applySkuFields(sku, request);
                int after = sku.getStock();

                // 库存从整行更新里摘出去（置 null 后 updateById 会跳过这一列），改成单独的条件更新。
                //
                // 不摘的后果不是「库存改不了」，而是更隐蔽的那种：表单里带着打开页面时读到的库存，
                // 商家改个价格点保存，就把这期间买家下单扣掉的量原样覆盖回去——他没碰库存，
                // 库存却变了，而且从界面上完全看不出来。这正是 adjustStock 要防的同一个读-改-写，
                // 只是入口在编辑页而不是库存页
                sku.setStock(null);
                updateSku(sku);

                if (before != after) {
                    if (skuMapper.updateStockIfUnchanged(sku.getId(), before, after) == 0) {
                        throw new IllegalStateException("规格库存已被其他操作变更，请刷新后重试");
                    }
                    writeStockLog(sku.getId(), after > before ? CHANGE_INBOUND : CHANGE_DEDUCT,
                            Math.abs(after - before), before, after, operatorId, "编辑商品时调整");
                }
            }
            specAssignments.add(Map.entry(sku.getId(), assignment));
        }

        // 规格关联按提交重建：先清掉这批 SKU 的全部关联，再逐个写回。
        // 增量比对在这里没有收益——一张 SKU × 规格的表本来就只有几行，
        // 而增量的写法要处理「同一规格换了值」这个既删又建的情况，容易少写或多写
        List<Long> liveIds = specAssignments.stream().map(Map.Entry::getKey).toList();
        if (!liveIds.isEmpty()) {
            skuSpecMapper.delete(new LambdaQueryWrapper<ProductSkuSpecEntity>()
                    .in(ProductSkuSpecEntity::getSkuId, liveIds));
        }
        for (Map.Entry<Long, Map<String, String>> assignment : specAssignments) {
            for (Map.Entry<String, String> pair : assignment.getValue().entrySet()) {
                SpecEntry spec = specIndex.get(pair.getKey());
                ProductSkuSpecEntity link = new ProductSkuSpecEntity();
                link.setSkuId(assignment.getKey());
                link.setSpecId(spec.specId());
                link.setSpecValueId(spec.valueIds().get(pair.getValue()));
                skuSpecMapper.insert(link);
            }
        }
    }

    /**
     * 校验 SKU 勾选的规格组合确实存在。
     * <p>
     * 不校验的话，提交里写错一个规格值名就会插出一行指向不存在规格值的关联——
     * 前台的表现是「这个规格组合点不进去」，而库里那条数据看不出任何问题。
     */
    private Map<String, String> normalizeSpecValues(Map<String, String> raw,
                                                    Map<String, SpecEntry> specIndex) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        for (Map.Entry<String, String> pair : raw.entrySet()) {
            String name = pair.getKey() == null ? "" : pair.getKey().trim();
            String value = pair.getValue() == null ? "" : pair.getValue().trim();
            if (name.isEmpty() || value.isEmpty()) {
                continue;
            }
            SpecEntry spec = specIndex.get(name);
            if (spec == null) {
                throw new IllegalArgumentException("规格「" + name + "」没有在这个商品上定义");
            }
            if (!spec.valueIds().containsKey(value)) {
                throw new IllegalArgumentException("规格「" + name + "」没有「" + value + "」这个可选值");
            }
            out.put(name, value);
        }
        return out;
    }

    private void applySkuFields(ProductSkuEntity sku, SkuRequest request) {
        Integer status = request.getStatus();
        if (status != null && status != STATUS_ON && status != STATUS_OFF) {
            throw new IllegalArgumentException("规格状态只能是 0（停用）或 1（启用）");
        }
        sku.setPrice(request.getPrice());
        sku.setOriginalPrice(request.getOriginalPrice());
        sku.setStock(request.getStock());
        sku.setImage(trimToNull(request.getImage()));
        sku.setStatus(status == null ? STATUS_ON : status);
    }

    private void insertSku(ProductSkuEntity sku) {
        try {
            skuMapper.insert(sku);
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException("SKU 编码「" + sku.getSkuCode() + "」已存在，请换一个", e);
        }
    }

    private void updateSku(ProductSkuEntity sku) {
        try {
            skuMapper.updateById(sku);
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException("SKU 编码「" + sku.getSkuCode() + "」已存在，请换一个", e);
        }
    }

    // ==================== 商品参数 ====================

    /** 整组替换：参数模板在类目上，取值在这里，一次提交就是一件商品的完整参数表 */
    private void reconcileAttributes(Long spuId, List<AttributeRequest> requests) {
        spuAttributeValueMapper.delete(new LambdaQueryWrapper<SpuAttributeValueEntity>()
                .eq(SpuAttributeValueEntity::getSpuId, spuId));
        if (requests.isEmpty()) {
            return;
        }

        Set<Long> ids = new LinkedHashSet<>();
        for (AttributeRequest request : requests) {
            if (!ids.add(request.getAttributeId())) {
                throw new IllegalArgumentException("参数项 " + request.getAttributeId() + " 提交了两次");
            }
        }
        Map<Long, ProductAttributeEntity> found = attributeMapper.selectByIds(ids).stream()
                .collect(Collectors.toMap(ProductAttributeEntity::getId, Function.identity()));

        for (AttributeRequest request : requests) {
            if (!found.containsKey(request.getAttributeId())) {
                throw new IllegalArgumentException("参数项 " + request.getAttributeId() + " 不存在");
            }
            SpuAttributeValueEntity entity = new SpuAttributeValueEntity();
            entity.setSpuId(spuId);
            entity.setAttributeId(request.getAttributeId());
            entity.setAttrValue(request.getValue().trim());
            spuAttributeValueMapper.insert(entity);
        }
    }

    // ==================== 库存流水 ====================

    /**
     * 记一条库存流水。
     * <p>
     * 与 {@code StockServiceImpl} 里的同名动作是同一条规则：<b>流水必须与那条 UPDATE
     * 在同一个事务内写</b>，这样前后值读到的才是自己刚写的结果。管理端调整和下单扣减
     * 的差别只在 {@code biz_type}（MANUAL / ORDER），对账时靠它区分「人改的」与「单扣的」。
     */
    private void writeStockLog(Long skuId, String changeType, int quantity,
                               int before, int after, String operatorId, String remark) {
        StockLogEntity entity = new StockLogEntity();
        entity.setSkuId(skuId);
        entity.setChangeType(changeType);
        entity.setQuantity(quantity);
        entity.setBeforeStock(before);
        entity.setAfterStock(after);
        entity.setBizType(BIZ_MANUAL);
        // 操作人写进 biz_id：没有它，流水只能证明「库存变了」，证明不了「谁改的」
        entity.setBizId(operatorId);
        entity.setRemark(remark);
        entity.setCreatedAt(Times.now());
        stockLogMapper.insert(entity);
    }

    // ==================== 查询条件与组装 ====================

    private LambdaQueryWrapper<ProductSpuEntity> buildWrapper(AdminSpuQuery query) {
        LambdaQueryWrapper<ProductSpuEntity> wrapper = new LambdaQueryWrapper<>();
        if (query.getStatus() != null) {
            wrapper.eq(ProductSpuEntity::getStatus, query.getStatus());
        }
        if (StringUtils.hasText(query.getKeyword())) {
            String keyword = query.getKeyword().trim();
            // 整组必须用 and(...) 包起来：不包的话 or 的优先级低于 and，
            // 前面的状态筛选会被短路掉——与公开列表里那个坑是同一个
            wrapper.and(w -> w.like(ProductSpuEntity::getName, keyword)
                    .or().like(ProductSpuEntity::getSubtitle, keyword)
                    .or().like(ProductSpuEntity::getSpuCode, keyword));
        }
        if (query.getCategoryId() != null) {
            List<Long> categoryIds = categoryService.selfAndDescendantIds(query.getCategoryId());
            if (categoryIds.isEmpty()) {
                return wrapper.eq(ProductSpuEntity::getId, -1L);
            }
            wrapper.in(ProductSpuEntity::getCategoryId, categoryIds);
        }
        if (query.getBrandId() != null) {
            wrapper.eq(ProductSpuEntity::getBrandId, query.getBrandId());
        }

        // 先判空再进 Map：SORT_CLAUSES 是 Map.of 造的不可变 Map，get(null) 直接抛 NPE
        String sortKey = query.getSort();
        wrapper.last("order by " + (sortKey == null
                ? DEFAULT_SORT : SORT_CLAUSES.getOrDefault(sortKey, DEFAULT_SORT)));
        return wrapper;
    }

    private List<AdminSpuSummary> assembleSummaries(List<ProductSpuEntity> spus) {
        if (spus.isEmpty()) {
            return List.of();
        }
        List<Long> spuIds = spus.stream().map(ProductSpuEntity::getId).toList();
        // 含停用 SKU：管理列表要回答「这个商品能不能卖」，停用中的规格也在它的存货里
        Map<Long, List<ProductSkuEntity>> skusBySpu = skuMapper
                .selectList(new LambdaQueryWrapper<ProductSkuEntity>()
                        .in(ProductSkuEntity::getSpuId, spuIds))
                .stream()
                .collect(Collectors.groupingBy(ProductSkuEntity::getSpuId));

        Map<Long, String> categoryNames = categoryService.categoryNames(
                spus.stream().map(ProductSpuEntity::getCategoryId).toList());
        Map<Long, String> brandNames = categoryService.brandNames(
                spus.stream().map(ProductSpuEntity::getBrandId).toList());

        return spus.stream()
                .map(spu -> {
                    List<ProductSkuEntity> skus = skusBySpu.getOrDefault(spu.getId(), List.of());
                    return AdminSpuSummary.builder()
                            .id(spu.getId())
                            .spuCode(spu.getSpuCode())
                            .name(spu.getName())
                            .subtitle(spu.getSubtitle())
                            .categoryId(spu.getCategoryId())
                            .categoryName(categoryNames.get(spu.getCategoryId()))
                            .brandId(spu.getBrandId())
                            .brandName(brandNames.get(spu.getBrandId()))
                            .mainImage(spu.getMainImage())
                            .minPrice(skus.isEmpty() ? null
                                    : skus.stream().mapToLong(ProductSkuEntity::getPrice).min().orElse(0L))
                            .maxPrice(skus.isEmpty() ? null
                                    : skus.stream().mapToLong(ProductSkuEntity::getPrice).max().orElse(0L))
                            .skuCount(skus.size())
                            .totalStock(skus.stream().mapToInt(ProductSkuEntity::getStock).sum())
                            .sales(spu.getSales())
                            .status(spu.getStatus())
                            .updatedAt(spu.getUpdatedAt())
                            .build();
                })
                .toList();
    }

    // ==================== 公共小工具 ====================

    private ProductSpuEntity requireSpu(Long spuId) {
        ProductSpuEntity spu = spuId == null ? null : spuMapper.selectById(spuId);
        if (spu == null) {
            throw new IllegalArgumentException("商品不存在");
        }
        return spu;
    }

    /**
     * 类目与品牌都必须真实存在。
     * <p>
     * 数据库对 {@code category_id} 没有外键约束（微服务拆分下单库单表，跨表外键会锁住
     * 后来拆库的路），所以「类目存在吗」只能在这一层问。不问的话，一个拼错的 id
     * 会让商品从所有按类目的查询里消失——而它在管理列表里看起来完全正常。
     * <p>
     * 借用 {@code categoryNames} / {@code brandNames} 而不是直接注入两个 Mapper：
     * 那两个方法已经在做「剔除 null 再查、查不到就返回空 Map」，语义正好是这里要判的。
     */
    private void validateCategory(Long categoryId) {
        if (categoryId == null || categoryService.categoryNames(List.of(categoryId)).isEmpty()) {
            throw new IllegalArgumentException("类目不存在");
        }
    }

    private void validateBrand(Long brandId) {
        if (brandId != null && categoryService.brandNames(List.of(brandId)).isEmpty()) {
            throw new IllegalArgumentException("品牌不存在");
        }
    }

    /** 新品编码：前缀 + 日期 + 随机段。手工填的编码由唯一约束兜底，见 {@link #insertSpu} */
    private String generateCode(String prefix) {
        String date = java.time.LocalDate.now()
                .format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
        String random = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
        return prefix + date + "-" + random;
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    /** 列表进出参是 List，库里是逗号分隔的一列。空的统一存 null 而不是空串，只有一个「没有」的表示 */
    private String joinNonBlank(Collection<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        String joined = values.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .collect(Collectors.joining(","));
        return joined.isEmpty() ? null : joined;
    }
}
