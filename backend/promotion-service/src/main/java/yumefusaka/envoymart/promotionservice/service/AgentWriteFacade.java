package yumefusaka.envoymart.promotionservice.service;

import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;
import yumefusaka.envoymart.common.operation.OperationClaimConflictException;
import yumefusaka.envoymart.common.operation.OperationContext;
import yumefusaka.envoymart.common.operation.OperationReceiptReply;
import yumefusaka.envoymart.common.operation.OperationReceiptUnavailableException;
import yumefusaka.envoymart.promotionservice.model.UserCouponResponse;

@Service
@Slf4j
public class AgentWriteFacade {
    private final OperationReceiptService receipts;
    private final CouponService coupons;
    public AgentWriteFacade(OperationReceiptService receipts, CouponService coupons) { this.receipts = receipts; this.coupons = coupons; }
    public OperationReceiptReply<UserCouponResponse> receive(OperationContext operation, Long couponId) {
        var previous = receipts.replay(operation, UserCouponResponse.class);
        if (previous.isPresent()) return previous.get();
        try {
            var result = receipts.commit(operation, UserCouponResponse.class, () -> coupons.receive(operation.userId(), couponId));
            result = receipts.verifyCommitted(result, UserCouponResponse.class);
            log.info("[操作回执] 阶段=已提交 tool={} operationId={} replay={}", operation.toolName(), operation.operationId(), result.replayed());
            return result;
        }
        catch (OperationClaimConflictException error) {
            return receipts.replay(operation, UserCouponResponse.class)
                    .orElseThrow(() -> new OperationReceiptUnavailableException("领券操作仍在处理，结果待确认"));
        } catch (RuntimeException error) {
            log.warn("[操作回执] 阶段=回滚或结果待确认 tool={} operationId={} errorType={}", operation.toolName(), operation.operationId(), error.getClass().getSimpleName());
            throw error;
        }
    }
}
