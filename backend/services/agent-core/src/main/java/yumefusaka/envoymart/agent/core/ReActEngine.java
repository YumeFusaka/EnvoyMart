package yumefusaka.envoymart.agent.core;

import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import yumefusaka.envoymart.agent.llm.ChatMessage;
import yumefusaka.envoymart.agent.llm.LLMConfig;
import yumefusaka.envoymart.agent.llm.LLMProvider;
import yumefusaka.envoymart.agent.llm.LLMResponse;
import yumefusaka.envoymart.agent.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.List;

/**
 * ReAct 循环引擎 —— Thought → Action → Observation 的迭代推理执行。
 * <p>
 * 每轮循环：
 * 1. Thought（思考）：LLM 分析当前状态，决定下一步 Action
 * 2. Action（行动）：调用工具或生成最终回答
 * 3. Observation（观察）：将工具执行结果注入上下文，进入下一轮思考
 * <p>
 * 循环直到 LLM 生成 STOP 信号或达到最大迭代次数。
 */
@Slf4j
public class ReActEngine {

    private final LLMProvider llmProvider;
    private final LLMConfig llmConfig;
    private final ToolRegistry toolRegistry;
    private final int maxIterations;

    public ReActEngine(LLMProvider llmProvider, LLMConfig llmConfig,
                       ToolRegistry toolRegistry, int maxIterations) {
        this.llmProvider = llmProvider;
        this.llmConfig = llmConfig;
        this.toolRegistry = toolRegistry;
        this.maxIterations = maxIterations;
    }

    /**
     * 执行 ReAct 循环，返回最终回答。
     */
    public ReActResult execute(String systemPrompt, List<ChatMessage> conversation) {
        List<ChatMessage> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            messages.add(ChatMessage.builder().role(ChatMessage.Role.SYSTEM).content(systemPrompt).build());
        }
        messages.addAll(conversation);

        List<ReActStep> steps = new ArrayList<>();
        int iteration = 0;

        while (iteration < maxIterations) {
            iteration++;
            log.debug("[ReAct] iteration {}/{}", iteration, maxIterations);

            LLMResponse response = llmProvider.chat(messages, llmConfig);

            // 如果 LLM 返回了工具调用请求
            if (response.getToolCalls() != null && !response.getToolCalls().isEmpty()) {
                for (ChatMessage.ToolCallRequest toolReq : response.getToolCalls()) {
                    // Thought 阶段隐含在 LLM 的响应中
                    String thought = response.getContent() != null ? response.getContent() : "";

                    // Action：执行工具（捕获异常防止中断推理循环）
                    String observation;
                    try {
                        var toolResult = toolRegistry.execute(new yumefusaka.envoymart.agent.tool.ToolCall(
                                toolReq.getId(), toolReq.getName(), toolReq.getArguments()));
                        observation = toolResult.getOutput() != null ? toolResult.getOutput() : "工具执行成功但无返回内容";
                    } catch (Exception e) {
                        log.warn("[ReAct] tool {} execution failed: {}", toolReq.getName(), e.getMessage());
                        observation = "工具执行异常: " + e.getMessage();
                    }

                    steps.add(ReActStep.builder()
                            .thought(thought)
                            .action(toolReq.getName())
                            .actionInput(String.valueOf(toolReq.getArguments()))
                            .observation(observation)
                            .build());

                    // Observation：将结果注入上下文
                    messages.add(ChatMessage.builder()
                            .role(ChatMessage.Role.ASSISTANT)
                            .content(thought)
                            .toolCall(toolReq)
                            .build());
                    messages.add(ChatMessage.builder()
                            .role(ChatMessage.Role.TOOL)
                            .content(observation)
                            .toolResult(observation)
                            .build());
                }
            } else {
                // 最终回答
                steps.add(ReActStep.builder()
                        .thought(response.getContent())
                        .action("final_answer")
                        .observation("")
                        .build());

                return ReActResult.builder()
                        .finalAnswer(response.getContent())
                        .steps(steps)
                        .totalIterations(iteration)
                        .build();
            }
        }

        // 达到最大迭代次数
        return ReActResult.builder()
                .finalAnswer("已达最大推理次数，当前未能给出完整回答。")
                .steps(steps)
                .totalIterations(iteration)
                .build();
    }

    @Data
    @Builder
    public static class ReActStep {
        private String thought;
        private String action;
        private String actionInput;
        private String observation;
    }

    @Data
    @Builder
    public static class ReActResult {
        private String finalAnswer;
        private List<ReActStep> steps;
        private int totalIterations;
    }
}
