package yumefusaka.envoymart.orderservice.service;

import io.seata.spring.annotation.GlobalLock;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import yumefusaka.envoymart.common.operation.OperationClaimConflictException;
import yumefusaka.envoymart.common.operation.OperationContext;
import yumefusaka.envoymart.common.operation.OperationReceiptReply;
import yumefusaka.envoymart.common.operation.OperationReceiptRow;
import yumefusaka.envoymart.common.operation.OperationReceiptStore;
import yumefusaka.envoymart.common.operation.OperationReceiptDecisionProbe;
import yumefusaka.envoymart.common.operation.OperationReceiptUnavailableException;
import yumefusaka.envoymart.orderservice.entity.OperationReceiptEntity;
import yumefusaka.envoymart.orderservice.mapper.OperationReceiptMapper;
import java.util.Optional;
import java.util.function.Supplier;

@Service
public class OperationReceiptService {
    private final OperationReceiptStore store;
    public OperationReceiptService(OperationReceiptMapper mapper, ObjectMapper objectMapper) {
        this(mapper, objectMapper, OperationReceiptDecisionProbe.unavailable());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public OperationReceiptService(OperationReceiptMapper mapper, ObjectMapper objectMapper, SeataOperationReceiptDecisionProbe decisions) {
        this(mapper, objectMapper, (OperationReceiptDecisionProbe) decisions);
    }

    private OperationReceiptService(OperationReceiptMapper mapper, ObjectMapper objectMapper, OperationReceiptDecisionProbe decisions) {
        store = new OperationReceiptStore(new OperationReceiptStore.Repository() {
            @Override
            public OperationReceiptRow find(String userId, String operationId) {
                var row = mapper.lockByOperation(userId, operationId);
                return row == null ? null : new OperationReceiptRow(row.getId(), row.getUserId(), row.getOperationId(),
                        row.getToolName(), row.getParametersHash(), row.getHashVersion(), row.getReceiptVersion(),
                        row.getResponseJson(), row.getCreatedAt(), row.getCompletedAt(), row.getGlobalXid(), row.getDecisionMark());
            }
            @Override
            public void claim(OperationContext operation) {
                var row = new OperationReceiptEntity();
                row.setUserId(operation.userId()); row.setOperationId(operation.operationId());
                row.setToolName(operation.toolName()); row.setParametersHash(operation.parametersHash());
                String xid = org.apache.seata.core.context.RootContext.getXID();
                if (xid != null && !xid.matches("[\\x21-\\x7E]{1,128}")) throw new IllegalStateException("受信全局事务XID不合法，拒绝认领操作");
                row.setGlobalXid(xid); row.setDecisionMark(xid == null ? "LOCAL" : "GLOBAL_PENDING");
                try { mapper.claim(row); }
                catch (DuplicateKeyException e) { throw new OperationClaimConflictException(e); }
            }
            @Override
            public int complete(OperationContext operation, String json) {
                return mapper.complete(operation.userId(), operation.operationId(), json);
            }
        }, objectMapper, decisions);
    }

    @GlobalLock
    @Transactional
    public <T> Optional<OperationReceiptReply<T>> replay(OperationContext operation, Class<T> type) {
        return store.replay(operation, type);
    }

    @GlobalLock
    @Transactional
    public <T> OperationReceiptReply<T> verifyCommitted(OperationReceiptReply<T> draft, Class<T> type) {
        var verified = store.replay(draft.operation(), type)
                .orElseThrow(() -> new OperationReceiptUnavailableException("业务返回后原成功回执不存在，结果待确认"));
        return new OperationReceiptReply<>(draft.operation(), verified.body(), draft.replayed());
    }

    @GlobalLock
    @Transactional
    public <T> OperationReceiptReply<T> commit(OperationContext operation, Class<T> type, Supplier<T> action) {
        return store.commit(operation, type, action);
    }
}
