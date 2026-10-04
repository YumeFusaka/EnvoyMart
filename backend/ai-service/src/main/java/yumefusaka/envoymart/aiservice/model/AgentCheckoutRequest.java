package yumefusaka.envoymart.aiservice.model;

import lombok.Data;

/**
 * 下单请求 —— 收货信息由用户在确认环节提供。
 * <p>
 * <b>与 order-service 的 CheckoutRequest 是两份类型，不是笔误。</b>那一份是交易域的入参契约，
 * 带校验注解、随交易规则演进；这一份是 Agent 的调用面，只声明「Agent 会说出口的字段」。
 * 让 ai-service 直接复用交易域 DTO，等于把交易域的字段增删直接变成 Agent 工具签名的变化，
 * 而后者是给模型看的提示词的一部分——它不该跟着另一个服务的重构抖动。
 */
@Data
public class AgentCheckoutRequest {

    private String receiverName;
    private String receiverPhone;
    private String receiverProvince;
    private String receiverCity;
    private String receiverDistrict;
    private String receiverDetail;
    private String remark;
}
