package yumefusaka.envoymart.productservice.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import yumefusaka.envoymart.productservice.entity.BrandEntity;
import yumefusaka.envoymart.productservice.entity.CategoryEntity;
import yumefusaka.envoymart.productservice.entity.ProductAttributeEntity;
import yumefusaka.envoymart.productservice.entity.ProductSpuEntity;
import yumefusaka.envoymart.productservice.mapper.BrandMapper;
import yumefusaka.envoymart.productservice.mapper.CategoryMapper;
import yumefusaka.envoymart.productservice.mapper.ProductAttributeMapper;
import yumefusaka.envoymart.productservice.mapper.ProductSpuMapper;
import yumefusaka.envoymart.productservice.model.CategoryNode;
import yumefusaka.envoymart.productservice.model.admin.AdminBrand;
import yumefusaka.envoymart.productservice.model.admin.BrandUpsertRequest;
import yumefusaka.envoymart.productservice.model.admin.CategoryUpsertRequest;
import yumefusaka.envoymart.productservice.service.CatalogAdminService;
import yumefusaka.envoymart.productservice.service.CategoryService;

import java.util.List;
import java.util.Objects;

/**
 * 类目与品牌的维护。
 *
 * <h3>为什么 {@code level} 与 {@code path} 一律推导、绝不接受传入</h3>
 * 它们不是可以自由填写的字段，而是 {@code parentId} 的函数：{@code level = 父.level + 1}，
 * {@code path = 父.path + "/" + id}。类目树的每一次查询都建立在这两列上，
 * 而它们一旦与实际父子关系不一致，出错的方式是<b>静默</b>的——「按二级类目筛选时
 * 少了几个商品」这种事不会报错，只会让人觉得数据本来就那样。所以这里只接受 parentId，
 * 其余全部由代码算出来；移动类目时整棵子树一起重算。
 */
@Slf4j
@Service
public class CatalogAdminServiceImpl implements CatalogAdminService {

    private static final int STATUS_ON = 1;
    private static final int STATUS_OFF = 0;
    /** 一 / 二 / 三级。再深下去导航就没法用了，界面上也没有对应的入口 */
    private static final int MAX_LEVEL = 3;
    /** 顶级类目的 parentId 约定值。库里是 `not null default 0`，不是 null */
    private static final long ROOT_PARENT_ID = 0L;

    private final CategoryMapper categoryMapper;
    private final BrandMapper brandMapper;
    private final ProductSpuMapper spuMapper;
    private final ProductAttributeMapper attributeMapper;
    private final CategoryTreeAssembler treeAssembler;
    private final CategoryService categoryService;
    private final ProductDerivedRefresh derivedRefresh;

    public CatalogAdminServiceImpl(CategoryMapper categoryMapper,
                                   BrandMapper brandMapper,
                                   ProductSpuMapper spuMapper,
                                   ProductAttributeMapper attributeMapper,
                                   CategoryTreeAssembler treeAssembler,
                                   CategoryService categoryService,
                                   ProductDerivedRefresh derivedRefresh) {
        this.categoryMapper = categoryMapper;
        this.brandMapper = brandMapper;
        this.spuMapper = spuMapper;
        this.attributeMapper = attributeMapper;
        this.treeAssembler = treeAssembler;
        this.categoryService = categoryService;
        this.derivedRefresh = derivedRefresh;
    }

    // ==================== 类目 ====================

    @Override
    public List<CategoryNode> tree() {
        return treeAssembler.build(categoryMapper.selectList(new LambdaQueryWrapper<CategoryEntity>()
                .orderByAsc(CategoryEntity::getSort)
                .orderByAsc(CategoryEntity::getId)));
    }

