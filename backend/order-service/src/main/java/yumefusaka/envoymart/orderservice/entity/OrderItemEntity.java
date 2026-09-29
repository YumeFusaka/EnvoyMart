package yumefusaka.envoymart.orderservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 订单行。
 * <p>
 * 存的是**下单那一刻的商品快照**，不是对商品表的引用：商品改名、改价、下架之后，
 * 历史订单必须还原当时的样子。这是订单行不与商品表做外键关联的唯一理由。
 */
@Data
@TableName("shop_order_item")
public class OrderItemEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long orderId;
    private String orderNo;
    private Long spuId;
    private Long skuId;
    /** 类目进快照：售后政策按类目判定，而订单行不引用商品表 */
    private Long categoryId;

    private String spuName;
    /** 形如 "规格:400IU×90粒;包装:瓶装" */
    private String skuSpecText;
    private String skuImage;

    /** 单位「分」 */
    private Long unitPrice;
    private Integer quantity;
    private Long subtotal;
}
