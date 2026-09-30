package yumefusaka.envoymart.orderservice.service;

import yumefusaka.envoymart.common.result.PageResult;
import yumefusaka.envoymart.orderservice.model.admin.AdminTicketDetail;
import yumefusaka.envoymart.orderservice.model.admin.AdminTicketQuery;
import yumefusaka.envoymart.orderservice.model.admin.AdminTicketSummary;

/**
 * 管理侧的客服工单工作台。
 * <p>
 * 三个写动作各自对应状态机上的一条边：回复（OPEN 或 RESOLVED → PROCESSING）、
 * 标记解决（→ RESOLVED）、关闭（→ CLOSED）。**没有"直接改状态"的入口** ——
 * 能提交任意目标状态的接口，等于把状态机交给调用方，而状态机是这个域里
 * 唯一说得清"工单现在处于哪一步"的东西。
 */
public interface TicketAdminService {

    PageResult<AdminTicketSummary> list(AdminTicketQuery query);

    AdminTicketDetail detail(Long ticketId);

    /**
     * 回复用户。对 OPEN 的工单是"首次回复即接手"，对 RESOLVED 的工单是"回复即重启"——
     * 两者都回到 PROCESSING，后者顺带清掉解决时间、重新开始等确认的计时。
     */
    AdminTicketDetail reply(Long ticketId, String content, String operatorId);

    /** 标记解决。可附一段说明（落为一条客服消息），填不填都由客服决定 */
    AdminTicketDetail resolve(Long ticketId, String content, String operatorId);

    /** 关闭工单，原因必填；会落一条带操作人的消息，让用户看到是谁关的、为什么 */
    AdminTicketDetail close(Long ticketId, String reason, String operatorId);
}
