package yumefusaka.envoymart.common.operation;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;
import yumefusaka.envoymart.common.result.Result;
import java.util.Optional;
import java.util.function.Supplier;

/** 与领域写共用事务的回执编解码；事务边界由下游业务入口负责。 */
@Slf4j
public final class OperationReceiptStore {
    public interface Repository {
        OperationReceiptRow find(String userId, String operationId);
        void claim(OperationContext operation);
        int complete(OperationContext operation, String responseJson);
    }
    private final Repository repository;
    private final ObjectMapper mapper;
    private final OperationReceiptDecisionProbe decisions;

    public OperationReceiptStore(Repository repository, ObjectMapper mapper) {
        this(repository, mapper, OperationReceiptDecisionProbe.unavailable());
    }

    public OperationReceiptStore(Repository repository, ObjectMapper mapper, OperationReceiptDecisionProbe decisions) {
        this.repository = repository;
        this.mapper = mapper;
        this.decisions = decisions;
    }

    public <T> Optional<OperationReceiptReply<T>> replay(OperationContext operation, Class<T> dataType) {
        var row = repository.find(operation.userId(), operation.operationId());
        if (row == null) return Optional.empty();
        if (!operation.toolName().equals(row.toolName()) || !operation.parametersHash().equals(row.parametersHash())) {
            log.warn("[操作回执] 阶段=冲突 tool={} operationId={}", operation.toolName(), operation.operationId());
            throw new OperationConflictException();
        }
        if (row.hashVersion() != 1 || row.receiptVersion() != 1 || row.completedAt() == null || row.responseJson() == null) {
            throw new OperationReceiptUnavailableException("原操作回执未就绪或版本不支持，结果待确认");
        }
        requireCommitted(row);
        Result<T> body;
        try {
            body = mapper.readValue(row.responseJson(), mapper.getTypeFactory().constructParametricType(Result.class, dataType));
        } catch (RuntimeException e) {
            log.error("[操作回执] 阶段=解码失败 operationId={}", operation.operationId(), e);
            throw new OperationReceiptUnavailableException("原操作回执无法读取，结果待确认");
        }
        if (body == null || body.getCode() == null || body.getCode() != 200 || (body.getData() == null && !"cart_remove".equals(operation.toolName())))
            throw new OperationReceiptUnavailableException("原操作回执完整性异常，结果待确认");
        log.info("[操作回执] 阶段=重放 tool={} operationId={} hashVersion=1 receiptVersion=1", operation.toolName(), operation.operationId());
        return Optional.of(new OperationReceiptReply<>(operation, body, true));
    }

    private void requireCommitted(OperationReceiptRow row) {
        if (row.globalXid() == null) {
            if ("LOCAL".equals(row.decisionMark()) || (row.decisionMark() == null && !"cart_checkout".equals(row.toolName()))) return;
            throw new OperationReceiptUnavailableException("原结算或事务回执缺少可验证的全局事务来源，结果待确认");
        }
        if (!row.globalXid().matches("[\\x21-\\x7E]{1,128}") || "LOCAL".equals(row.decisionMark())
                || (row.decisionMark() != null && !java.util.Set.of("GLOBAL_PENDING", "GLOBAL_COMMITTED").contains(row.decisionMark()))) {
            throw new OperationReceiptUnavailableException("原回执事务来源不完整或相互矛盾，结果待确认");
        }
        OperationReceiptDecisionProbe.Proof proof;
        try { proof = decisions.inspect(row.globalXid()); }
        catch (RuntimeException unavailable) {
            log.warn("[操作回执] 阶段=全局决议检查不可用 operationId={} errorType={}", row.operationId(), unavailable.getClass().getSimpleName());
            throw new OperationReceiptUnavailableException("全局事务权威决议暂时无法核验，结果待确认");
        }
        if (proof != null && proof.status() == OperationReceiptDecisionProbe.Status.ROLLED_BACK) {
            log.error("[操作回执] 阶段=完整性异常 全局已回滚但成功行仍在 operationId={} xid={}", row.operationId(), row.globalXid());
            throw new OperationReceiptUnavailableException("全局事务已回滚但成功回执行仍在，必须核对完整性，结果待确认");
        }
        if (proof == null || (proof.status() != OperationReceiptDecisionProbe.Status.COMMITTED
                && proof.status() != OperationReceiptDecisionProbe.Status.FINISHED) || proof.pendingUndo() != 0) {
            log.warn("[操作回执] 阶段=全局提交未证明 operationId={} xid={} proof={}", row.operationId(), row.globalXid(), proof);
            throw new OperationReceiptUnavailableException("全局事务尚未证明提交或undo尚未结算，结果待确认");
        }
    }

    public <T> OperationReceiptReply<T> commit(OperationContext operation, Class<T> dataType, Supplier<T> business) {
        // 认领使用 INSERT 唯一键。不存在行的 FOR UPDATE 在 InnoDB 下会持有 gap lock，
        // 两次并发查询再插入反而容易形成死锁；已有回执由事务外门面读取。
        repository.claim(operation);
        T result = business.get();
        if (dataType != Void.class && result == null) throw new IllegalStateException("操作没有返回权威业务结果，事务必须回滚");
        Result<T> body = Result.success(result);
        String json = mapper.writeValueAsString(body);
        if (repository.complete(operation, json) != 1) throw new IllegalStateException("操作回执写入失败，事务必须回滚");
        log.info("[操作回执] 阶段=待提交 tool={} operationId={} hashVersion=1 receiptVersion=1", operation.toolName(), operation.operationId());
        return new OperationReceiptReply<>(operation, body, false);
    }
}
