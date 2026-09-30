package yumefusaka.envoymart.productservice.service;

import yumefusaka.envoymart.common.result.PageResult;
import yumefusaka.envoymart.productservice.model.FavoriteItem;

import java.util.List;

public interface FavoriteService {

    /**
     * 收藏一个商品。
     * <p>
     * 重复收藏算成功（幂等）：连点两下、两个标签页同时点，结果都该是「已收藏」，
     * 而不是其中一个弹红字。商品不存在时抛 {@code IllegalArgumentException}。
     */
    void add(String userId, Long spuId);

    /** 取消收藏。没收藏过也算成功（幂等）——取消的目标状态是「不在收藏夹里」，它已经达成了 */
    void remove(String userId, Long spuId);

    /** 我的收藏，按收藏时间倒序。页码 0 基，与全站约定一致 */
    PageResult<FavoriteItem> list(String userId, int page, int size);

    /**
     * 给一批商品查「哪些已收藏」，供商品列表页回填按钮的初始状态。
     * <p>
     * 返回的是<b>入参的子集</b>，不是逐项布尔值：调用方手里就有完整 id 列表，
     * 再回一份等长的 true/false 只是把「缺席」和「false」两种表达重复了一遍。
     */
    List<Long> favorited(String userId, List<Long> spuIds);
}
