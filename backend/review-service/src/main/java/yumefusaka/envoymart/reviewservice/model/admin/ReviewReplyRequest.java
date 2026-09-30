package yumefusaka.envoymart.reviewservice.model.admin;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 商家回复评价。
 * <p>
 * <b>不标 {@code @NotBlank}</b>：传空串或 null 表示<b>撤回回复</b>。撤回是个真实需求
 * （回复写错了、把内部口径写进了对外发言），与其另开一个删除接口，不如让同一个
 * {@code PUT} 承担「写上」与「擦掉」两种结果——重复提交同一个请求体结果一样，语义是干净的。
 */
@Data
public class ReviewReplyRequest {

    /** 空或 null 即撤回回复 */
    @Size(max = 500, message = "回复不能超过 500 字")
    private String content;
}
