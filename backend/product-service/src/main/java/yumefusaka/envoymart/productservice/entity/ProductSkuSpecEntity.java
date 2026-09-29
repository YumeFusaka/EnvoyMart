package yumefusaka.envoymart.productservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * SKU ↔ 规格值。
 * <p>
 * 用规范化关联表而不是在 SKU 上存 JSON：JSON 写起来快，但「找出所有黑色的 SKU」
 * 只能全表扫后内存过滤，规范化之后是一次索引命中。
 */
@Data
@TableName("product_sku_spec")
public class ProductSkuSpecEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long skuId;
    private Long specId;
    private Long specValueId;
}
