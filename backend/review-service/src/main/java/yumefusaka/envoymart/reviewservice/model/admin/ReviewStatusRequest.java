package yumefusaka.envoymart.reviewservice.model.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 改变评价状态（通过审核 / 隐藏 / 恢复）。
 * <p>
 * 提交的是状态本身而不是「隐藏」「恢复」两个动作：状态只有三个取值，
 * 而为每个动作开一个接口会让同一件事有两条路径，两条都要各自维护权限与留痕逻辑。
 */
@Data
public class ReviewStatusRequest {

    /** PENDING 待审核 / PUBLISHED 已发布 / HIDDEN 已隐藏 */
    @NotBlank(message = "状态不能为空")
    private String status;

    /**
     * 隐藏原因。设为 HIDDEN 时必填（在服务层校验，因为「必填」取决于 status 的取值）。
     * <p>
     * 隐藏是可以被滥用的动作——商家隐藏差评——所以它必须留下理由；
     * 反过来，恢复或通过审核时这个字段没有意义，会被忽略并清空已存的隐藏记录。
     */
    @Size(max = 255, message = "隐藏原因不能超过 255 字")
    private String reason;
}
