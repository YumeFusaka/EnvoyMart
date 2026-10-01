package yumefusaka.envoymart.productservice.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
public class CategoryServiceImpl implements CategoryService {

    private static final int STATUS_ENABLED = 1;

    private final CategoryMapper categoryMapper;
    private final BrandMapper brandMapper;
    private final CategoryTreeAssembler treeAssembler;

    public CategoryServiceImpl(CategoryMapper categoryMapper,
                               BrandMapper brandMapper,
                               CategoryTreeAssembler treeAssembler) {
        this.categoryMapper = categoryMapper;
        this.brandMapper = brandMapper;
        this.treeAssembler = treeAssembler;
    }

    @Override
    public List<CategoryNode> tree() {
        return treeAssembler.build(categoryMapper.selectList(new LambdaQueryWrapper<CategoryEntity>()
                .eq(CategoryEntity::getStatus, STATUS_ENABLED)
                .orderByAsc(CategoryEntity::getSort)
                .orderByAsc(CategoryEntity::getId)));
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
            return EMPTY_LOOKUP;
        }
        return categoryMapper.selectByIds(cleaned).stream()
                .collect(Collectors.toMap(CategoryEntity::getId, CategoryEntity::getName));
    }

    @Override
    public Map<Long, List<Long>> categoryPaths(Collection<Long> ids) {
        Set<Long> cleaned = cleanIds(ids);
        if (cleaned.isEmpty()) {
            return EMPTY_PATH_LOOKUP;
        }
        return categoryMapper.selectByIds(cleaned).stream()
                .collect(Collectors.toMap(CategoryEntity::getId, CategoryServiceImpl::ancestorChain));
    }

    /**
     * 把物化路径拆成 id 链。{@code path} 形如 {@code 1/5}，<b>含自身</b>，由近及远。
     * <p>
     * 缺失或拆不出数时回退成「只有自己」而不是空链：空链会让限类目的券把这行商品
     * 判成「不在适用范围」，而真实原因只是某一行 path 没维护好 ——
     * 一个配置问题伪装成业务结论，排查时看到的是「券用不了」而不是「数据缺了」。
     */
    private static List<Long> ancestorChain(CategoryEntity category) {
        String path = category.getPath();
        if (path != null && !path.isBlank()) {
            List<Long> ids = new ArrayList<>();
            for (String part : path.split("/")) {
                try {
                    ids.add(Long.valueOf(part.trim()));
                } catch (NumberFormatException e) {
                    log.warn("[Category] 类目路径段非法，已跳过 categoryId={} path={} 段={}",
                            category.getId(), path, part);
                }
            }
            if (!ids.isEmpty()) {
                return List.copyOf(ids);
            }
        }
        return List.of(category.getId());
    }

    @Override
    public Map<Long, String> brandNames(Collection<Long> ids) {
        Set<Long> cleaned = cleanIds(ids);
        if (cleaned.isEmpty()) {
            return EMPTY_LOOKUP;
        }
        return brandMapper.selectByIds(cleaned).stream()
                .collect(Collectors.toMap(BrandEntity::getId, BrandEntity::getName));
    }

    /**
     * 「什么都没查到」时返回的空表。
     * <p>
     * <b>不能用 {@code Map.of()}</b>：调用方拿到这张表后会直接
     * {@code get(spu.getBrandId())}，而 {@link #cleanIds} 的注释已经写明「品牌可以为空、
     * null 必然出现」。不可变空表（{@code Map.of()}、{@code Collections.emptyMap()}）
     * 的 {@code get(null)} 抛 {@link NullPointerException} 而不是返回 null ——
     * 于是「这一页商品恰好都没有品牌」会让整个管理端商品列表报 500，
     * 而错误信息里只有一句 NullPointerException，看不出跟品牌有什么关系。
     * <p>
     * 这里用的是可接受 null 键的 {@link HashMap}（再包一层只读视图），
     * 语义与「查了但没有」一致：<b>返回 null，不抛异常</b>。
     */
    private static final Map<Long, String> EMPTY_LOOKUP = Collections.unmodifiableMap(new HashMap<>());

    /** 同 {@link #EMPTY_LOOKUP} 的理由，只是值类型不同 —— 同样要能 {@code get(null)} 返回 null */
    private static final Map<Long, List<Long>> EMPTY_PATH_LOOKUP =
            Collections.unmodifiableMap(new HashMap<>());

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
