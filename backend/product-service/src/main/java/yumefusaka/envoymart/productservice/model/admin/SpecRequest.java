package yumefusaka.envoymart.productservice.model.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 管理端提交的一组规格（"净含量" → ["90粒", "180粒"]）。
 * <p>
 * 整组替换语义：提交什么就是什么。没提交的规格会被删掉，它的规格值也一并删除。
 * 这是管理台的正确语义——表单上看到的就是全部，而不是「在原有基础上打补丁」，
 * 后者会让「怎么删掉一个规格」变成没有入口的操作。
 */
@Data
public class SpecRequest {

    @NotBlank(message = "规格名不能为空")
    @Size(max = 32, message = "规格名最长 32 个字符")
    private String name;

    @NotEmpty(message = "规格至少有一个可选值")
    private List<@NotBlank(message = "规格值不能为空") @Size(max = 64) String> values;
}
