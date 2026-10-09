package yumefusaka.envoymart.aiservice.model;

import lombok.Data;

import java.time.LocalDateTime;

/** 领券工具使用的稳定、脱敏优惠券投影。 */
@Data
public class AgentCouponResponse {
    private Long id;
    private Long couponId;
    private String name;
    private String type;
    private String ruleText;
    private Long amount;
    private Long threshold;
    private String status;
    private Boolean received;
    private LocalDateTime expireAt;
}
