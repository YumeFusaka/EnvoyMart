package yumefusaka.envoymart.agent.core;

import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import yumefusaka.envoymart.agent.llm.ChatMessage;
import yumefusaka.envoymart.agent.llm.LLMConfig;
import yumefusaka.envoymart.agent.llm.LLMProvider;
import yumefusaka.envoymart.agent.llm.PlanStep;
import yumefusaka.envoymart.agent.llm.ToolExecution;
import yumefusaka.envoymart.agent.tool.ToolDefinition;
import yumefusaka.envoymart.agent.tool.ToolRegistry;
import yumefusaka.envoymart.agent.tool.ToolResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * PAE（Plan-Act-Evaluate）引擎 —— 先规划再逐步执行。
 * <p>
 * 相比 ReAct 的"边想边做"，PAE 先完整规划步骤，再依次执行并按计划评估偏差。
 * 适用于复杂多步骤任务（如多商品比价下单流程）。
 * <p>
 * 三个阶段：
 * 1. Plan：LLM 基于「已注册工具清单」生成步骤计划
 * 2. Act：依次执行计划中的每一步
 * 3. Evaluate：每步执行后评估结果是否符合预期，必要时终止或跳过可选步骤
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
     * 执行 PAE 循环：自行生成计划。
     */
    public PAEResult execute(String userMessage, List<ChatMessage> context) {
        return execute(userMessage, context, generatePlan(userMessage, null), null, false);
    }

    public PAEResult execute(String userMessage, List<ChatMessage> context,
                             List<PlanStep> plan, String systemPrompt) {
        return execute(userMessage, context, plan, systemPrompt, false);
    }

    /**
     * 执行 PAE 循环：使用调用方给定的计划（避免重复规划）。
     *
     * @param systemPrompt 含 RAG 知识与用户长期记忆的系统提示词，用于最终回答合成
     * @param approved     用户是否已确认高危操作
     */
    public PAEResult execute(String userMessage, List<ChatMessage> context,
                             List<PlanStep> plan, String systemPrompt, boolean approved) {
        if (plan == null) {
            plan = List.of();
        }
        List<PAEStep> steps = new ArrayList<>();
        List<ToolExecution> executions = new ArrayList<>();

        // Phase 1: Plan —— 只允许引用已注册工具
        log.info("[PAE] plan generated: {} steps -> {}", plan.size(),
                plan.stream().map(PlanStep::getTool).toList());

        if (plan.isEmpty()) {
            return PAEResult.builder()
                    .finalAnswer("没有可用的工具能完成这个请求，请换一种问法或直接描述你的需求。")
                    .steps(steps)
                    .plan(plan)
                    .toolExecutions(executions)
                    .build();
        }

        // Phase 2 & 3: Act & Evaluate
        for (int i = 0; i < Math.min(plan.size(), maxSteps); i++) {
            PlanStep item = plan.get(i);
            log.debug("[PAE] executing step {}/{}: {}", i + 1, plan.size(), item.getTool());

            ToolResult toolResult = toolRegistry.execute(new yumefusaka.envoymart.agent.tool.ToolCall(
                    "pae_" + i, item.getTool(),
                    item.getArguments() == null ? Map.of() : item.getArguments(), approved));

            String observation = toolResult.isSuccess()
                    ? toolResult.getOutput()
                    : "工具执行失败: " + toolResult.getErrorMessage();

            executions.add(ToolExecution.builder()
                    .tool(item.getTool())
                    .input(String.valueOf(item.getArguments()))
                    .output(observation)
                    .success(toolResult.isSuccess())
                    .rawData(toolResult.getRawData())
                    .build());

            steps.add(PAEStep.builder()
                    .stepIndex(i)
                    .action(item.getTool())
                    .expectedOutcome(item.getReason())
                    .result(observation)
                    .evaluation(toolResult.isSuccess() ? "符合预期" : "执行异常：" + toolResult.getErrorMessage())
                    .success(toolResult.isSuccess())
                    .build());

            if (!toolResult.isSuccess() && !item.isOptional()) {
                log.warn("[PAE] step {} failed, aborting plan", i);
                break;
            }
        }

        // Phase 4: Answer —— 把工具结果交给 LLM 组织成自然语言回复
        String finalAnswer = synthesize(userMessage, steps, systemPrompt);

        return PAEResult.builder()
                .finalAnswer(finalAnswer)
                .steps(steps)
                .plan(plan)
                .toolExecutions(executions)
                .build();
    }

    /**
     * 用工具执行结果合成最终回答。
     * 合成失败时退回到结构化摘要，保证用户至少能看到执行结果。
     */
    private String synthesize(String userMessage, List<PAEStep> steps, String systemPrompt) {
        if (steps.isEmpty()) {
            return "抱歉，我没能完成这个请求。";
        }

        StringBuilder observations = new StringBuilder();
        for (PAEStep step : steps) {
            observations.append("【").append(step.getAction()).append("】\n")
                    .append(step.getResult()).append("\n\n");
        }

        List<ChatMessage> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(ChatMessage.builder().role(ChatMessage.Role.SYSTEM)
                    .content(systemPrompt).build());
        }
        messages.add(ChatMessage.builder().role(ChatMessage.Role.SYSTEM)
                .content("下面是刚查到的真实数据，请基于它用自然、简洁的中文回答用户，不要编造数据。")
                .build());
        messages.add(ChatMessage.builder().role(ChatMessage.Role.USER)
                .content("用户问：" + userMessage + "\n\n查询结果：\n" + observations)
                .build());

        try {
            String answer = llmProvider.chat(messages, llmConfig).getContent();
            if (answer != null && !answer.isBlank()) {
                return answer;
            }
        } catch (Exception e) {
            log.warn("[PAE] answer synthesis failed: {}", e.getMessage());
        }

        StringBuilder fallback = new StringBuilder("执行完成。\n");
        for (PAEStep step : steps) {
            fallback.append("- ").append(step.getAction())
                    .append(": ").append(step.isSuccess() ? "成功" : "失败")
                    .append("\n");
        }
        return fallback.toString();
    }

    /**
     * 生成计划：优先由 LLM 规划，失败或为空时回退到关键词规则。
     * 两条路径都会过滤掉未注册的工具，避免执行必然失败的计划。
     */
    private List<PlanStep> generatePlan(String userMessage, String systemPrompt) {
        List<ToolDefinition> available = toolRegistry.listDefinitions();

        List<PlanStep> llmPlan = llmProvider.plan(userMessage, available, systemPrompt);
        if (llmPlan != null && !llmPlan.isEmpty()) {
            List<PlanStep> valid = llmPlan.stream()
                    .filter(step -> toolRegistry.get(step.getTool()).isPresent())
                    .toList();
            if (valid.size() != llmPlan.size()) {
                log.warn("[PAE] LLM plan referenced unknown tools, dropped {} step(s)",
                        llmPlan.size() - valid.size());
            }
            if (!valid.isEmpty()) {
                return valid;
            }
        }

        return keywordPlan(userMessage, available);
    }

    /**
     * 规则兜底：按关键词匹配已注册工具。
     * 只产出「参数能凑齐」的步骤——凑不齐必填参数的计划只会白白失败。
     */
    private List<PlanStep> keywordPlan(String userMessage, List<ToolDefinition> available) {
        List<PlanStep> plan = new ArrayList<>();
        String text = userMessage == null ? "" : userMessage;

        if ((text.contains("订单") || text.contains("物流") || text.contains("快递"))
                && has(available, "logistics_query")) {
            Long orderId = extractNumber(text);
            if (orderId != null) {
                plan.add(PlanStep.builder().tool("logistics_query")
                        .arguments(Map.of("orderId", orderId))
                        .reason("查询订单物流轨迹").build());
            } else {
                log.info("[PAE] 缺少订单号，跳过 logistics_query");
            }
        }
        if ((text.contains("推荐") || text.contains("买") || text.contains("商品") || text.contains("比价"))
                && has(available, "product_search")) {
            plan.add(PlanStep.builder().tool("product_search")
                    .arguments(Map.of("query", text))
                    .reason("按需求检索候选商品").build());
        }
        return plan;
    }

    /** 从文本里抽取第一串数字，用于识别订单号。 */
    private Long extractNumber(String text) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\d{1,19}").matcher(text);
        if (!matcher.find()) {
            return null;
        }
        try {
            return Long.valueOf(matcher.group());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private boolean has(List<ToolDefinition> available, String toolName) {
        return available.stream().anyMatch(d -> d.getName().equals(toolName));
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
        private List<PlanStep> plan;
        private List<ToolExecution> toolExecutions;
    }
}
