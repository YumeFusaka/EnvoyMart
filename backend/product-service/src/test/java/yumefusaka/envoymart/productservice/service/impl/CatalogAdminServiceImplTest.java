package yumefusaka.envoymart.productservice.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import yumefusaka.envoymart.productservice.entity.BrandEntity;
import yumefusaka.envoymart.productservice.entity.CategoryEntity;
import yumefusaka.envoymart.productservice.entity.ProductAttributeEntity;
import yumefusaka.envoymart.productservice.entity.ProductSpuEntity;
import yumefusaka.envoymart.productservice.mapper.BrandMapper;
import yumefusaka.envoymart.productservice.mapper.CategoryMapper;
import yumefusaka.envoymart.productservice.mapper.ProductAttributeMapper;
import yumefusaka.envoymart.productservice.mapper.ProductSpuMapper;
import yumefusaka.envoymart.productservice.model.admin.CategoryUpsertRequest;
import yumefusaka.envoymart.productservice.service.CategoryService;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 类目维护的层级与路径推导。
 * <p>
 * {@code level} 与 {@code path} 是 {@code parentId} 的函数，而类目树的每一次查询都建在
 * 这两列上。它们一旦与实际父子关系不一致，出错方式是<b>静默</b>的：
 * 「按二级类目筛选时少了几个商品」不会报错，只会让人觉得数据本来就那样。
 * 所以「移动类目」这个唯一会改动它们的操作，必须有确定性的回归。
 */
class CatalogAdminServiceImplTest {

    private CategoryMapper categoryMapper;
    private ProductSpuMapper spuMapper;
    private CategoryService categoryService;
    private CatalogAdminServiceImpl service;

