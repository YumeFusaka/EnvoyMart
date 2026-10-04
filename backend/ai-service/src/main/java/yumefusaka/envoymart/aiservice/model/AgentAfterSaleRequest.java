package yumefusaka.envoymart.aiservice.model;

import lombok.Data;

/**
 * 售后申请请求。
 * <p>
 * {@code type} 是交易域的取值（退货 / 换货 / 退款），由模型的参数说明约束成白名单，
 * 而不是让模型自由发挥——它编一个不存在的类型，下游只会给一句业务码非 200 的报错。
 */
@Data
public class AgentAfterSaleRequest {

    private Long orderItemId;
    private String type;
    private String reason;
    private String description;
    private Boolean qualityIssue;
}
