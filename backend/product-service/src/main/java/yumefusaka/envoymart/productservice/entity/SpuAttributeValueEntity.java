package yumefusaka.envoymart.productservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 某个商品在某项属性上的取值。属性定义挂类目，取值挂商品 */
@Data
@TableName("spu_attribute_value")
public class SpuAttributeValueEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long spuId;
    private Long attributeId;
    private String attrValue;
}
