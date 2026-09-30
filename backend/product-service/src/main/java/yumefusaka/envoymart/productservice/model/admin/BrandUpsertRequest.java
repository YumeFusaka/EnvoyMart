package yumefusaka.envoymart.productservice.model.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 新建 / 编辑品牌。品牌名在库里是唯一约束，重复提交要靠这一层转成可读的提示 */
@Data
public class BrandUpsertRequest {

    @NotBlank(message = "品牌名不能为空")
    @Size(max = 64, message = "品牌名最长 64 个字符")
    private String name;

    @Size(max = 512)
    private String logo;

    @Size(max = 500, message = "品牌简介最长 500 个字符")
    private String description;

    /** 1 启用 / 0 停用 */
    private Integer status = 1;
}
