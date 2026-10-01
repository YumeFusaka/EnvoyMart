package yumefusaka.envoymart.productservice.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import yumefusaka.envoymart.productservice.entity.ProductSpuEntity;
import yumefusaka.envoymart.productservice.mapper.ProductSkuMapper;
import yumefusaka.envoymart.productservice.mapper.ProductSkuSpecMapper;
import yumefusaka.envoymart.productservice.mapper.ProductSpuMapper;
import yumefusaka.envoymart.productservice.model.ProductQuery;
import yumefusaka.envoymart.productservice.service.CategoryService;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 商品浏览侧的价格区间语义。
 * <p>
 * 这条链路上有两套取数实现——库内查询与 ES——用户看到的却是同一个筛选框。
 * 两者的语义一旦分叉，出错方式是**静默且反直觉**的：「搜『蛋白』能筛出 3 个商品，
 * 点进类目浏览筛同样的价钱只剩 1 个」，而两条路各自跑起来都没毛病。
 * 所以这里不测「SQL 长什么样」这个实现细节，测的是<b>两边对齐后的那一个判据</b>：
 * 价格是区间对区间，沾边即命中。
 */
class ProductServiceImplTest {

    private ProductSpuMapper spuMapper;
    private ProductServiceImpl service;

    /**
     * {@code LambdaQueryWrapper} 按 getter 反查列名，靠的是 MyBatis-Plus 的实体元数据缓存——
     * 那份缓存平时由 Spring 容器启动时建立，纯单元测试里没有。
     */
    @BeforeAll
    static void initMybatisPlusMetadata() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, ProductSpuEntity.class);
    }

    @BeforeEach
    void setUp() {
        spuMapper = mock(ProductSpuMapper.class);
        // 空页足够驱动 assemble() 走提前返回，不必再摆 SKU 与类目
        when(spuMapper.selectPage(any(), any())).thenAnswer(invocation -> invocation.getArgument(0));
        service = new ProductServiceImpl(
                spuMapper,
                mock(ProductSkuMapper.class),
                mock(ProductSkuSpecMapper.class),
                mock(ProductCacheService.class),
                mock(CategoryService.class),
                mock(ProductAssembler.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void 价格区间按沾边命中而不是存在某个SKU落在区间内() {
        ProductQuery query = new ProductQuery();
        query.setMinPrice(10000L);
        query.setMaxPrice(20000L);

        String sql = capturedSql(query);

        // 关键在 max/min 这一对聚合：SPU 的价格是一个区间，查询区间与它相交就该命中。
        // 写成「存在一个 SKU 的 price 落在 100~200 内」会漏掉「59 与 299 两个 SKU」的商品——
        // 而 ES 那条路会把它查出来
        assertThat(sql).contains("group by spu_id").contains("having");
        assertThat(sql).contains("max(price) >= 10000").contains("min(price) <= 20000");
        assertThat(sql).doesNotContain("price >= 10000").doesNotContain("price <= 20000");
    }

    @Test
    @SuppressWarnings("unchecked")
    void 只给下界时上界条件不出现() {
        ProductQuery query = new ProductQuery();
        query.setMinPrice(10000L);

        String sql = capturedSql(query);

        assertThat(sql).contains("max(price) >= 10000");
        // 上界缺省不能变成 min(price) <= null——那会让筛选静默失效或整条 SQL 报错
        assertThat(sql).doesNotContain("min(price)");
    }

    @Test
    @SuppressWarnings("unchecked")
    void 不给价格条件时不生成子查询() {
        // 多一个恒真的 IN 子查询，代价是每次浏览都要扫一遍 product_sku
        assertThat(capturedSql(new ProductQuery())).doesNotContain("product_sku");
    }

    @SuppressWarnings("unchecked")
    private String capturedSql(ProductQuery query) {
        service.list(query);
        ArgumentCaptor<LambdaQueryWrapper<ProductSpuEntity>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        org.mockito.Mockito.verify(spuMapper).selectPage(any(Page.class), captor.capture());
        return captor.getValue().getTargetSql();
    }
}
