package yumefusaka.envoymart.productservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 可售单元层（"维生素 D3 软胶囊 400IU×90粒"）。价格与库存挂在这一层 */
@Data
@TableName("product_sku")
public class ProductSkuEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long spuId;
    private String skuCode;
    /** 售价，单位「分」。用整数存金额：浮点误差会破坏「子项之和 = 总额」这个恒等式 */
    private Long price;
    private Long originalPrice;
    private Integer stock;
    private String image;
    private Integer status;
}
