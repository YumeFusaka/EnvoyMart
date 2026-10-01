package yumefusaka.envoymart.promotionservice.model;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/** 用户持有的券 */
@Data
@Builder
public class UserCouponResponse {

    private Long id;
    private Long couponId;
    private String name;
    private String type;
    private String ruleText;
    private Long amount;
    private Long threshold;
    /** UNUSED / USED / EXPIRED */
    private String status;
    private String statusText;
    /** 核销在哪张订单上 */
    private String orderNo;
    private LocalDateTime receivedAt;
    private LocalDateTime usedAt;
    /** 过期时间。前端据此显示「N 天后过期」 */
    private LocalDateTime expireAt;
}
