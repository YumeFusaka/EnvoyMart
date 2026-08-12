package yumefusaka.envoymart.agent.core;

import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import yumefusaka.envoymart.agent.llm.ChatMessage;
import yumefusaka.envoymart.agent.llm.LLMConfig;
import yumefusaka.envoymart.agent.llm.LLMProvider;
import yumefusaka.envoymart.agent.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.List;

/**
 * PAE（Plan-Act-Evaluate）引擎 —— 先规划再逐步执行。
 * <p>
 * 相比 ReAct 的"边想边做"，PAE 先完整规划步骤，再依次执行并按计划评估偏差。
 * 适用于复杂多步骤任务（如多商品比价下单流程）。
 * <p>
 * 三个阶段：
 * 1. Plan：LLM 根据用户需求生成步骤计划
 * 2. Act：依次执行计划中的每一步（工具调用或子任务）
 * 3. Evaluate：每步执行后评估结果是否符合预期，必要时修正计划
 */
@Slf4j
public class PAEEngine {

    private final LLMProvider llmProvider;
    private final LLMConfig llmConfig;
    private final ToolRegistry toolRegistry;
    private final int maxSteps;

    public PAEEngine(LLMProvider llmProvider, LLMConfig llmConfig,
                     ToolRegistry toolRegistry, int maxSteps) {
        this.llmProvider = llmProvider;
        this.llmConfig = llmConfig;
        this.toolRegistry = toolRegistry;
        this.maxSteps = maxSteps;
    }

    /**
     * 执行 PAE 循环。
     */
    public PAEResult execute(String userMessage, List<ChatMessage> context) {
        List<PAEStep> steps = new ArrayList<>();

        // Phase 1: Plan
        List<PlanItem> plan = generatePlan(userMessage, context);
        log.info("[PAE] plan generated: {} steps", plan.size());

        // Phase 2 & 3: Act & Evaluate
        for (int i = 0; i < Math.min(plan.size(), maxSteps); i++) {
            PlanItem item = plan.get(i);
            log.debug("[PAE] executing step {}/{}: {}", i + 1, plan.size(), item.getAction());

            var toolResult = toolRegistry.execute(new yumefusaka.envoymart.agent.tool.ToolCall(
                    "pae_" + i, item.getAction(), item.getArguments()));

            // Evaluate
            String evaluation = evaluateStep(item, toolResult);

            steps.add(PAEStep.builder()
                    .stepIndex(i)
                    .action(item.getAction())
                    .expectedOutcome(item.getExpectedOutcome())
                    .result(toolResult.getOutput())
                    .evaluation(evaluation)
                    .success(toolResult.isSuccess())
                    .build());

            if (!toolResult.isSuccess() && !item.isOptional()) {
                log.warn("[PAE] step {} failed, aborting plan", i);
                break;
            }
        }

        // 汇总
        StringBuilder finalAnswer = new StringBuilder("执行完成。\n");
        for (PAEStep step : steps) {
            finalAnswer.append("- ").append(step.getAction())
                    .append(": ").append(step.isSuccess() ? "成功" : "失败")
                    .append("\n");
        }

        return PAEResult.builder()
                .finalAnswer(finalAnswer.toString())
                .steps(steps)
                .plan(plan)
                .build();
    }

    @SuppressWarnings("unchecked")
    private List<PlanItem> generatePlan(String userMessage, List<ChatMessage> context) {
        // 规则化规划逻辑，后续可替换为 LLM 生成结构化 Plan
        List<PlanItem> plan = new ArrayList<>();
        if (userMessage.contains("买") || userMessage.contains("下单")) {
            plan.add(PlanItem.builder().action("product_search").expectedOutcome("找到目标商品").build());
            plan.add(PlanItem.builder().action("check_stock").expectedOutcome("确认库存充足").build());
            plan.add(PlanItem.builder().action("create_order").expectedOutcome("下单成功").build());
            plan.add(PlanItem.builder().action("check_logistics").expectedOutcome("确认物流信息").optional(true).build());
        } else {
            plan.add(PlanItem.builder().action("knowledge_retrieval").expectedOutcome("检索相关知识").build());
            plan.add(PlanItem.builder().action("product_recommend").expectedOutcome("推荐商品").build());
        }
        return plan;
    }

    private String evaluateStep(PlanItem plan, yumefusaka.envoymart.agent.tool.ToolResult result) {
        if (result.isSuccess()) return "符合预期";
        return "执行异常：" + result.getErrorMessage();
    }

    @Data
    @Builder
    public static class PlanItem {
        private String action;
        private String expectedOutcome;
        @Builder.Default private boolean optional = false;
        private java.util.Map<String, Object> arguments;
    }

    @Data
    @Builder
    public static class PAEStep {
        private int stepIndex;
        private String action;
        private String expectedOutcome;
        private String result;
        private String evaluation;
        private boolean success;
    }

    @Data
    @Builder
    public static class PAEResult {
        private String finalAnswer;
        private List<PAEStep> steps;
        private List<PlanItem> plan;
    }
}
