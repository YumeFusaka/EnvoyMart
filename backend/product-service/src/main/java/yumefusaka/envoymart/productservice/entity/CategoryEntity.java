package yumefusaka.envoymart.productservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("category")
public class CategoryEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long parentId;
    private String name;
    /** 1 一级 / 2 二级 / 3 三级 */
    private Integer level;
    /** 祖级路径，形如 1/5/12。查整棵子树走 `path like '1/5/%'`，不必递归 */
    private String path;
    private Integer sort;
    private Integer status;
}
