package yumefusaka.envoymart.productservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 属性的可选值（SELECT 类型的候选项）。TEXT 类型不需要候选项 */
@Data
@TableName("product_attribute_value")
public class ProductAttributeValueEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long attributeId;
    private String attrValue;
    private Integer sort;
}