    @BeforeAll
    static void initMybatisPlusMetadata() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        for (Class<?> entity : List.of(CategoryEntity.class, BrandEntity.class,
                ProductSpuEntity.class, ProductAttributeEntity.class)) {
            TableInfoHelper.initTableInfo(assistant, entity);
        }
    }

    @BeforeEach
    void setUp() {
        categoryMapper = mock(CategoryMapper.class);
        BrandMapper brandMapper = mock(BrandMapper.class);
        spuMapper = mock(ProductSpuMapper.class);
        ProductAttributeMapper attributeMapper = mock(ProductAttributeMapper.class);
        categoryService = mock(CategoryService.class);
        service = new CatalogAdminServiceImpl(categoryMapper, brandMapper, spuMapper, attributeMapper,
                new CategoryTreeAssembler(), categoryService, mock(ProductDerivedRefresh.class));
    }

    // ==================== 移动类目 ====================

    @Test
    void 移动到新父节点时整棵子树一起重算路径与层级() {
        CategoryEntity child = category(5L, 1L, "维生素D", 2, "1/5");
        CategoryEntity grandChild = category(7L, 5L, "软胶囊", 3, "1/5/7");
        when(categoryMapper.selectById(5L)).thenReturn(child);
        when(categoryMapper.selectById(2L)).thenReturn(category(2L, 0L, "营养保健", 1, "2"));
        when(categoryMapper.selectCount(any())).thenReturn(0L);
        when(categoryMapper.selectList(any())).thenReturn(List.of(grandChild));

        service.updateCategory(5L, categoryRequest(2L, "维生素D"));

        assertThat(child.getPath()).as("新路径挂在新的父路径下").isEqualTo("2/5");
        assertThat(child.getLevel()).as("父是 1 级，自己就是 2 级").isEqualTo(2);
        assertThat(child.getParentId()).isEqualTo(2L);
        // 只改自己是不够的：孙节点的路径前缀还指着旧位置，
        // 此后「查维生素D 的整棵子树」会返回空——而接口一路成功
        assertThat(grandChild.getPath()).isEqualTo("2/5/7");
        assertThat(grandChild.getLevel()).as("相对深度不变，随父一起平移").isEqualTo(3);

        ArgumentCaptor<CategoryEntity> captor = ArgumentCaptor.forClass(CategoryEntity.class);
        verify(categoryMapper, times(2)).updateById(captor.capture());
        assertThat(captor.getAllValues()).as("子树先写、自身后写").containsExactly(grandChild, child);
    }

    @Test
    void 移动到根节点时路径只剩自己的id() {
        CategoryEntity child = category(5L, 1L, "维生素D", 2, "1/5");
        CategoryEntity grandChild = category(7L, 5L, "软胶囊", 3, "1/5/7");
        when(categoryMapper.selectById(5L)).thenReturn(child);
        when(categoryMapper.selectCount(any())).thenReturn(0L);
        when(categoryMapper.selectList(any())).thenReturn(List.of(grandChild));

        service.updateCategory(5L, categoryRequest(0L, "维生素D"));

        assertThat(child.getPath()).as("顶级类目的路径就是自己的 id").isEqualTo("5");
        assertThat(child.getLevel()).isEqualTo(1);
        assertThat(grandChild.getPath()).isEqualTo("5/7");
        assertThat(grandChild.getLevel()).isEqualTo(2);
    }

    @Test
    void 不能把类目移到自己的后代下() {
        CategoryEntity parent = category(5L, 1L, "维生素D", 2, "1/5");
        CategoryEntity descendant = category(7L, 5L, "软胶囊", 3, "1/5/7");
        when(categoryMapper.selectById(5L)).thenReturn(parent);
        when(categoryMapper.selectById(7L)).thenReturn(descendant);
        when(categoryMapper.selectCount(any())).thenReturn(0L);

        // 成环之后树永远建不出来（这些节点互为父子，谁都不在根上），
        // 表现是整个类目树从导航里消失——而库里的数据看起来完全正常
        assertThatThrownBy(() -> service.updateCategory(5L, categoryRequest(7L, "维生素D")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("自己的子类目");
        verify(categoryMapper, never()).updateById(any(CategoryEntity.class));
    }

    @Test
    void 移动后子树超过三级被拒() {
        CategoryEntity parent = category(5L, 1L, "维生素D", 2, "1/5");
        CategoryEntity grandChild = category(7L, 5L, "软胶囊", 3, "1/5/7");
        when(categoryMapper.selectById(5L)).thenReturn(parent);
        when(categoryMapper.selectById(3L)).thenReturn(category(3L, 0L, "运动营养", 2, "1/3"));
        when(categoryMapper.selectCount(any())).thenReturn(0L);
        when(categoryMapper.selectList(any())).thenReturn(List.of(grandChild));

        // 父是 2 级 → 自己变 3 级 → 原有的孙节点变 4 级。深度要按整棵子树判，不能只看自己
        assertThatThrownBy(() -> service.updateCategory(5L, categoryRequest(3L, "维生素D")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("超过 3 级");
        verify(categoryMapper, never()).updateById(any(CategoryEntity.class));
    }

    @Test
    void 同一级下不允许重名() {
        when(categoryMapper.selectById(5L)).thenReturn(category(5L, 1L, "维生素D", 2, "1/5"));
        when(categoryMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> service.updateCategory(5L, categoryRequest(1L, "维生素D")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("已经有叫");
    }

    // ==================== 删除守卫 ====================

    @Test
    void 类目下还有商品时拒绝删除并保留下架之外的出路() {
        when(categoryMapper.selectById(5L)).thenReturn(category(5L, 1L, "维生素D", 2, "1/5"));
        when(categoryMapper.selectCount(any())).thenReturn(0L);
        when(spuMapper.selectCount(any())).thenReturn(3L);

        assertThatThrownBy(() -> service.deleteCategory(5L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("3 个商品");

        // 不能删一半：商品挂在一个不存在的类目上时，管理列表里看起来完全正常，
        // 只有前台按类目点进去才发现是空的
        verify(categoryMapper, never()).deleteById(any(Long.class));
    }

    @Test
    void 类目下有子类目时拒绝删除() {
        when(categoryMapper.selectById(5L)).thenReturn(category(5L, 1L, "维生素D", 2, "1/5"));
        when(categoryMapper.selectCount(any())).thenReturn(2L);

        assertThatThrownBy(() -> service.deleteCategory(5L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("2 个子类目");
        verify(categoryMapper, never()).deleteById(any(Long.class));
    }

    // ==================== 夹具 ====================

    private CategoryEntity category(Long id, Long parentId, String name, int level, String path) {
        CategoryEntity entity = new CategoryEntity();
        entity.setId(id);
        entity.setParentId(parentId);
        entity.setName(name);
        entity.setLevel(level);
        entity.setPath(path);
        entity.setSort(0);
        entity.setStatus(1);
        return entity;
    }

    private CategoryUpsertRequest categoryRequest(Long parentId, String name) {
        CategoryUpsertRequest request = new CategoryUpsertRequest();
        request.setParentId(parentId);
        request.setName(name);
        request.setSort(0);
        request.setStatus(1);
        return request;
    }
}
