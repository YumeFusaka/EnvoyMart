package yumefusaka.envoymart.productservice.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import yumefusaka.envoymart.common.result.PageResult;
import yumefusaka.envoymart.contract.ProductSummary;
import yumefusaka.envoymart.productservice.entity.ProductSpuEntity;
import yumefusaka.envoymart.productservice.entity.UserFavoriteEntity;
import yumefusaka.envoymart.productservice.mapper.UserFavoriteMapper;
import yumefusaka.envoymart.productservice.mapper.ProductSpuMapper;
import yumefusaka.envoymart.productservice.model.FavoriteItem;
import yumefusaka.envoymart.productservice.service.FavoriteService;
import yumefusaka.envoymart.productservice.service.ProductService;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class FavoriteServiceImpl implements FavoriteService {

    private static final int STATUS_ON = 1;

    /** 一页最多多少条。收藏夹是个人数据，页大小没有理由比商品目录（100）还大 */
    private static final int MAX_PAGE_SIZE = 50;

    /**
     * {@link #favorited} 一次最多核对多少个商品。
     * <p>
     * 上限不是性能洁癖，是<b>不许静默少查</b>：如果超了就直接把多余的 id 丢掉，
     * 前端会把这些商品显示成「未收藏」——一个不报错、用户自己都说不清的错。
     * 所以宁可报错。50 是收藏夹自己的页大小上限，100 是目录的上限，200 留足余量。
     */
    private static final int MAX_CHECK_IDS = 200;

    private final UserFavoriteMapper favoriteMapper;
    private final ProductSpuMapper spuMapper;
    private final ProductService productService;

    public FavoriteServiceImpl(UserFavoriteMapper favoriteMapper,
                               ProductSpuMapper spuMapper,
                               ProductService productService) {
        this.favoriteMapper = favoriteMapper;
        this.spuMapper = spuMapper;
        this.productService = productService;
    }

    @Override
    public void add(String userId, Long spuId) {
        if (spuId == null) {
            throw new IllegalArgumentException("缺少商品 id");
        }
        // 先确认商品存在。不拦下架商品：临时下架后重新上架是常态，
        // 「我收藏过它」这件事在中间那段时间依然成立，而下架商品本身进不了列表页与详情页，
        // 不会有人误收藏。真正要挡的是凭空捏一个 id 进来把表当草稿纸
        if (spuMapper.selectById(spuId) == null) {
            throw new IllegalArgumentException("商品不存在");
        }

        UserFavoriteEntity entity = new UserFavoriteEntity();
        entity.setUserId(userId);
        entity.setSpuId(spuId);
        entity.setCreatedAt(LocalDateTime.now());
        try {
            favoriteMapper.insert(entity);
        } catch (DuplicateKeyException ignored) {
            // 唯一键 (user_id, spu_id) 挡下的重复收藏。先查再插也能挡住绝大多数重复，
            // 但挡不住两次请求同时穿过那个窗口——那种情况下库里只留一行是对的，
            // 报错给用户是错的
        }
    }

    @Override
    public void remove(String userId, Long spuId) {
        favoriteMapper.delete(new LambdaQueryWrapper<UserFavoriteEntity>()
                .eq(UserFavoriteEntity::getUserId, userId)
                .eq(UserFavoriteEntity::getSpuId, spuId));
    }

    @Override
    public PageResult<FavoriteItem> list(String userId, int page, int size) {
        int zeroBasedPage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));

        Page<UserFavoriteEntity> result = favoriteMapper.selectPage(
                new Page<>(zeroBasedPage + 1, safeSize),
                new LambdaQueryWrapper<UserFavoriteEntity>()
                        .eq(UserFavoriteEntity::getUserId, userId)
                        // 同一秒内收藏多个商品时 created_at 会打平，加 id 兜底保证翻页不重不漏
                        .orderByDesc(UserFavoriteEntity::getCreatedAt)
                        .orderByDesc(UserFavoriteEntity::getId));

        return PageResult.<FavoriteItem>builder()
                .records(assemble(result.getRecords()))
                .total(result.getTotal())
                .page(zeroBasedPage)
                .size(safeSize)
                .build();
    }

    @Override
    public List<Long> favorited(String userId, List<Long> spuIds) {
        if (spuIds == null || spuIds.isEmpty()) {
            return List.of();
        }
        if (spuIds.size() > MAX_CHECK_IDS) {
            throw new IllegalArgumentException("一次最多核对 " + MAX_CHECK_IDS + " 个商品");
        }
        return favoriteMapper.selectList(new LambdaQueryWrapper<UserFavoriteEntity>()
                        .select(UserFavoriteEntity::getSpuId)
                        .eq(UserFavoriteEntity::getUserId, userId)
                        .in(UserFavoriteEntity::getSpuId, spuIds))
                .stream()
                .map(UserFavoriteEntity::getSpuId)
                .toList();
    }

    /**
     * 把收藏记录拼成可展示的条目。
     * <p>
     * 商品数据与在售状态分两次查：前者用 {@link ProductService#summaries}（它顺带算出了
     * 价格区间、库存、类目名，收藏夹页要的就是商品卡那一套），后者需要 SPU 的 status，
     * 而 ProductSummary 里没有。
     * <p>
     * 两次都是按主键 in 查、一页最多 50 个 id，代价可以忽略；换来的是共享契约不用为
     * 一个页面的需求加字段。真要合并成一次，正确的位置是让 product-service 自己多一个
     * 「带状态的列表项」方法，而不是把 status 加进 ProductSummary。
     */
    private List<FavoriteItem> assemble(List<UserFavoriteEntity> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }

        List<Long> spuIds = rows.stream().map(UserFavoriteEntity::getSpuId).distinct().toList();

        Map<Long, Integer> statusById = spuMapper.selectByIds(spuIds).stream()
                .collect(Collectors.toMap(ProductSpuEntity::getId, ProductSpuEntity::getStatus,
                        (first, second) -> first));

        Map<Long, ProductSummary> cardById = productService.summaries(spuIds).stream()
                .collect(Collectors.toMap(ProductSummary::getId, Function.identity(),
                        (first, second) -> first));

        return rows.stream()
                .map(row -> FavoriteItem.builder()
                        .spuId(row.getSpuId())
                        .favoritedAt(row.getCreatedAt())
                        .available(Integer.valueOf(STATUS_ON).equals(statusById.get(row.getSpuId())))
                        .product(cardById.get(row.getSpuId()))
                        .build())
                .toList();
    }
}
