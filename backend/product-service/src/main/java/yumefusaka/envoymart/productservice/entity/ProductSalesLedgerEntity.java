package yumefusaka.envoymart.productservice.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 销量台账：某一条订单行计入过一次销量。
 * <p>
 * 主键是 {@code order_item_id} 而没有自增 id —— 唯一性本身就是这张表的主要用途，
 * 它让「同一条支付完成消息被投递两次」变成一次无害的重复插入。
 * <p>
 * <b>不能拿 {@code (order_id, spu_id)} 当主键</b>：一笔订单里同一个商品买两个规格
 * （三个 SKU 同属一个 SPU）是完全正常的，那样会把第 2、3 行判成重复投递丢掉，
 * 销量少算且毫无动静。
 */
@Data
@TableName("product_sales_ledger")
public class ProductSalesLedgerEntity {

    private Long orderItemId;
    private Long orderId;
    private Long spuId;
    private Integer quantity;
    private LocalDateTime createdAt;
}
