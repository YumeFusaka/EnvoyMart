package yumefusaka.envoymart.aiservice.tool;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import yumefusaka.envoymart.agent.tool.ToolResult;
import yumefusaka.envoymart.common.operation.OperationContext;
import yumefusaka.envoymart.common.operation.OperationReceiptReply;
import yumefusaka.envoymart.common.result.Result;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class WriteReceiptsTest {
    private final OperationContext operation = OperationContext.of("u1", "op1", "cart_add", Map.of("skuId", 5L, "quantity", 1));
    private ResponseEntity<Result<Object>> missing() { return OperationReceiptReply.notFound(operation); }
    private ResponseEntity<Result<Object>> committed(Object data) { return new OperationReceiptReply<>(operation, Result.success(data), true).response(); }

    @Test void 业务提交后应答丢失只查询同一操作并返回首次回执() {
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger writes = new AtomicInteger();
        WriteReceipts.Receipt<Map<String, Object>> receipt = WriteReceipts.run(operation, false, Map.class,
                () -> reads.getAndIncrement() == 0 ? missing() : committed(Map.of("quantity", 1)),
                () -> { writes.incrementAndGet(); throw new IllegalStateException("模拟应答断开"); });
        assertThat(receipt.data()).containsEntry("quantity", 1);
        assertThat(receipt.replayed()).isTrue();
        assertThat(writes).hasValue(1);
        assertThat(reads).hasValue(2);
    }

    @Test void 只读恢复没有回执时不调用写入口且不声称未执行() {
        AtomicInteger writes = new AtomicInteger();
        assertThatThrownBy(() -> WriteReceipts.run(operation, true, Map.class, this::missing,
                () -> { writes.incrementAndGet(); return null; }))
                .isInstanceOf(WriteReceipts.ReceiptException.class).hasMessageContaining("不能推断");
        assertThat(writes).hasValue(0);
    }

    @Test void 原操作已有回执时即使业务后来变化也不重新提交() {
        AtomicInteger writes = new AtomicInteger();
        WriteReceipts.Receipt<Map<String, Object>> receipt = WriteReceipts.run(operation, false, Map.class,
                () -> committed(Map.of("quantity", 1)), () -> { writes.incrementAndGet(); return null; });
        assertThat(receipt.data()).containsEntry("quantity", 1);
        assertThat(writes).hasValue(0);
    }

    @Test void 缺少或错误回执元数据不能伪装成功() {
        var unknown = catchThrowable(() -> WriteReceipts.run(operation, true, Map.class,
                () -> ResponseEntity.ok(Result.success(Map.of("quantity", 1))), () -> null));
        assertThat(WriteReceipts.failure((Exception) unknown, "加购").getOperationState()).isEqualTo(ToolResult.OperationState.UNKNOWN);
        var different = OperationContext.of("u1", "op1", "cart_add", Map.of("skuId", 5L, "quantity", 2));
        assertThatThrownBy(() -> WriteReceipts.run(operation, true, Map.class,
                () -> new OperationReceiptReply<Object>(different, Result.success(Map.of("quantity", 2)), true).response(), () -> null))
                .isInstanceOf(WriteReceipts.ReceiptException.class).hasMessageContaining("不匹配");
    }

    @Test void 旧服务裸404不可放行首次写入() {
        AtomicInteger writes = new AtomicInteger();
        var failure = catchThrowable(() -> WriteReceipts.run(operation, false, Map.class,
                () -> ResponseEntity.ok(Result.error(404, "不存在路由")), () -> { writes.incrementAndGet(); return null; }));
        assertThat(WriteReceipts.failure((Exception) failure, "加购").getOperationState()).isEqualTo(ToolResult.OperationState.UNKNOWN);
        assertThat(writes).hasValue(0);
    }

    @Test void 空DTO必须保持未知而不是业务拒绝() {
        var failure = catchThrowable(() -> WriteReceipts.require(false));
        assertThat(WriteReceipts.failure((Exception) failure, "下单").getOperationState()).isEqualTo(ToolResult.OperationState.UNKNOWN);
    }

    @Test void 成功回执缺少非空业务数据时保持未知而删除的空回执可接受() {
        assertThatThrownBy(() -> WriteReceipts.run(operation, true, Map.class, () -> committed(null), () -> null))
                .hasMessageContaining("缺少业务结果");
        assertThat(WriteReceipts.run(operation, true, Void.class, () -> committed(null), () -> null).replayed()).isTrue();
    }
}
