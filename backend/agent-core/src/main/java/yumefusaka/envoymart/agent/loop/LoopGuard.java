package yumefusaka.envoymart.agent.loop;

import lombok.Getter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 循环护栏 —— 一次请求一份，管住 Agent 的两类循环。
 * <p>
 * 这里的设计要点是<b>统一</b>：
 * <ul>
 *   <li>执行图里的 ACT → EVALUATE → REPLAN 环，由节点直接调用；</li>
 *   <li>ReAct 的工具循环（由框架驱动），通过 Spring AI 的 toolContext
 *       把同一个护栏传进 ToolCallback，在调用点拦截。</li>
 * </ul>
 * 循环本身交给框架跑，但<b>循环的边界与终止条件归我们</b>——
 * 这样两种循环的"最多花多少"是同一套账。
 */
public class LoopGuard {

    private final LoopBudget budget;
    private final Map<String, Integer> actionCounts = new ConcurrentHashMap<>();
    private final AtomicInteger toolCalls = new AtomicInteger();
    private final AtomicInteger planRounds = new AtomicInteger();

    @Getter
    private volatile String stopReason;

    public LoopGuard() {
        this(LoopBudget.defaults());
    }

    public LoopGuard(LoopBudget budget) {
        this.budget = budget;
    }

    /**
     * 工具调用前的准入判断：检查总预算与重复调用。
     *
     * @return false 表示已超预算，调用方应拒绝执行并让模型基于已有信息作答
     */
    public synchronized boolean allowToolCall(String tool, Map<String, Object> arguments) {
        if (stopReason != null) {
            return false;
        }
        if (toolCalls.incrementAndGet() > budget.maxToolCalls()) {
            stopReason = "工具调用总数已达上限 " + budget.maxToolCalls();
            return false;
        }
        String key = tool + "|" + canonical(arguments);
        int times = actionCounts.merge(key, 1, Integer::sum);
        if (times > budget.maxRepeatedAction()) {
            stopReason = "同一操作重复调用已达上限（" + tool + "）";
            return false;
        }
        return true;
    }

    /** 进入新一轮规划前的准入判断。 */
    public synchronized boolean allowPlanRound() {
        if (stopReason != null) {
            return false;
        }
        if (planRounds.incrementAndGet() > budget.maxPlanRounds()) {
            stopReason = "规划轮次已达上限 " + budget.maxPlanRounds();
            return false;
        }
        return true;
    }

    public boolean isExhausted() {
        return stopReason != null;
    }

    public int toolCalls() {
        return toolCalls.get();
    }

    public int planRounds() {
        return planRounds.get();
    }

    /** 用于日志与可观测：一次请求的循环消耗摘要。 */
    public String summary() {
        return "toolCalls=" + toolCalls.get() + "/" + budget.maxToolCalls()
                + " planRounds=" + planRounds.get() + "/" + budget.maxPlanRounds()
                + (stopReason == null ? "" : " stoppedBy=[" + stopReason + "]");
    }

    /** 参数字典序拼接，保证「同一参数」的判定不受 Map 顺序影响。 */
    private String canonical(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return "";
        }
        return arguments.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(","));
    }
}
