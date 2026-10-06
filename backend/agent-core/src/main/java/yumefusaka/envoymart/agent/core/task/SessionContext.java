package yumefusaka.envoymart.agent.core.task;

import yumefusaka.envoymart.agent.core.TaskStage;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话级任务现场 —— 「这个会话目前正在办的是哪件事」。
 * <p>
 * <b>它解决的是什么。</b>真实用户不会为每件事开一个新会话：一个会话里先问商品推荐、
 * 再问订单、再问成分能不能一起吃。此前每一轮的意图判断都只看**这一轮这句话**，
 * 于是「那第二个呢」这类省略句只能靠指代消解猜，而猜的依据是整段对话历史——
 * 历史里躺着三件互不相干的事，模型只能挑一个。
 * <p>
 * <b>与 {@link TaskState} 的分工。</b>{@code TaskState} 描述的是<b>一次任务内部</b>的
 * 进度（PLANNING → EXECUTING → … → DONE），它随任务开始与结束存在与消失；
 * {@code SessionContext} 描述的是<b>会话层面</b>的事：当前的核心意图是什么、
 * 这一轮在办哪个子任务、已经完成的步骤、以及一个可序列化的上下文快照。
 * 一次会话里会有多个 {@code TaskState}（每件事一个），但只有一个 {@code SessionContext}。
 * 把两者合并成一个「当前状态」字段，会让「任务结束了」与「会话没有进行中的任务」
 * 变成同一种表示——而它们在该不该继承上一轮上下文这件事上给出相反的答案。
 * <p>
 * <b>字段名与用户拍板一致</b>：{@code core_intent} / {@code current_subtask} /
 * {@code pending_tools} / {@code completed_steps} / {@code context_snapshot}。
 * 它们是这条状态对外的稳定契约，前端与日志按这些名字读。
 * <p>
 * <b>不可变。</b>{@link #switchTo} / {@link #advance} / {@link #complete} 都产出新实例。
 * 理由与 {@link TaskState} 相同：中断恢复要把某一刻的现场整份存下来，
 * 可变对象会让「存下来的那份」被后续步骤继续改写。
 */
public record SessionContext(String userId,
                             String sessionId,
                             String coreIntent,
                             String currentSubtask,
                             List<String> pendingTools,
                             List<String> completedSteps,
                             Map<String, Object> contextSnapshot,
                             TaskStage stage,
                             int round,
                             long updatedAtEpochMs) {

    public SessionContext {
        pendingTools = pendingTools == null ? List.of() : List.copyOf(pendingTools);
        completedSteps = completedSteps == null ? List.of() : List.copyOf(completedSteps);
        // 不能用 Map.copyOf：模型生成的参数值是任意 JSON，可能是 null，
        // 而 copyOf 遇到 null 值直接抛——那正好发生在「要保存现场」的路径上
        // （TaskState 的紧凑构造器里记过同一个坑）
        contextSnapshot = contextSnapshot == null || contextSnapshot.isEmpty()
                ? Map.of()
                : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(contextSnapshot));
    }

    /** 空现场：会话还没有任何进行中的事 */
    public static SessionContext empty(String userId, String sessionId, long nowMs) {
        return new SessionContext(userId, sessionId, null, null,
                List.of(), List.of(), Map.of(), TaskStage.DONE, 0, nowMs);
    }

    /**
     * 换一件事做 —— 核心意图被替换，子任务与待办清空。
     * <p>
     * <b>为什么要清空子任务与待办，而不是保留。</b>继承上一件事的子任务会让新一轮
     * 的上下文里同时存在两件事的痕迹，而模型分不清哪个是「现在的」；
     * 待办更是危险：上一件事里等着确认的调用，会在新话题里被当成「还没做完的事」
     * 重新提起。已完成的步骤<b>保留</b>——它记录的是这个会话真做过什么，
     * 是「不要再重复查一遍」的依据。
     */
    public SessionContext switchTo(String newIntent, long nowMs) {
        return new SessionContext(userId, sessionId, newIntent, null,
                List.of(), completedSteps, Map.of(), TaskStage.PLANNING, round + 1, nowMs);
    }

    /** 推进子任务与上下文快照，不动核心意图 */
    public SessionContext advance(String subtask, Map<String, Object> snapshot, long nowMs) {
        return new SessionContext(userId, sessionId, coreIntent, subtask,
                pendingTools, completedSteps, snapshot, stage, round, nowMs);
    }

    /** 记一步已完成。去重且保序 */
    public SessionContext complete(String step, long nowMs) {
        if (step == null || step.isBlank()) {
            return this;
        }
        java.util.Set<String> merged = new java.util.LinkedHashSet<>(completedSteps);
        merged.add(step);
        return new SessionContext(userId, sessionId, coreIntent, currentSubtask,
                pendingTools, new java.util.ArrayList<>(merged), contextSnapshot, stage, round, nowMs);
    }

    /** 记下待执行的调用名。空列表表示没有待办（已收尾或从未中断） */
    public SessionContext withPending(List<String> tools, long nowMs) {
        return new SessionContext(userId, sessionId, coreIntent, currentSubtask,
                tools, completedSteps, contextSnapshot, stage, round, nowMs);
    }

    /** 还没有进行中的意图 */
    public boolean idle() {
        return coreIntent == null || coreIntent.isBlank();
    }
}
