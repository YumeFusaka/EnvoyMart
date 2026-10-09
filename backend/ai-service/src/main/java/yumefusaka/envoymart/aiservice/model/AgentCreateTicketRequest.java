package yumefusaka.envoymart.aiservice.model;

import lombok.Data;

/** 工单创建的跨服务 JSON 契约，字段与 order-service CreateTicketRequest 对齐。 */
@Data
public class AgentCreateTicketRequest {
    private String category;
    private String title;
    private String content;
    private Long orderId;
}
