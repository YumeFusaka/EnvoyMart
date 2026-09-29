package yumefusaka.envoymart.productservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 规格项（颜色 / 容量 / 口味）—— 决定买的是哪一个 */
@Data
@TableName("product_spec")
public class ProductSpecEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long spuId;
    private String name;
    private Integer sort;
}
