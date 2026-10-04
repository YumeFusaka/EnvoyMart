package yumefusaka.envoymart.orderservice.service;

import yumefusaka.envoymart.orderservice.model.TicketAwaitingPayload;

/**
 * 工单「等你回应」的推送出口。
 *
 * <p>存在的唯一理由是让写路径<b>只表达意图</b>（"这个用户的工单状态变了，推一下"），
 * 不必各自去拼载荷、也不必知道推送是 SSE 还是别的什么。四个写路径（客服回复、
 * 用户发消息、关闭、重开、以及超时自动关闭）都要触发它；把它们各自的推送代码复制
 * 五遍，就是给自己埋一个「改了四处、漏了第五处」的坑。
 */
public interface TicketNotifier {

    /**
     * 重新计算该用户的「等你回应」并推送给他的全部在线连接。
     * <p>
     * <b>实现必须是尽力而为的</b>：推送失败不能影响已经落库的业务结果。
     */
    void notifyAwaiting(String userId);

    /** 直接推送已知的载荷（超时批处理用它，避免逐条重查） */
    void push(String userId, TicketAwaitingPayload payload);
}
