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
     * 挂在哪个类目下，{@code 0} 表示一级类目。口径与 {@code CategoryUpsertRequest} 一致。
     * <p>
     * <b>树结构里本来就能推出这一项，但推不出「孤儿」的情况</b>：父类目被停用后，
     * 子节点会被挂到根上展示（否则整棵子树从界面上消失，而数据还在）。这时候它在树里的位置
     * 是「根」，真实的挂载点却是那个停用的父类目。管理台拿它去提交编辑请求时，
     * 用树上的位置当挂载点会把子类目真的挪到根，用这一项才不会动它。
     * <p>
     * 管理台靠它才能做到「编辑时只改名字、不改挂载点」——把这个字段省掉，
     * 客户端就只能猜，而猜错的表现是<b>改一次名字，一整棵子树换了位置</b>。
     */
    private Long parentId;
    /**
     * 1 启用 / 0 停用。
     * <p>
     * 公开树里它恒为 1（停用的类目不会出现在结果里），管理树要靠它区分「停用」与「启用」——
     * 管理台必须看得到停用的类目，否则停用之后就再没有入口把它改回来。
     */
    private Integer status;
    private List<CategoryNode> children = new ArrayList<>();
}