    @Override
    @Transactional
    public Long createCategory(CategoryUpsertRequest request) {
        long parentId = normalizeParentId(request.getParentId());
        CategoryEntity parent = parentId == ROOT_PARENT_ID ? null : requireCategory(parentId);
        if (parent != null && parent.getLevel() >= MAX_LEVEL) {
            throw new IllegalArgumentException("类目最多 " + MAX_LEVEL + " 级，「" + parent.getName()
                    + "」已经是第 " + parent.getLevel() + " 级，下面不能再挂子类目");
        }
        int level = parent == null ? 1 : parent.getLevel() + 1;
        requireNameFreeUnderParent(parentId, request.getName(), null);

        CategoryEntity entity = new CategoryEntity();
        entity.setParentId(parentId);
        entity.setName(request.getName().trim());
        entity.setLevel(level);
        entity.setSort(request.getSort() == null ? 0 : request.getSort());
        entity.setStatus(normalizeStatus(request.getStatus()));
        // path 里要含自己的 id，而 id 是自增的、插入前不知道，所以先插入再回填。
        // 代价是一次额外的 UPDATE，换来的是 path 与主键天然一致——
        // 换成「先算一个预计 id 再插」就依赖了自增的当前值，并发下会算错
        entity.setPath("");
        categoryMapper.insert(entity);
        entity.setPath(parent == null ? String.valueOf(entity.getId())
                : parent.getPath() + "/" + entity.getId());
        categoryMapper.updateById(entity);

        log.info("[管理] 新建类目 id={} name={} parentId={} level={}",
                entity.getId(), entity.getName(), parentId, level);
        return entity.getId();
    }

    @Override
    @Transactional
    public void updateCategory(Long categoryId, CategoryUpsertRequest request) {
        CategoryEntity entity = requireCategory(categoryId);
        long parentId = normalizeParentId(request.getParentId());
        if (parentId == categoryId) {
            throw new IllegalArgumentException("类目不能挂在自己下面");
        }
        requireNameFreeUnderParent(parentId, request.getName(), categoryId);

        // 改名与否必须在 setName 之前算：setName 之后再和 request 比，两边一定相等，
        // renamed 恒为 false，下面那段刷新就成了死代码——表现是「类目改名了，
        // 前台商品详情与搜索里还是旧名字」，而且日志还会打印 renamed=false 把排查带偏
        String name = request.getName().trim();
        boolean renamed = !Objects.equals(entity.getName(), name);
        boolean moved = !Objects.equals(entity.getParentId(), parentId);

        entity.setName(name);
        entity.setSort(request.getSort() == null ? 0 : request.getSort());
        entity.setStatus(normalizeStatus(request.getStatus()));
        if (moved) {
            applyMove(entity, parentId);
        }
        categoryMapper.updateById(entity);

        if (renamed) {
            // 类目名冗余在商品详情与搜索索引里，改完不刷的话前台还是旧名字
            refreshProductsOfCategory(categoryId);
        }
        log.info("[管理] 编辑类目 id={} name={} parentId={} moved={} renamed={}",
                categoryId, entity.getName(), parentId, moved, renamed);
    }

    @Override
    @Transactional
    public void deleteCategory(Long categoryId) {
        CategoryEntity entity = requireCategory(categoryId);
        // 三类引用都要挡住。它们各自对应一种「删了之后看不出问题」的坏状态
        long children = categoryMapper.selectCount(new LambdaQueryWrapper<CategoryEntity>()
                .eq(CategoryEntity::getParentId, categoryId));
        if (children > 0) {
            throw new IllegalStateException("该类目下还有 " + children + " 个子类目，"
                    + "请先删除或移走它们。");
        }
        long products = spuMapper.selectCount(new LambdaQueryWrapper<ProductSpuEntity>()
                .eq(ProductSpuEntity::getCategoryId, categoryId));
        if (products > 0) {
            throw new IllegalStateException("该类目下还有 " + products + " 个商品，"
                    + "请先把它们移到别的类目——直接删除会让这些商品挂在一个不存在的类目上。");
        }
        long attributes = attributeMapper.selectCount(new LambdaQueryWrapper<ProductAttributeEntity>()
                .eq(ProductAttributeEntity::getCategoryId, categoryId));
        if (attributes > 0) {
            throw new IllegalStateException("该类目下还有 " + attributes + " 个参数模板，"
                    + "删掉类目会让它们成为孤儿，商品的参数也就没有定义可依。");
        }

        // 走到这里说明前面几项都是 0，没有商品因为这个类目而需要刷新
        categoryMapper.deleteById(categoryId);
        log.info("[管理] 删除类目 id={} name={}", categoryId, entity.getName());
    }

