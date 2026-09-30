package yumefusaka.envoymart.orderservice.model.admin;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 商家备注。
 * <p>
 * 255 与 {@code shop_order.admin_remark} 的列宽一致，校验放在这里而不是靠数据库报错：
 * 超长在 MySQL 严格模式下是报错、在 H2 下是静默截断，两边行为不一致——
 * 而「界面上写着要问客户，库里只剩半句」这种事只会在 H2 上发生，
 * 也就是只会在本地和 CI 上发生。
 */
@Data
public class AdminOrderRemarkRequest {

    /** 空串即清除备注 —— 见 {@code OrderAdminService#remark} */
    @Size(max = 255, message = "备注最长 255 个字符")
    private String remark;
}
