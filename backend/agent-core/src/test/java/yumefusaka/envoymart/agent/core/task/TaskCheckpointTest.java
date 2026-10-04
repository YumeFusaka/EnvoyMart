package yumefusaka.envoymart.agent.core.task;

import org.junit.jupiter.api.Test;
import yumefusaka.envoymart.agent.core.TaskStage;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TaskCheckpointTest {

    /**
     * 两类现场可恢复，但它们**不是同一件事**：
     * 等确认的恢复要执行 pendingActions；执行中途的恢复只是把「查到哪了」交回给模型。
     * <p>
     * 这里曾经断言 {@code EXECUTING} 不可恢复，理由是「接着做需要知道当时那一步的输出，
     * 而快照里没有」。那个理由现在不成立了：快照里有 {@code coreIntent} 与 {@code executedTools}，
     * 足够让模型「不重复已查过的步骤、接着往下走」——**恢复不等于重放**。
     * 一条测试钉住旧结论时，要连着它的理由一起看，否则会把「当时的限制」当成「设计意图」。
     */
    @Test
    void 等确认与执行中途都可恢复且语义可区分() {
        assertTrue(checkpoint(TaskStage.WAITING_USER).resumable());
        assertTrue(checkpoint(TaskStage.EXECUTING).resumable());
        // 只有等确认那类要执行 pendingActions
        assertTrue(checkpoint(TaskStage.WAITING_USER).awaitingApproval());
        assertFalse(checkpoint(TaskStage.EXECUTING).awaitingApproval());
        // 已收尾、以及瞬时中间态不当成「未完成的任务」——
        // 前者会让模型去续一个已经做完的任务，后者没有后续动作可接
        assertFalse(checkpoint(TaskStage.DONE).resumable());
        assertFalse(checkpoint(TaskStage.PLANNING).resumable());
        assertFalse(checkpoint(TaskStage.CHECKING).resumable());
    }

    @Test
    void 空集合被归一成不可变的空列表而不是null() {
        TaskCheckpoint cp = new TaskCheckpoint("t", "u", "s", TaskStage.DONE, null, null, null, 1, 0L);
        assertNotNull(cp.executedTools());
        assertNotNull(cp.pendingActions());
        assertTrue(cp.executedTools().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> cp.executedTools().add("x"));
    }

    @Test
    void 待确认调用的参数里可以有null值() {
        // 模型生成的参数是任意 JSON，值可能是 null，而 Map.copyOf 遇到 null 值直接抛——
        // 那正好发生在「任务中断、要保存现场」的路径上。这里钉住这个坑
        // 注意 Map.of 本身不接受 null 值，这里要构造的是「从 JSON 解析回来的那种含 null 的 Map」——
        // 真实来源是 Jackson 解析出的 LinkedHashMap，用 HashMap 模拟它
        var args = new java.util.HashMap<String, Object>();
        args.put("orderId", 12);
        args.put("reason", null);
        var call = new TaskCheckpoint.PendingCall("order_cancel", args);
        assertEquals("order_cancel", call.tool());
        assertEquals(12, call.arguments().get("orderId"));
        assertNull(call.arguments().get("reason"));
    }

    @Test
    void 待确认调用的参数不可被外部修改() {
        var call = new TaskCheckpoint.PendingCall("t", new java.util.HashMap<>(Map.of("a", 1)));
        assertThrows(UnsupportedOperationException.class, () -> call.arguments().put("b", 2));
    }

    private static TaskCheckpoint checkpoint(TaskStage stage) {
        return new TaskCheckpoint("t", "u", "s", stage, "查订单物流",
                List.of("order_query"), List.of(), 1, 0L);
    }
}