    /**
     * 移动类目：重算自己与整棵子树的 {@code level}、{@code path}。
     * <p>
     * <b>先挡环。</b>把节点移到自己的后代下会让树成环——此时「查整棵子树」的
     * {@code path like 'x/%'} 仍然能查，但树永远建不出来（那些节点互为父子，
     * 谁都不在根上），表现是<b>整个类目树从导航里消失</b>。而 path 恰好让这个判断
     * 变成一次字符串前缀比较，不必递归。
     * <p>
     * 自身只改内存里的字段，落库交给调用方那一次 {@code updateById}——它带着
     * name / sort / status 一起写，分成两次 UPDATE 只会多一次无谓的往返。
     */
    private void applyMove(CategoryEntity entity, long newParentId) {
        CategoryEntity newParent = newParentId == ROOT_PARENT_ID ? null : requireCategory(newParentId);
        if (newParent != null && isSelfOrDescendant(entity, newParent)) {
            throw new IllegalArgumentException("不能把类目「" + entity.getName() + "」移到它自己的子类目下");
        }

        int newLevel = newParent == null ? 1 : newParent.getLevel() + 1;
        List<CategoryEntity> descendants = descendantsOf(entity);
        // 子树最深的那一层也跟着一起上下移动，所以要按「相对自身的深度」校验总高度
        int deepestBelow = descendants.stream()
                .mapToInt(child -> child.getLevel() - entity.getLevel())
                .max()
                .orElse(0);
        if (newLevel + deepestBelow > MAX_LEVEL) {
            throw new IllegalArgumentException("移动后子类目会超过 " + MAX_LEVEL + " 级，"
                    + "当前子类目最深到第 " + (newLevel + deepestBelow) + " 级");
        }

        String oldPath = entity.getPath();
        String newPath = newParent == null ? String.valueOf(entity.getId())
                : newParent.getPath() + "/" + entity.getId();
        int levelDelta = newLevel - entity.getLevel();

        entity.setParentId(newParentId);
        entity.setLevel(newLevel);
        entity.setPath(newPath);

        for (CategoryEntity child : descendants) {
            // path 前缀整体换掉、后半段保留：换的是「从根到本节点」这一段，
            // 本节点以下的相对结构不变
            child.setPath(newPath + child.getPath().substring(oldPath.length()));
            child.setLevel(child.getLevel() + levelDelta);
            categoryMapper.updateById(child);
        }
        // 子树的逐个 UPDATE 与自身的 UPDATE 必须在同一事务里：中途失败会留下
        // 一半新 path、一半旧 path 的树，而那种树下「按类目筛选」会静默漏数据
    }

    /** 自己或自己的后代。{@code path} 是含自身的祖级路径，因此前缀比较即可，不必递归 */
    private boolean isSelfOrDescendant(CategoryEntity self, CategoryEntity candidate) {
        String path = candidate.getPath();
        return path != null && (path.equals(self.getPath()) || path.startsWith(self.getPath() + "/"));
    }

    private List<CategoryEntity> descendantsOf(CategoryEntity entity) {
        return categoryMapper.selectList(new LambdaQueryWrapper<CategoryEntity>()
                .likeRight(CategoryEntity::getPath, entity.getPath() + "/"));
    }

    // ==================== 品牌 ====================

    @Override
    public List<AdminBrand> brands() {
        return brandMapper.selectList(new LambdaQueryWrapper<BrandEntity>()
                        .orderByAsc(BrandEntity::getId))
                .stream()
                .map(brand -> AdminBrand.builder()
                        .id(brand.getId())
                        .name(brand.getName())
                        .logo(brand.getLogo())
                        .description(brand.getDescription())
                        .status(brand.getStatus())
                        .build())
                .toList();
    }

    @Override
    @Transactional
    public Long createBrand(BrandUpsertRequest request) {
        BrandEntity entity = new BrandEntity();
        entity.setName(request.getName().trim());
        entity.setLogo(trimToNull(request.getLogo()));
        entity.setDescription(trimToNull(request.getDescription()));
        entity.setStatus(normalizeStatus(request.getStatus()));
        insertBrand(entity);
        log.info("[管理] 新建品牌 id={} name={}", entity.getId(), entity.getName());
        return entity.getId();
    }

    @Override
    @Transactional
    public void updateBrand(Long brandId, BrandUpsertRequest request) {
        BrandEntity entity = requireBrand(brandId);
        String name = request.getName().trim();
        boolean renamed = !Objects.equals(entity.getName(), name);
        entity.setName(name);
        entity.setLogo(trimToNull(request.getLogo()));
        entity.setDescription(trimToNull(request.getDescription()));
        entity.setStatus(normalizeStatus(request.getStatus()));
        updateBrandRow(entity);

        if (renamed) {
            refreshProductsOfBrand(brandId);
        }
        log.info("[管理] 编辑品牌 id={} name={} renamed={}", brandId, name, renamed);
    }

