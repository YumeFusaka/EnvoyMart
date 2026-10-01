package yumefusaka.envoymart.orderservice.service;

import yumefusaka.envoymart.common.result.PageResult;
import yumefusaka.envoymart.orderservice.model.CreateTicketRequest;
import yumefusaka.envoymart.orderservice.model.TicketDetailResponse;
import yumefusaka.envoymart.orderservice.model.TicketResponse;
import yumefusaka.envoymart.orderservice.model.TicketSummary;

/**
 * 用户侧的客服工单。
 * <p>
 * 每个方法的第一参数都是 userId，而且每条路径都经过归属校验 ——
 * 工单里装着用户的问题描述、订单号与沟通记录，是这个系统里隐私密度最高的
 * 数据之一，凭工单 id 猜到别人的工单必须返回"不存在"。
 */
public interface SupportTicketService {

    /** 提交工单。第一条消息随工单一起落库 */
    TicketDetailResponse create(String userId, CreateTicketRequest request);

    /** 我的工单列表，可按状态筛选，按最近活跃排序 */
    PageResult<TicketResponse> listMine(String userId, String status, Integer page, Integer size);

    /** 我的工单计数：页签与顶栏角标共用。口径见 {@link TicketSummary#getAwaitingMe()} */
    TicketSummary summary(String userId);

    TicketDetailResponse detail(String userId, Long ticketId);

    /** 追加说明（非终态） */
    TicketDetailResponse addMessage(String userId, Long ticketId, String content);

    /** 关闭自己的工单：已解决的=确认解决，其余=自行撤销 */
    TicketDetailResponse close(String userId, Long ticketId);

    /** 重开：<b>只对已解决的工单开放</b>，关闭是终态 */
    TicketDetailResponse reopen(String userId, Long ticketId, String content);
}
