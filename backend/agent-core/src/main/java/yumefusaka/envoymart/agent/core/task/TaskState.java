package yumefusaka.envoymart.agent.core.task;

import yumefusaka.envoymart.agent.core.TaskStage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一次任务的显式状态 —— 编排层唯一认的「现在到哪了」。
 * <p>
 * <b>为什么要有它，而不是继续散在 GraphState 的若干键里。</b>任务状态此前是隐式的：
 * 意图在 {@code KEY_CORE_INTENT}、待确认在 {@code pendingActions}、跑完的步骤在
 * {@code steps}、阶段在 {@code stage} —— 每个字段都对，但它们<b>没有一个共同的载体</b>，
 * 于是「恢复时该带哪些」只能靠调用方逐个别去凑，漏一个就静默少恢复一块。
 * 更要紧的是：<b>没有载体就没有合法流转</b>。阶段可以随便从 DONE 跳回 PLANNING 而没人拦，
 * 因为没人知道「现在这段代码是在迁移哪个状态」。
 * <p>
 * <b>状态与阶段的分工。</b>{@link TaskStage} 是<b>对外可观察的进度</b>（给前端显示、
 * 给用户看）；{@code TaskState} 是<b>编排层的内部契约</b>——它多带了字段级的账：
 * 核心意图、当前子任务、待执行的调用、已完成的步骤、以及上下文快照。
 * 两者不是一回事：前端只需要知道「在等你确认」，恢复逻辑需要知道「等的是哪两个调用、
 * 意图是什么、第几轮」。把这两层压成一个字段，迟早会出现「为了显示而改状态」。
 * <p>
 * <b>为什么字段名用下划线风格。</b>它们是任务状态对外序列化的稳定契约
 * （前端、日志、将来的可观测面板都按这些名字读），下划线是这条契约的既有命名，
 * 不随 Java 侧的驼峰偏好改。内部 getter 仍是驼峰，两种风格在这里有各自的读者。
 * <p>
 * <b>不可变 + 迁移函数。</b>每次流转产出一份新实例（{@link #withStage} / {@link #complete} /
 * {@link #await}），不原地改。理由是中断恢复要把某一刻的现场整份存下来，
 * 可变对象会让「存下来的那份」被后续步骤继续改写，快照就不再是快照。
 */
public record TaskState(String taskId,
                        String userId,
                        String sessionId,
                        String coreIntent,
                        String currentSubtask,
                        List<String> pendingTools,
                        List<String> completedSteps,
                        Map<String, Object> contextSnapshot,
                        TaskStage stage,
                        int round,
                        long updatedAtEpochMs) {

    public TaskState {
        pendingTools = pendingTools == null ? List.of() : List.copyOf(pendingTools);
        completedSteps = completedSteps == null ? List.of() : List.copyOf(completedSteps);
        // 不能用 Map.copyOf：模型生成的参数值是任意 JSON，可能是 null，
        // 而 copyOf 遇到 null 值直接抛——那正好发生在「任务中断、要保存现场」的路径上
        contextSnapshot = contextSnapshot == null || contextSnapshot.isEmpty()
                ? Map.of()
                : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(contextSnapshot));
    }

    /** 新建一份初始状态：还没有任何执行，处在 PLANNING */
    public static TaskState initial(String taskId, String userId, String sessionId, long nowMs) {
        return new TaskState(taskId, userId, sessionId, null, null,
                List.of(), List.of(), Map.of(), TaskStage.PLANNING, 0, nowMs);
    }

    /**
     * 阶段流转 —— <b>唯一的入口</b>，非法流转在这里被拒绝而不是被静静写下去。
     * <p>
     * <b>为什么允许的边这么少。</b>这些边是任务真实会走的路：规划完去执行、
     * 执行中撞高危去等用户、执行完去核对、核对完收尾；外加上一条<b>核对不满意回退重规划</b>
     * 的边（那正是 replan 存在的原因），以及<b>等用户确认后回到执行</b>的边（恢复）。
     * <p>
     * <b>PLANNING → DONE 是合法边，不是漏网。</b>计划为空（直接对话、知识问答、
     * 无需工具的一轮）本来就该从规划直接收尾——这正是最常见的一条路径，
     * 每轮正常回答都走它。把它判成非法会有两个后果：合法边表的告警变成<b>每轮必现</b>，
     * 于是真正该被注意的那条（PLANNING → EXECUTING → … → DONE 之间某条不合法跳转）
     * 被淹没在噪声里；以及调用方被迫绕过状态机直接构造 TaskState，
     * <b>一道本该拦错的闸成了必须绕开的障碍</b>，那它就什么也拦不住了。
     * <p>
     * <b>同一阶段可以自迁移</b>（EXECUTING → EXECUTING）：一次任务里执行多个步骤是常态，
     * 每次推进子任务都算一次执行中的状态更新，把它判成非法会把正常流程挡在门外。
     */
    private static final Map<TaskStage, Set<TaskStage>> LEGAL = buildLegal();

    private static Map<TaskStage, Set<TaskStage>> buildLegal() {
        Map<TaskStage, Set<TaskStage>> m = new LinkedHashMap<>();
        m.put(TaskStage.PLANNING, Set.of(TaskStage.PLANNING, TaskStage.EXECUTING,
                TaskStage.WAITING_USER, TaskStage.DONE));
        m.put(TaskStage.EXECUTING, Set.of(TaskStage.EXECUTING, TaskStage.WAITING_USER, TaskStage.CHECKING));
        m.put(TaskStage.WAITING_USER, Set.of(TaskStage.EXECUTING, TaskStage.DONE));
        m.put(TaskStage.CHECKING, Set.of(TaskStage.CHECKING, TaskStage.PLANNING, TaskStage.EXECUTING, TaskStage.DONE));
        m.put(TaskStage.DONE, Set.of());
        return Map.copyOf(m);
    }

    /** 这条边合不合法。给调用方一个不抛异常的查询入口（护栏用它决定放不放行） */
    public static boolean canTransition(TaskStage from, TaskStage to) {
        if (from == null || to == null) {
            return false;
        }
        return LEGAL.getOrDefault(from, Set.of()).contains(to);
    }

    /**
     * 迁移到新阶段，顺带刷新时间戳与轮次。
     * <p>
     * 非法流转抛 {@link IllegalStateException} 而不是返回原状态：静默忽略会让
     * 「状态没动」和「迁移被拒」长得一模一样，而调用方几乎总是假设前者。
     * 让它炸出来，栈里就能看见是谁在往一个不可能的边推。
     */
    public TaskState withStage(TaskStage next, int nextRound, long nowMs) {
        if (!canTransition(stage, next)) {
            throw new IllegalStateException(
                    "非法任务阶段流转: " + stage + " -> " + next + " (taskId=" + taskId + ")");
        }
        return new TaskState(taskId, userId, sessionId, coreIntent, currentSubtask,
                pendingTools, completedSteps, contextSnapshot, next, nextRound, nowMs);
    }

    /** 设置当前子任务与上下文快照（执行推进时调用）。不改变阶段 */
    public TaskState withProgress(String subtask, Map<String, Object> snapshot, long nowMs) {
        return new TaskState(taskId, userId, sessionId, coreIntent, subtask,
                pendingTools, completedSteps, snapshot, stage, round, nowMs);
    }

    /** 记一步已完成。去重且保序——恢复时按它跳过重复调用，重复项会让「跳过」判两次 */
    public TaskState withCompleted(String step, long nowMs) {
        if (step == null || step.isBlank()) {
            return this;
        }
        Set<String> merged = new LinkedHashSet<>(completedSteps);
        merged.add(step);
        return new TaskState(taskId, userId, sessionId, coreIntent, currentSubtask,
                pendingTools, new ArrayList<>(merged), contextSnapshot, stage, round, nowMs);
    }

    /** 冻结核心意图。只应在首次规划那一刻调用一次，之后它是恢复时的锚点 */
    public TaskState withCoreIntent(String intent, long nowMs) {
        return new TaskState(taskId, userId, sessionId, intent, currentSubtask,
                pendingTools, completedSteps, contextSnapshot, stage, round, nowMs);
    }

    /** 转入等待用户确认，并记下待执行的调用名 */
    public TaskState await(List<String> tools, int nextRound, long nowMs) {
        return new TaskState(taskId, userId, sessionId, coreIntent, currentSubtask,
                tools, completedSteps, contextSnapshot,
                withStageChecked(TaskStage.WAITING_USER), nextRound, nowMs);
    }

    private TaskStage withStageChecked(TaskStage next) {
        if (!canTransition(stage, next)) {
            throw new IllegalStateException("非法任务阶段流转: " + stage + " -> " + next);
        }
        return next;
    }

    /** 收尾。清空待执行列表——留着它会让「已结束」的任务看起来还有事没做 */
    public TaskState done(long nowMs) {
        return new TaskState(taskId, userId, sessionId, coreIntent, currentSubtask,
                List.of(), completedSteps, contextSnapshot,
                withStageChecked(TaskStage.DONE), round, nowMs);
    }
}