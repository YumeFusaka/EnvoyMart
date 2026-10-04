package yumefusaka.envoymart.aiservice.model;

import lombok.Data;

/**
 * 加购请求 —— Agent 侧的下单入口之一。
 * <p>
 * 只保留「买什么规格、买几件」这两个字段。收货信息不在这里：加购不涉及收货人，
 * 把它塞进来只会让模型多填一个它此刻不需要填的字段，而多填的字段就是多一处会填错的地方。
 */
@Data
public class AgentAddCartRequest {

    private Long skuId;
    private Integer quantity;
}
