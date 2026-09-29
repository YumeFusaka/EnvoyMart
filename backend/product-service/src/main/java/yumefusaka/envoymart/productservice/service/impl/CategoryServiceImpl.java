package yumefusaka.envoymart.productservice.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import yumefusaka.envoymart.productservice.entity.BrandEntity;
import yumefusaka.envoymart.productservice.entity.CategoryEntity;
import yumefusaka.envoymart.productservice.mapper.BrandMapper;
import yumefusaka.envoymart.productservice.mapper.CategoryMapper;
import yumefusaka.envoymart.productservice.model.BrandView;
import yumefusaka.envoymart.productservice.model.CategoryNode;
import yumefusaka.envoymart.productservice.service.CategoryService;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class CategoryServiceImpl implements CategoryService {

    private static final int STATUS_ENABLED = 1;

    private final CategoryMapper categoryMapper;
    private final BrandMapper brandMapper;

    public CategoryServiceImpl(CategoryMapper categoryMapper, BrandMapper brandMapper) {
        this.categoryMapper = categoryMapper;
        this.brandMapper = brandMapper;
    }

    @Override
    public List<CategoryNode> tree() {
        List<CategoryEntity> all = categoryMapper.selectList(new LambdaQueryWrapper<CategoryEntity>()
                .eq(CategoryEntity::getStatus, STATUS_ENABLED)
                .orderByAsc(CategoryEntity::getSort)
                .orderByAsc(CategoryEntity::getId));

        // 一次查完在内存里挂子树：类目总量是几十条量级，
        // 逐层查库的往返开销远大于这一步
        Map<Long, CategoryNode> nodes = new LinkedHashMap<>();
        for (CategoryEntity entity : all) {
            CategoryNode node = new CategoryNode();
            node.setId(entity.getId());
            node.setName(entity.getName());
            node.setLevel(entity.getLevel());
            node.setSort(entity.getSort());
            nodes.put(entity.getId(), node);
        }

        List<CategoryNode> roots = new ArrayList<>();
        for (CategoryEntity entity : all) {
            CategoryNode node = nodes.get(entity.getId());
            CategoryNode parent = entity.getParentId() == null ? null : nodes.get(entity.getParentId());
            if (parent == null) {
                // 顶级类目（parentId = 0），或父节点被停用而成了孤儿。后者也让它留在根上：
                // 藏起来的话整棵子树会从界面上消失，而数据其实还在，排查时很难想到是这里
                roots.add(node);
            } else {
                parent.getChildren().add(node);
            }
        }
        return roots;
    }

    @Override
    public List<BrandView> brands() {
        return brandMapper.selectList(new LambdaQueryWrapper<BrandEntity>()
                        .eq(BrandEntity::getStatus, STATUS_ENABLED)
                        .orderByAsc(BrandEntity::getId))
                .stream()
                .map(brand -> BrandView.builder()
                        .id(brand.getId())
                        .name(brand.getName())
                        .logo(brand.getLogo())
                        .build())
                .toList();
    }

    @Override
    public List<Long> selfAndDescendantIds(Long categoryId) {
        if (categoryId == null) {
            return List.of();
        }
        CategoryEntity self = categoryMapper.selectById(categoryId);
        if (self == null) {
            return List.of();
        }

        List<Long> ids = new ArrayList<>();
        ids.add(self.getId());

        String path = self.getPath();
        if (path != null && !path.isBlank()) {
            // 前缀要带上分隔符：只写 `path like '1/5%'` 会把 `1/50` 也匹进来 ——
            // 两个八竿子打不着的类目被并成一组，而查询不会报任何错
            categoryMapper.selectList(new LambdaQueryWrapper<CategoryEntity>()
                            .likeRight(CategoryEntity::getPath, path + "/"))
                    .stream()
                    .map(CategoryEntity::getId)
                    .forEach(ids::add);
        }
        return ids;
    }

    @Override
    public Map<Long, String> categoryNames(Collection<Long> ids) {
        Set<Long> cleaned = cleanIds(ids);
        if (cleaned.isEmpty()) {
            return Map.of();
        }
        return categoryMapper.selectByIds(cleaned).stream()
                .collect(Collectors.toMap(CategoryEntity::getId, CategoryEntity::getName));
    }

    @Override
    public Map<Long, String> brandNames(Collection<Long> ids) {
        Set<Long> cleaned = cleanIds(ids);
        if (cleaned.isEmpty()) {
            return Map.of();
        }
        return brandMapper.selectByIds(cleaned).stream()
                .collect(Collectors.toMap(BrandEntity::getId, BrandEntity::getName));
    }

    /**
     * 剔除 null 再查。
     * <p>
     * 调用方传进来的往往是 SPU 列表的 categoryId / brandId，而**品牌可以为空**，
     * null 必然出现。`id in (null)` 一行也匹配不到，且不报错 —— 表现为「有些商品
     * 的类目名是空的」，看上去像数据问题。
     */
    private Set<Long> cleanIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Set.of();
        }
        return ids.stream().filter(Objects::nonNull).collect(Collectors.toSet());
    }
}
