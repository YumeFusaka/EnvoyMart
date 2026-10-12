package yumefusaka.envoymart.productservice.projection;

import org.springframework.stereotype.Component;
import yumefusaka.envoymart.common.operation.OperationReceiptDecisionProbe;

/** 后续同SPU的GT也属于当前投影输入，不能只检查当前候选任务。 */
@Component
@lombok.extern.slf4j.Slf4j
public class ProductProjectionSources {
    private final ProductProjectionStore store;
    private final OperationReceiptDecisionProbe decisions;
    public ProductProjectionSources(ProductProjectionStore store, OperationReceiptDecisionProbe decisions) {
        this.store = store; this.decisions = decisions;
    }
    public void requireStable(long spuId) {
        for (String xid : store.pendingSources(spuId)) {
            var proof = decisions.inspect(xid);
            if ((proof.status() != OperationReceiptDecisionProbe.Status.COMMITTED && proof.status() != OperationReceiptDecisionProbe.Status.FINISHED)
                    || proof.pendingUndo() != 0) {
                log.info("[商品投影] 阶段=来源未决等待 spuId={} xid={} status={} pendingUndo={}", spuId, xid, proof.status(), proof.pendingUndo());
                throw new ProjectionSourceUnresolvedException("商品正在更新，请稍后重试");
            }
        }
    }
}
