package yumefusaka.envoymart.orderservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 购物车条目。
 * <p>
 * 引用的是 <b>SKU</b> 而不是商品：价格与库存都挂在 SKU 上，购物车里放的
 * 也必须是一行行具体的规格（"维生素 D3 400IU×90粒"）。用商品维度的话，
 * 结算时根本无法确定该扣哪个规格的库存。
 * <p>
 * 表上有 {@code unique (user_id, sku_id)}：同商品累加由唯一键保证，
 * 而不是靠「先查后写」的应用逻辑 —— 后者在并发加购时会插出两条。
 */
@Data
@TableName("cart_item")
public class CartItemEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String userId;
    private Long spuId;
    private Long skuId;
    private Integer quantity;
    /** 0 未勾选 / 1 已勾选。结算时只结算勾选的条目 */
    private Integer selected;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
