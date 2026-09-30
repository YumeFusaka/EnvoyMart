package yumefusaka.envoymart.productservice.model.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 管理端提交的一条商品参数取值（属性 id + 值）。
 * <p>
 * 只提交值，不提交属性名与单位：那两样属于类目上的属性模板（{@code product_attribute}），
 * 是另一份数据。让管理端把名字一起传上来，等于把模板的定义权散进了每个商品的提交里——
 * 改一次模板要改所有商品。
 */
@Data
public class AttributeRequest {

    @NotNull(message = "参数项不能为空")
    private Long attributeId;

    @NotBlank(message = "参数值不能为空")
    /** 上限对齐 {@code product_attribute_value.attr_value varchar(255)}——超过就是落库时 500 */
    @Size(max = 255, message = "参数值最长 255 个字符")
    private String value;
}
