package yumefusaka.envoymart.productservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 规格值（黑色 / 128G）。
 * <p>
 * 字段名是 {@code spec_value} 而不是 {@code value}：后者是 H2 的保留字，会直接语法报错，
 * 且加引号绕不过去 —— MySQL 用反引号、H2 用双引号，一份 DDL 没法共用。
 */
@Data
@TableName("product_spec_value")
public class ProductSpecValueEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long specId;
    private String specValue;
    private Integer sort;
}
