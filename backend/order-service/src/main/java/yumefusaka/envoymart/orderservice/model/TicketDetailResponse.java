package yumefusaka.envoymart.orderservice.model;

import lombok.Builder;
import lombok.Data;
import yumefusaka.envoymart.orderservice.entity.SupportTicketEntity;

import java.util.List;

/**
 * 工单详情 = 工单字段 + 全部消息（时间正序）。
 * <p>
 * 详情里一次性带回全部消息，不分页：工单是对话，中间缺一段就读不懂，
 * 而单条工单的消息量天然有界（varchar 2000 × 来回几十条已经是很长的纠纷）。
 */
@Data
@Builder
public class TicketDetailResponse {

    private TicketResponse ticket;
    private List<TicketMessageView> messages;

    public static TicketDetailResponse of(SupportTicketEntity entity, List<TicketMessageView> messages) {
        return TicketDetailResponse.builder()
                .ticket(TicketResponse.from(entity))
                .messages(messages)
                .build();
    }
}
