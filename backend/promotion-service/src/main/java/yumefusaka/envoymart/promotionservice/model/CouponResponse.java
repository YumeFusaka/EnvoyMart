package yumefusaka.envoymart.promotionservice.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 券模板的对外视图。金额单位「分」 */
@Data
@Builder
public class CouponResponse {

    private Long id;
    private String name;
    private String type;
    /** 「满 300 减 30」「8.5 折」这样的一句话说明，前端直接展示 */
    private String ruleText;
    private Long amount;
    private BigDecimal discount;
    /** 使用门槛（分），0 表示无门槛 */
    private Long threshold;
    private String scopeType;
    private Integer totalCount;
    private Integer receivedCount;
    /** 剩余可领数量。前端据此显示「仅剩 N 张」 */
    private Integer remainingCount;
    private LocalDateTime validFrom;
    private LocalDateTime validTo;

    /** 当前用户是否已领过。同一张券每人限领一次 */
    private Boolean received;
}
