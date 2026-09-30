package yumefusaka.envoymart.productservice.model;

import lombok.Builder;
import lombok.Data;
import yumefusaka.envoymart.contract.ProductSummary;

import java.time.LocalDateTime;

/**
 * 收藏夹里的一条。
 * <p>
 * 没有直接把 {@link ProductSummary} 当返回体，是因为收藏夹要回答两个商品卡回答不了的问题：
 * <b>什么时候收藏的</b>、<b>现在还买不买得到</b>。后者不能靠「有没有商品数据」反推 ——
 * 商品下架后 {@code detail} 与列表都会把它藏起来（{@code loadDetail} 把下架等同于不存在），
 * 但「我收藏过它」这条记录不该跟着消失。
 * <p>
 * 也因此不往共享契约 {@link ProductSummary} 上加 status 字段：为收藏夹一处的展示需求
 * 让搜索、推荐、目录等全部消费方多背一个字段，不划算。
 */
@Data
@Builder
public class FavoriteItem {

    private Long spuId;
    private LocalDateTime favoritedAt;
    /** 是否仍在售。false 时前端标「已下架」并禁止加购，而不是把整条抹掉 */
    private boolean available;
    /** 商品卡片数据。商品被物理删除时为 null（库里就只剩这条收藏记录） */
    private ProductSummary product;
}
