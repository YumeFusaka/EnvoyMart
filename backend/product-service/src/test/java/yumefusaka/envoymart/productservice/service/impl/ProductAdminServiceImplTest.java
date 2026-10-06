package yumefusaka.envoymart.productservice.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import yumefusaka.envoymart.productservice.model.admin.SkuRequest;
import yumefusaka.envoymart.productservice.model.admin.SpuUpsertRequest;
import yumefusaka.envoymart.productservice.model.admin.StockAdjustRequest;
import yumefusaka.envoymart.productservice.service.CategoryService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 商品管理侧的写路径约束。
 * <p>
 * 这里钉的是几件「做错了不会报错、只会到某天才暴露」的事：
 * <ul>
 *   <li>删掉一个被订单引用过的 SKU —— 历史订单从此查不到商品；</li>
 *   <li>库存变了却不留流水 —— 事后没人能回答「库存为什么少了」；</li>
 *   <li>库存没变却留一条 0 增量的流水 —— 真正的变动会淹没在噪音里。</li>
 * </ul>
 * 都是守卫类的判断，用 mock 精确摆出前置状态比起服务更快，也更容易把边界摆到位。
 */
class ProductAdminServiceImplTest {

    private static final String OPERATOR = "u1003";

    private ProductSpuMapper spuMapper;
    private ProductSkuMapper skuMapper;
    private ProductSpecMapper specMapper;
    private ProductSpecValueMapper specValueMapper;
    private ProductSkuSpecMapper skuSpecMapper;
    private SpuAttributeValueMapper spuAttributeValueMapper;
    private StockLogMapper stockLogMapper;
    private CategoryService categoryService;
    private ProductDerivedRefresh derivedRefresh;
    private ProductAdminServiceImpl service;

