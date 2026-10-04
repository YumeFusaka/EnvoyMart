package yumefusaka.envoymart.agent.core.task;

import yumefusaka.envoymart.agent.core.TaskStage;

import java.util.List;

/**
 * 一次任务的断点快照 —— 「跑到哪了」的完整可恢复形态。
 * <p>
 * <b>为什么需要它，以及它为什么不是「把整个 GraphState 存下来」。</b>
 * 图状态里有 {@code ToolResult.rawData} 这类业务 DTO，它们不保证可序列化，
 * 塞进快照会在写入时抛 {@code NotSerializableException}——而那正好发生在
 * 「任务撞上高危操作、正需要保存现场」的路径上。所以快照<b>刻意只留可序列化的执行结果</b>：
 * 意图、阶段、已经跑完的步骤、待确认的操作、以及本轮消耗的轮次。
 * <p>
 * <b>{@code pendingActions} 里存的是载荷（工具名 + 入参）而不是一句描述</b>，
 * 与 {@code AgentGraph.GraphResult#pendingActions} 同一口径：恢复这一轮时要执行的
 * 是那次调用本身，不是关于它的一句话。
 *
 * @param taskId         任务标识。同一轮对话的规划、执行、中断、恢复共享它
 * @param userId         归属用户。恢复时必须校验，否则换个人带上 taskId 就能接着跑别人的任务
 * @param sessionId      会话标识
 * @param stage          保存时的阶段。恢复的判据就是「这个阶段有没有后续动作可接」
 * @param coreIntent     首次规划冻结的核心意图（见 AgentGraph#KEY_CORE_INTENT）
 * @param executedTools  已经真正执行过的工具名，按执行顺序。恢复时据此跳过重复调用
 * @param pendingActions 待用户确认的调用载荷。为空表示不是在等确认
 * @param round          已用掉的规划轮次。恢复时接着它继续计数，护栏预算才不会被重置绕过
 * @param savedAtEpochMs 保存时刻，用于过期判断与可观测
 */
public record TaskCheckpoint(String taskId,
                             String userId,
                             String sessionId,
                             TaskStage stage,
                             String coreIntent,
                             List<String> executedTools,
                             List<PendingCall> pendingActions,
                             int round,
                             long savedAtEpochMs) {

    public TaskCheckpoint {
        executedTools = executedTools == null ? List.of() : List.copyOf(executedTools);
        pendingActions = pendingActions == null ? List.of() : List.copyOf(pendingActions);
    }

    /**
     * 待执行的调用载荷。
     * <p>
     * 不直接复用 {@code PendingAction}：那个类在 agent-core 的 tool 包里，
     * 与 TaskCheckpoint 放一起会形成「任务状态 → 工具」的反向依赖，
     * 而快照是需要能被独立序列化与独立演进的一层。
     */
    public record PendingCall(String tool, java.util.Map<String, Object> arguments) {
        public PendingCall {
            // 不能用 Map.copyOf：模型生成的参数值是任意 JSON，可能是 null，
            // 而 copyOf 遇到 null 值直接抛 —— 那正好发生在「任务中断、要保存现场」的路径上。
            // 与 PendingAction 同一处理（那边也踩过这个坑，注释在它的紧凑构造器里）
            arguments = arguments == null || arguments.isEmpty()
                    ? java.util.Map.of()
                    : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(arguments));
        }
    }

    /**
     * 这一份快照能不能接着跑。
     * <p>
     * 只有 {@link TaskStage#WAITING_USER} 是「有事在等」——其余阶段要么已经收尾（DONE），
     * 要么是进程被中途掐断（PLANNING / EXECUTING / CHECKING）而成了一份<b>过期现场</b>：
     * 那种情况下的正确行为是重跑，而不是从一个没有外部触发点的中间态接着做，
     * 因为「接着做」需要知道当时那一步的输出，而快照里没有（也不该有）。
     */
    /**
     * 这个断点还能不能接着跑。
     * <p>
     * <b>原先只认 {@code WAITING_USER}</b>，理由是「等确认」是唯一真正需要保存现场的时刻。
     * 但那只覆盖了「跑到一半停下等人」，漏掉了另一半：**跑着的时候进程没了**。
     * 那种情况下没有断点可读，用户下一次问同一件事等于从零开始——
     * 而前几步的查询结果（比如「哪一单是待发货的」）本来是可以复用的。
     * <p>
     * 现在 {@code EXECUTING} 也可恢复。两类阶段的恢复语义**不同**，所以分开问：
     * <ul>
     *   <li>{@link #awaitingApproval()}：停下等用户点确认，恢复时要执行 {@code pendingActions}；</li>
     *   <li>{@link #resumable()}：还有后续动作可接（等确认，或执行中途被打断）。
     *       执行中途的恢复**不重放**已完成的工具，而是把「已经查到了什么」作为上下文交给下一轮，
     *       让模型接着往下走——重放是危险的，工具可能有副作用。</li>
     * </ul>
     * <p>
     * {@code CHECKING} / {@code DONE} 不可恢复：前者是瞬时中间态（评估完立刻进下一步），
     * 后者任务已经完成，把完成的任务当「未完成」提示会给模型一个错误的现场。
     */
    public boolean resumable() {
        return stage == TaskStage.WAITING_USER || stage == TaskStage.EXECUTING;
    }

    /** 是否在等用户确认。恢复这类断点要执行 {@code pendingActions}，与执行中途的恢复不是一回事 */
    public boolean awaitingApproval() {
        return stage == TaskStage.WAITING_USER;
    }
}