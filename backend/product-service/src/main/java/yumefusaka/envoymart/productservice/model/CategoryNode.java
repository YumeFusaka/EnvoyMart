package yumefusaka.envoymart.productservice.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 类目树节点。一次查出扁平列表后在内存里组装，避免逐层查库 */
@Data
public class CategoryNode {

    private Long id;
    private String name;
    private Integer level;
    private Integer sort;
    /**
     * 1 启用 / 0 停用。
     * <p>
     * 公开树里它恒为 1（停用的类目不会出现在结果里），管理树要靠它区分「停用」与「启用」——
     * 管理台必须看得到停用的类目，否则停用之后就再没有入口把它改回来。
     */
    private Integer status;
    private List<CategoryNode> children = new ArrayList<>();
}
