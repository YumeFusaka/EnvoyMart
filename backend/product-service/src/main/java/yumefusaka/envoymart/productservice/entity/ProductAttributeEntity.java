package yumefusaka.envoymart.productservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 商品属性定义（网络制式 / 电池容量）。挂在**类目**上——同类目商品共用一套参数模板。
 * <p>
 * 与规格的区别：规格决定「买哪一个」，属性只是「描述是什么」。
 */
@Data
@TableName("product_attribute")
public class ProductAttributeEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long categoryId;
    private String name;
    /** TEXT 文本 / SELECT 单选 / MULTI_SELECT 多选 */
    private String inputType;
    private String unit;
    private Integer sort;
}
