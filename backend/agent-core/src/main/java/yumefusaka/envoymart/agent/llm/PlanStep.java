package yumefusaka.envoymart.agent.llm;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * 多步执行计划中的一步 —— 由 LLM 规划，指向一个已注册的工具。
 */
@Data
@Builder
public class PlanStep {

    /** 工具名，必须来自已注册工具清单 */
    private String tool;
    /** 工具入参 */
    private Map<String, Object> arguments;
    /** 这一步要达成什么，便于评估与排障 */
    private String reason;
    /** 可选步骤：失败时不终止整个计划 */
    @Builder.Default
    private boolean optional = false;
}
