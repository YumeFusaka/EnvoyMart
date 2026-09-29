package yumefusaka.envoymart.orderservice.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class ApplyAfterSaleRequest {

    @NotNull(message = "orderItemId 不能为空")
    private Long orderItemId;

    /** REFUND_ONLY 仅退款 / RETURN_REFUND 退货退款 / EXCHANGE 换货 */
    @NotBlank(message = "售后类型不能为空")
    private String type;

    @NotBlank(message = "请选择售后原因")
    @Size(max = 64, message = "原因最长 64 位")
    private String reason;

    @Size(max = 500, message = "问题描述最长 500 位")
    private String description;

    @Size(max = 9, message = "最多上传 9 张凭证")
    private List<String> images;

    /**
     * 是否属于质量问题。
     * <p>
     * 由用户勾选而不是系统猜：质量问题的处理期限更长（15 天 vs 7 天），
     * 而这个判断需要实物证据，系统看不到。**但恶意勾选会走更长的窗口** ——
     * 所以审核环节必须真的审，不能自动通过。
     */
    private Boolean qualityIssue;
}
