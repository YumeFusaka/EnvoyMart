package yumefusaka.envoymart.productservice.controller;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import yumefusaka.envoymart.common.result.PageResult;
import yumefusaka.envoymart.common.result.Result;
import yumefusaka.envoymart.common.web.IdentityHeaderInterceptor;
import yumefusaka.envoymart.productservice.model.FavoriteItem;
import yumefusaka.envoymart.productservice.service.FavoriteService;

import java.util.List;

/**
 * 商品收藏（用户私有数据）。
 * <p>
 * <b>userId 一律取自网关注入的身份头</b>，没有一个接口从参数里收 userId：
 * 收藏是「我的」，参数里收 id 就等于让任何人都能读写别人的收藏夹。网关已经
 * 拦在 /favorites/** 前（未登录拿不到身份头，这里会因缺头直接失败），
 * 所以这一层不需要再做登录判断，只需要不自己发明一个 userId 来源。
 * <p>
 * 写操作用 POST/DELETE 两个动词表达，而不是一个 `POST /favorites/toggle`：
 * 收藏按钮在弱网下会重发请求，toggle 重发一次的结果是「取消收藏」——
 * 用户看到的状态和请求次数有关，这种接口没法重试。
 */
@RestController
@RequestMapping("/favorites")
public class FavoriteController {

    private final FavoriteService favoriteService;

    public FavoriteController(FavoriteService favoriteService) {
        this.favoriteService = favoriteService;
    }

    @PostMapping("/{spuId}")
    public Result<Void> add(@PathVariable("spuId") Long spuId,
                            @RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId) {
        favoriteService.add(userId, spuId);
        return Result.success(null);
    }

    @DeleteMapping("/{spuId}")
    public Result<Void> remove(@PathVariable("spuId") Long spuId,
                               @RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId) {
        favoriteService.remove(userId, spuId);
        return Result.success(null);
    }

    @GetMapping
    public Result<PageResult<FavoriteItem>> list(@RequestParam(value = "page", defaultValue = "0") int page,
                                                 @RequestParam(value = "size", defaultValue = "20") int size,
                                                 @RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId) {
        return Result.success(favoriteService.list(userId, page, size));
    }

    /**
     * 批量核对是否已收藏，供商品列表页回填心形按钮。
     * <p>
     * 路径 {@code /check} 与 {@code /{spuId}} 不冲突：这里有 @GetMapping 且有字面量路径，
     * Spring 的字面量优先于模板，不会把它当成「spuId 为 check」的请求。
     */
    @GetMapping("/check")
    public Result<List<Long>> check(@RequestParam("spuIds") List<Long> spuIds,
                                    @RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId) {
        return Result.success(favoriteService.favorited(userId, spuIds));
    }
}
