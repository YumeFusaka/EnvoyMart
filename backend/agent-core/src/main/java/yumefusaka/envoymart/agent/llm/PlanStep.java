package yumefusaka.envoymart.agent.llm;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 计划中的一步 —— 由规划阶段产出，执行阶段按它调用工具。
 */
@Data
@Builder(toBuilder = true)
public class PlanStep implements java.io.Serializable {
    private String tool;
    private Map<String, Object> arguments;
    /** 计划编译后绑定的稳定操作标识。模型不得自行伪造，空值由执行器补齐。 */
    private String operationId;
    /** 这一步要达成什么，评估阶段据此判断是否达标 */
    private String reason;
    /** 失败是否可跳过 */
    @Builder.Default
    private boolean optional = false;

    /**
     * 依赖的前置步骤序号（0 起）。
     * <p>
     * 无依赖的步骤会被并发执行；有依赖的等前置完成后再执行。
     * 这是 Plan-and-Execute 相对 ReAct 的结构性优势——ReAct 每步都要看上一步结果，
     * 天然串行。
     */
    @Builder.Default
    private List<Integer> dependsOn = List.of();

    /** 计划编译阶段得到的「字段引用 -> 前置步骤」绑定。仅作观测/校验，不由模型填写。 */
    @Builder.Default
    private Map<String, Integer> stepOutputBindings = Map.of();
}
