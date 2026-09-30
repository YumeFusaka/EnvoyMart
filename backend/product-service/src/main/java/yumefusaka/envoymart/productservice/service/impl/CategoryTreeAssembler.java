package yumefusaka.envoymart.productservice.service.impl;

import org.springframework.stereotype.Component;
import yumefusaka.envoymart.productservice.entity.CategoryEntity;
import yumefusaka.envoymart.productservice.model.CategoryNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 扁平类目列表 → 树。
 * <p>
 * 公开树（只含启用）与管理树（含停用）用同一份组装：两者的差别只在<b>查哪些行</b>，
 * 一个状态过滤条件的事。组装逻辑本身——尤其是「父节点被停用而成了孤儿该怎么办」这条
 * 边界——如果各写一份，两边迟早分叉，而分叉的表现是「管理台看得到的类目，前台看不到」，
 * 反过来也一样，两种都很难在排查时联想到树组装。
 */
@Component
public class CategoryTreeAssembler {

    /**
     * @param all 已按 sort、id 排好序的扁平列表；顺序决定同级类目的展示顺序
     */
    public List<CategoryNode> build(List<CategoryEntity> all) {
        // 一次查完在内存里挂子树：类目总量是几十条量级，
        // 逐层查库的往返开销远大于这一步
        Map<Long, CategoryNode> nodes = new LinkedHashMap<>();
        for (CategoryEntity entity : all) {
            CategoryNode node = new CategoryNode();
            node.setId(entity.getId());
            node.setName(entity.getName());
            node.setLevel(entity.getLevel());
            node.setSort(entity.getSort());
            node.setStatus(entity.getStatus());
            // 真实的挂载点，与它在树里被摆在哪个位置无关：父类目停用后子节点会被挂到根上，
            // 那时候「树上的父」和「库里记的父」不是同一个（见 CategoryNode#parentId）
            node.setParentId(entity.getParentId() == null ? 0L : entity.getParentId());
            nodes.put(entity.getId(), node);
        }

        List<CategoryNode> roots = new ArrayList<>();
        for (CategoryEntity entity : all) {
            CategoryNode node = nodes.get(entity.getId());
            CategoryNode parent = entity.getParentId() == null ? null : nodes.get(entity.getParentId());
            if (parent == null) {
                // 顶级类目（parentId = 0），或父节点被停用而成了孤儿。后者也让它留在根上：
                // 藏起来的话整棵子树会从界面上消失，而数据其实还在，排查时很难想到是这里
                roots.add(node);
            } else {
                parent.getChildren().add(node);
            }
        }
        return roots;
    }
}
