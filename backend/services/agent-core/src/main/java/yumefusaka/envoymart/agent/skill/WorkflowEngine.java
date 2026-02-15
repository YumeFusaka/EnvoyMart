package yumefusaka.envoymart.agent.skill;

import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * 工作流引擎 —— 按定义顺序执行步骤，支持可选步骤的失败容错。
 */
@Slf4j
public class WorkflowEngine {

    private final SkillRegistry skillRegistry;

    public WorkflowEngine(SkillRegistry skillRegistry) {
        this.skillRegistry = skillRegistry;
    }

    /**
     * 执行完整工作流，返回每一步的结果。
     */
    public Map<String, SkillResult> execute(Workflow workflow, SkillContext baseContext) {
        Map<String, SkillResult> results = new LinkedHashMap<>();
        SkillContext currentContext = baseContext;

        for (WorkflowStep step : workflow.getSteps()) {
            Optional<Skill> skillOpt = skillRegistry.get(step.getSkillName());
            if (skillOpt.isEmpty()) {
                log.warn("[Workflow] step {} skill {} not found", step.getName(), step.getSkillName());
                if (!step.isOptional()) {
                    results.put(step.getName(), SkillResult.builder()
                            .success(false).output("Skill not found: " + step.getSkillName()).build());
                    return results;
                }
                continue;
            }

            try {
                SkillResult result = skillOpt.get().execute(currentContext);
                results.put(step.getName(), result);
                // 将上一步输出注入下一步上下文（简化处理）
                if (result.getData() != null) {
                    Map<String, Object> newParams = new HashMap<>(currentContext.getParameters());
                    newParams.put(step.getName() + "_result", result.getData());
                    currentContext = SkillContext.builder()
                            .userId(currentContext.getUserId())
                            .sessionId(currentContext.getSessionId())
                            .userMessage(currentContext.getUserMessage())
                            .parameters(newParams)
                            .shortTermMemory(currentContext.getShortTermMemory())
                            .longTermMemory(currentContext.getLongTermMemory())
                            .toolRegistry(currentContext.getToolRegistry())
                            .ragEngine(currentContext.getRagEngine())
                            .build();
                }
            } catch (Exception e) {
                log.error("[Workflow] step {} failed", step.getName(), e);
                if (!step.isOptional()) {
                    results.put(step.getName(), SkillResult.builder()
                            .success(false).output("Error: " + e.getMessage()).build());
                    return results;
                }
            }
        }

        return results;
    }
}
