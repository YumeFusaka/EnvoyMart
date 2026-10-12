package yumefusaka.envoymart.orderservice.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.seata.core.context.RootContext;
import yumefusaka.envoymart.orderservice.entity.OrderEntity;

/** AT阶段一可见不等于订单已成立；失败终态同样不得进入支付或履约。 */
@Slf4j
public final class OrderGlobalDecision {
    private OrderGlobalDecision() { }

    public static void requireCommitted(OrderEntity order, SeataOperationReceiptDecisionProbe probe) {
        if (order == null || order.getGlobalXid() == null || order.getGlobalXid().isBlank()) return;
        if (order.getGlobalXid().equals(RootContext.getXID())) return;
        if (probe != null && probe.committed(order.getGlobalXid())) return;
        log.warn("[订单决议] 阶段=拒绝未确认订单 orderId={} xid={} 当前xid={}",
                order.getId(), order.getGlobalXid(), RootContext.getXID());
        throw new yumefusaka.envoymart.common.operation.OperationReceiptUnavailableException("订单事务结果尚未确认，请稍后重试");
    }
}
