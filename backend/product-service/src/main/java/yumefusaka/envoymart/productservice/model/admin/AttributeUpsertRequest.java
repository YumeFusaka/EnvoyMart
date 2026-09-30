package yumefusaka.envoymart.productservice.model.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新增或编辑一个参数模板项。
 * <p>
 * 刻意**不含 {@code categoryId}**：新建时它来自路径，编辑时它不可改 ——
 * 改挂类目等于把一件商品的参数定义换到另一套模板上，而已经填过的值不会跟着走，
 * 结果是商品带着一堆「不属于本类目」的参数。要换类目就删了重建。
 */
@Data
public class AttributeUpsertRequest {

    @NotBlank(message = "参数名不能为空")
    @Size(max = 32, message = "参数名最长 32 个字符")
    private String name;

    @Size(max = 16, message = "控件类型最长 16 个字符")
    private String inputType = "text";

    @Size(max = 16, message = "单位最长 16 个字符")
    private String unit;

    private Integer sort = 0;
}