    @Override
    @Transactional
    public void deleteBrand(Long brandId) {
        BrandEntity entity = requireBrand(brandId);
        long products = spuMapper.selectCount(new LambdaQueryWrapper<ProductSpuEntity>()
                .eq(ProductSpuEntity::getBrandId, brandId));
        if (products > 0) {
            throw new IllegalStateException("还有 " + products + " 个商品属于这个品牌，"
                    + "请先把它们改到别的品牌——直接删除会让这些商品挂在一个不存在的品牌上。");
        }
        brandMapper.deleteById(brandId);
        log.info("[管理] 删除品牌 id={} name={}", brandId, entity.getName());
    }

    /**
     * 品牌名是唯一约束，重复提交要在这里翻成一句人话。
     * <p>
     * 不先做一次 select 判重：那是「查一次再写一次」的竞态写法，两个并发请求都能查到
     * 「不存在」，然后一个成功一个炸在约束上。让库来判，我们只负责把异常翻译出来。
     */
    private void insertBrand(BrandEntity entity) {
        try {
            brandMapper.insert(entity);
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException("品牌「" + entity.getName() + "」已存在", e);
        }
    }

    private void updateBrandRow(BrandEntity entity) {
        try {
            brandMapper.updateById(entity);
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException("品牌「" + entity.getName() + "」已存在", e);
        }
    }

    // ==================== 连带刷新 ====================

    /**
     * 刷新挂在某个类目（含其子树）下的商品。
     * <p>
     * 只有<b>改名</b>才需要调用它：类目名冗余在商品详情与搜索索引里。
     * 移动类目不用——商品引用的是类目 id，不是路径。
     */
    private void refreshProductsOfCategory(Long categoryId) {
        List<Long> categoryIds = categoryService.selfAndDescendantIds(categoryId);
        if (categoryIds.isEmpty()) {
            return;
        }
        derivedRefresh.afterCommit(spuMapper.selectList(new LambdaQueryWrapper<ProductSpuEntity>()
                        .select(ProductSpuEntity::getId)
                        .in(ProductSpuEntity::getCategoryId, categoryIds))
                .stream()
                .map(ProductSpuEntity::getId)
                .toList());
    }

    private void refreshProductsOfBrand(Long brandId) {
        derivedRefresh.afterCommit(spuMapper.selectList(new LambdaQueryWrapper<ProductSpuEntity>()
                        .select(ProductSpuEntity::getId)
                        .eq(ProductSpuEntity::getBrandId, brandId))
                .stream()
                .map(ProductSpuEntity::getId)
                .toList());
    }

    // ==================== 校验与小工具 ====================

    private CategoryEntity requireCategory(Long categoryId) {
        CategoryEntity entity = categoryId == null ? null : categoryMapper.selectById(categoryId);
        if (entity == null) {
            throw new IllegalArgumentException("类目不存在");
        }
        return entity;
    }

    private BrandEntity requireBrand(Long brandId) {
        BrandEntity entity = brandId == null ? null : brandMapper.selectById(brandId);
        if (entity == null) {
            throw new IllegalArgumentException("品牌不存在");
        }
        return entity;
    }

    /**
     * 同级不允许重名。
     * <p>
     * 库里没有这个唯一约束（同级重名在 SQL 里要写成 {@code (parent_id, name)} 联合唯一，
     * 而 {@code parent_id} 用 0 表示顶级，语义上没问题、但加上去要改动既有表结构）。
     * 后果是管理台里出现两个一模一样的类目，运营分不清哪个挂着商品。
     */
    private void requireNameFreeUnderParent(long parentId, String name, Long excludeId) {
        String trimmed = name.trim();
        long sameName = categoryMapper.selectCount(new LambdaQueryWrapper<CategoryEntity>()
                .eq(CategoryEntity::getParentId, parentId)
                .eq(CategoryEntity::getName, trimmed)
                .ne(excludeId != null, CategoryEntity::getId, excludeId));
        if (sameName > 0) {
            throw new IllegalArgumentException("同一级下已经有叫「" + trimmed + "」的类目了");
        }
    }

    private long normalizeParentId(Long parentId) {
        return parentId == null ? ROOT_PARENT_ID : parentId;
    }

    private int normalizeStatus(Integer status) {
        if (status == null) {
            return STATUS_ON;
        }
        if (status != STATUS_ON && status != STATUS_OFF) {
            throw new IllegalArgumentException("状态只能是 0（停用）或 1（启用）");
        }
        return status;
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
