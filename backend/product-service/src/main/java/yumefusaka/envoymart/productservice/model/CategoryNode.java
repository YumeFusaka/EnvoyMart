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
    private List<CategoryNode> children = new ArrayList<>();
}
