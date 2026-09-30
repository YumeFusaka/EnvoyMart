package yumefusaka.envoymart.orderservice.model.admin;

import lombok.Builder;
import lombok.Data;
import yumefusaka.envoymart.orderservice.model.TicketMessageView;

import java.util.List;

/** 管理端详情 = 管理端字段（含 userId 与关闭原因）+ 全部消息 */
@Data
@Builder
public class AdminTicketDetail {

    private AdminTicketSummary ticket;
    /** 关闭原因不再对客服隐藏：客服用它回答"这条为什么被关了" */
    private String closeReason;
    private List<TicketMessageView> messages;
}
