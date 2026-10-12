package yumefusaka.envoymart.orderservice.service;

import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;
import yumefusaka.envoymart.common.operation.OperationContext;
import yumefusaka.envoymart.common.operation.OperationReceiptReply;
import yumefusaka.envoymart.common.operation.OperationClaimConflictException;
import yumefusaka.envoymart.common.operation.OperationReceiptUnavailableException;
import yumefusaka.envoymart.orderservice.model.*;
import yumefusaka.envoymart.contract.OrderResponse;
import java.util.function.Supplier;

/** Agent 八个写入口的显式适配层：协议回执与已有领域服务在同一事务中完成。 */
@Service
@Slf4j
public class AgentWriteFacade {
    private final OperationReceiptService receipts;
    private final CartService cart;
    private final OrderDomainService orders;
    private final AfterSaleService afterSales;
    private final SupportTicketService tickets;

    public AgentWriteFacade(OperationReceiptService receipts, CartService cart, OrderDomainService orders,
                            AfterSaleService afterSales, SupportTicketService tickets) {
        this.receipts = receipts; this.cart = cart; this.orders = orders; this.afterSales = afterSales; this.tickets = tickets;
    }
    public OperationReceiptReply<CartItemResponse> addCart(OperationContext op, AddCartItemRequest req) {
        return execute(op, CartItemResponse.class, () -> receipts.commit(op, CartItemResponse.class, () -> cart.add(op.userId(), req)));
    }
    public OperationReceiptReply<CartItemResponse> updateCart(OperationContext op, Long id, UpdateCartItemRequest req) {
        return execute(op, CartItemResponse.class, () -> receipts.commit(op, CartItemResponse.class, () -> cart.updateQuantity(op.userId(), id, req)));
    }
    public OperationReceiptReply<Void> removeCart(OperationContext op, Long id) {
        return execute(op, Void.class, () -> receipts.commit(op, Void.class, () -> { cart.remove(op.userId(), id); return null; }));
    }
    public OperationReceiptReply<OrderResponse> checkout(OperationContext op, CheckoutRequest req) {
        return execute(op, OrderResponse.class, () -> orders.checkoutWithOperation(op.userId(), req, op));
    }
    public OperationReceiptReply<OrderResponse> cancel(OperationContext op, String rawId) {
        return execute(op, OrderResponse.class, () -> receipts.commit(op, OrderResponse.class, () -> rawId.matches("\\d+")
                ? orders.cancelOrder(op.userId(), Long.valueOf(rawId)) : orders.cancelOrderByNo(op.userId(), rawId)));
    }
    public OperationReceiptReply<AfterSaleResponse> afterSale(OperationContext op, ApplyAfterSaleRequest req) {
        return execute(op, AfterSaleResponse.class, () -> receipts.commit(op, AfterSaleResponse.class, () -> afterSales.apply(op.userId(), req)));
    }
    public OperationReceiptReply<TicketDetailResponse> ticket(OperationContext op, CreateTicketRequest req) {
        return execute(op, TicketDetailResponse.class, () -> receipts.commit(op, TicketDetailResponse.class, () -> tickets.create(op.userId(), req)));
    }

    private <T> OperationReceiptReply<T> execute(OperationContext op, Class<T> type, Supplier<OperationReceiptReply<T>> attempt) {
        var existing = receipts.replay(op, type);
        if (existing.isPresent()) return existing.get();
        try {
            var result = attempt.get();
            result = receipts.verifyCommitted(result, type);
            log.info("[操作回执] 阶段=已提交 tool={} operationId={} replay={}", op.toolName(), op.operationId(), result.replayed());
            return result;
        }
        catch (OperationClaimConflictException e) {
            // 此处在业务代理之外，本次事务已回滚；新事务读取胜者，不能继续用 rollback-only 连接。
            return receipts.replay(op, type).orElseThrow(() -> new OperationReceiptUnavailableException("操作仍在处理，结果待确认"));
        } catch (RuntimeException e) {
            log.warn("[操作回执] 阶段=回滚或结果待确认 tool={} operationId={} errorType={}", op.toolName(), op.operationId(), e.getClass().getSimpleName());
            throw e;
        }
    }
}
