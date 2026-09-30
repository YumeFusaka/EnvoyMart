package yumefusaka.envoymart.productservice.model.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新建 / 编辑类目。
 * <p>
 * <b>没有 level 与 path 字段</b>：两者都是从 {@code parentId} 推导出来的派生值
 * （{@code level = 父.level + 1}，{@code path = 父.path + "/" + id}）。
 * 让调用方传，就等于允许提交出「层级写着 3、父节点其实是 1 级」这类自相矛盾的数据，
 * 而类目树的所有查询都建立在这两列上。
 */
@Data
public class CategoryUpsertRequest {

    /** 0 表示一级类目 */
    private Long parentId = 0L;

    @NotBlank(message = "类目名不能为空")
    @Size(max = 64, message = "类目名最长 64 个字符")
    private String name;

    private Integer sort = 0;

    /** 1 启用 / 0 停用。停用不影响已挂在它下面的商品，只是导航树里不再出现 */
    private Integer status = 1;
}