    /**
     * {@code LambdaQueryWrapper} 按 getter 反查列名，靠的是 MyBatis-Plus 的实体元数据缓存——
     * 那份缓存平时由 Spring 容器启动时建立，纯单元测试里没有。不建它会抛
     * "can not find lambda cache for this entity"，且报的是列名解析失败，看不出真正原因。
     */
    @BeforeAll
    static void initMybatisPlusMetadata() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        for (Class<?> entity : List.of(ProductSpuEntity.class, ProductSkuEntity.class,
                ProductSpecEntity.class, ProductSpecValueEntity.class, ProductSkuSpecEntity.class,
                ProductAttributeEntity.class, SpuAttributeValueEntity.class, StockLogEntity.class)) {
            TableInfoHelper.initTableInfo(assistant, entity);
        }
    }

    @BeforeEach
    void setUp() {
        spuMapper = mock(ProductSpuMapper.class);
        skuMapper = mock(ProductSkuMapper.class);
        specMapper = mock(ProductSpecMapper.class);
        specValueMapper = mock(ProductSpecValueMapper.class);
        skuSpecMapper = mock(ProductSkuSpecMapper.class);
        ProductAttributeMapper attributeMapper = mock(ProductAttributeMapper.class);
        spuAttributeValueMapper = mock(SpuAttributeValueMapper.class);
        stockLogMapper = mock(StockLogMapper.class);
        categoryService = mock(CategoryService.class);
        derivedRefresh = mock(ProductDerivedRefresh.class);
        service = new ProductAdminServiceImpl(spuMapper, skuMapper, specMapper, specValueMapper,
                skuSpecMapper, attributeMapper, spuAttributeValueMapper, stockLogMapper,
                mock(ProductAssembler.class), categoryService, derivedRefresh, mock(KnowledgeLifecycleSync.class));
    }

    // ==================== 库存调整 ====================

    @Test
    void 库存增加时写一条入库流水并记下操作人() {
        when(skuMapper.selectById(1L)).thenReturn(sku(1L, 10L, 5));
        when(skuMapper.updateStockIfUnchanged(1L, 5, 8)).thenReturn(1);

        service.adjustStock(1L, stockRequest(8, "供应商到货"), OPERATOR);

        StockLogEntity log = capturedLog();
        assertThat(log.getChangeType()).isEqualTo("INBOUND");
        assertThat(log.getQuantity()).as("增量是差值的绝对值").isEqualTo(3);
        assertThat(log.getBeforeStock()).isEqualTo(5);
        assertThat(log.getAfterStock()).isEqualTo(8);
        assertThat(log.getBizType()).as("管理端的手工改动与订单扣减要能区分开").isEqualTo("MANUAL");
        assertThat(log.getBizId()).as("没有操作人的流水只能证明库存变了，证明不了谁改的")
                .isEqualTo(OPERATOR);
        assertThat(log.getRemark()).isEqualTo("供应商到货");
        // 必须是带旧值条件的原子更新，不能是 updateById：整行覆盖会把期间发生的
        // 下单扣减抹掉，而且从界面上完全看不出来
        verify(skuMapper).updateStockIfUnchanged(1L, 5, 8);
        verify(skuMapper, never()).updateById(any(ProductSkuEntity.class));
    }

    @Test
    void 库存减少时写一条扣减流水() {
        when(skuMapper.selectById(1L)).thenReturn(sku(1L, 10L, 5));
        when(skuMapper.updateStockIfUnchanged(1L, 5, 2)).thenReturn(1);

        service.adjustStock(1L, stockRequest(2, "盘点修正"), OPERATOR);

        StockLogEntity log = capturedLog();
        assertThat(log.getChangeType()).isEqualTo("DEDUCT");
        assertThat(log.getQuantity()).isEqualTo(3);
        assertThat(log.getBeforeStock()).isEqualTo(5);
        assertThat(log.getAfterStock()).isEqualTo(2);
    }

    /**
     * 打开库存页到点保存之间，买家下单扣了库存 —— 条件更新会 0 行命中，
     * 这时必须报冲突让人刷新重来，而不是当成写成功把那次扣减抹掉。
     */
    @Test
    void 库存已被并发修改时应报冲突而不是覆盖() {
        when(skuMapper.selectById(1L)).thenReturn(sku(1L, 10L, 5));
        when(skuMapper.updateStockIfUnchanged(1L, 5, 8)).thenReturn(0);

        assertThatThrownBy(() -> service.adjustStock(1L, stockRequest(8, "到货"), OPERATOR))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("已被其他操作变更");

        // 冲突时一条流水都不该写：库存没变，凭什么记一笔"5→8"
        verify(stockLogMapper, never()).insert(any(StockLogEntity.class));
    }

    @Test
    void 库存没变时不写流水也不更新() {
        when(skuMapper.selectById(1L)).thenReturn(sku(1L, 10L, 5));

        service.adjustStock(1L, stockRequest(5, null), OPERATOR);

        // 一条 0 增量的记录不会让任何判断出错，但它会让真正的变动淹没在噪音里——
        // 流水是「库存为什么变了」的答案，没有问题要回答时就不该写
        verify(stockLogMapper, never()).insert(any(StockLogEntity.class));
        verify(skuMapper, never()).updateById(any(ProductSkuEntity.class));
    }

    @Test
    void 调整不存在的规格被拒() {
        when(skuMapper.selectById(99L)).thenReturn(null);

        assertThatThrownBy(() -> service.adjustStock(99L, stockRequest(3, null), OPERATOR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("规格不存在");
    }

    // ==================== 删除守卫 ====================

    @Test
    void 被订单引用过的商品不能删除() {
        when(spuMapper.selectById(10L)).thenReturn(spu(10L));
        when(skuMapper.selectList(any())).thenReturn(List.of(sku(1L, 10L, 5), sku(2L, 10L, 5)));
        when(stockLogMapper.countOrderedSkus(anyList())).thenReturn(2);

        assertThatThrownBy(() -> service.delete(10L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不能删除")
                .hasMessageContaining("下架");

        // 关键不是「抛了异常」，而是**一行都没删**：半途删掉 SKU 再抛，
        // 商品就成了一个没有规格的空壳，而接口返回的是失败
        verify(skuMapper, never()).deleteByIds(anyList());
        verify(spuMapper, never()).deleteById(any(Long.class));
        verify(skuSpecMapper, never()).delete(any());
    }

    @Test
    void 没卖过的商品连同规格与参数一起清掉() {
        when(spuMapper.selectById(10L)).thenReturn(spu(10L));
        when(skuMapper.selectList(any())).thenReturn(List.of(sku(1L, 10L, 5)));
        when(stockLogMapper.countOrderedSkus(anyList())).thenReturn(0);
        when(specMapper.selectList(any())).thenReturn(List.of(spec(50L, 10L)));
        when(specValueMapper.selectList(any())).thenReturn(List.of(specValue(500L, 50L)));

        service.delete(10L);

        // 先删关联再删主表：反过来的话关联会短暂指向不存在的行，
        // 而 MyBatis-Plus 的 deleteByIds 不报错、只留一堆孤儿
        verify(skuSpecMapper).delete(any());
        verify(skuMapper).deleteByIds(List.of(1L));
        verify(spuAttributeValueMapper).delete(any());
        verify(specValueMapper).delete(any());
        verify(specMapper).delete(any());
        verify(spuMapper).deleteById(10L);
        // 缓存与索引要一起清：只清缓存的话，搜索结果里会留下一个点不进去的商品
        verify(derivedRefresh).afterCommit(10L);
    }

    @Test
    void 编辑时删掉被订单引用过的规格被拒() {
        when(spuMapper.selectById(10L)).thenReturn(spu(10L));
        when(categoryService.categoryNames(anyList())).thenReturn(Map.of(1L, "维生素D"));
        when(skuMapper.selectList(any())).thenReturn(List.of(sku(1L, 10L, 5), sku(2L, 10L, 5)));
        when(stockLogMapper.countOrderedSkus(anyList())).thenReturn(1);
        when(specMapper.selectList(any())).thenReturn(List.of());

        SpuUpsertRequest request = new SpuUpsertRequest();
        request.setName("维生素 D3 软胶囊");
        request.setCategoryId(1L);
        request.setDetailHtml("<p>成分</p>");
        // 只提交 1 号，2 号在请求里消失了 —— 管理台上就是把那一行规格删掉
        request.setSkus(List.of(submittedSku(1L)));
        request.setSpecs(List.of());

        assertThatThrownBy(() -> service.update(10L, request, OPERATOR))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("订单引用");

        // 关键同样是「一行都没删」：SKU 的关联、流水、订单引用都还在
        verify(skuMapper, never()).deleteByIds(anyList());
        verify(skuSpecMapper, never()).delete(any());
    }

    // ==================== 上下架 ====================

    @Test
    void 上下架状态重复设置时幂等不写库() {
        ProductSpuEntity spu = spu(10L);
        spu.setStatus(1);
        when(spuMapper.selectById(10L)).thenReturn(spu);

        service.changeStatus(10L, 1);

        // 重复点同一个按钮不该报错，也确实没有任何需要重建的东西
        verify(spuMapper, never()).updateById(any(ProductSpuEntity.class));
    }

    @Test
    void 上下架只接受0和1() {
        when(spuMapper.selectById(10L)).thenReturn(spu(10L));

        assertThatThrownBy(() -> service.changeStatus(10L, 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("只能是 0");
    }

    @Test
    void 商品不存在时操作被拒() {
        when(spuMapper.selectById(404L)).thenReturn(null);

        assertThatThrownBy(() -> service.changeStatus(404L, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("商品不存在");
    }

    // ==================== 夹具 ====================

    private StockLogEntity capturedLog() {
        ArgumentCaptor<StockLogEntity> captor = ArgumentCaptor.forClass(StockLogEntity.class);
        verify(stockLogMapper).insert(captor.capture());
        return captor.getValue();
    }

    private SkuRequest submittedSku(Long id) {
        SkuRequest request = new SkuRequest();
        request.setId(id);
        request.setPrice(9900L);
        request.setStock(5);
        request.setSpecValues(new LinkedHashMap<>());
        return request;
    }

    private StockAdjustRequest stockRequest(int stock, String remark) {
        StockAdjustRequest request = new StockAdjustRequest();
        request.setStock(stock);
        request.setRemark(remark);
        return request;
    }

    private ProductSpuEntity spu(Long id) {
        ProductSpuEntity spu = new ProductSpuEntity();
        spu.setId(id);
        spu.setSpuCode("SPU" + id);
        spu.setName("维生素 D3 软胶囊");
        spu.setStatus(1);
        return spu;
    }

    private ProductSkuEntity sku(Long id, Long spuId, int stock) {
        ProductSkuEntity sku = new ProductSkuEntity();
        sku.setId(id);
        sku.setSpuId(spuId);
        sku.setSkuCode("SKU" + id);
        sku.setPrice(9900L);
        sku.setStock(stock);
        sku.setStatus(1);
        return sku;
    }

    private ProductSpecEntity spec(Long id, Long spuId) {
        ProductSpecEntity spec = new ProductSpecEntity();
        spec.setId(id);
        spec.setSpuId(spuId);
        spec.setName("净含量");
        spec.setSort(0);
        return spec;
    }

    private ProductSpecValueEntity specValue(Long id, Long specId) {
        ProductSpecValueEntity value = new ProductSpecValueEntity();
        value.setId(id);
        value.setSpecId(specId);
        value.setSpecValue("90粒");
        value.setSort(0);
        return value;
    }
}
