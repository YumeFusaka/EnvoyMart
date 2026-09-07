package yumefusaka.envoymart.agent.llm;

import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * Mock 实现 —— 未配置模型 Key 时的占位，不调用真实 LLM。
 * <p>
 * <b>只回一句固定的提示，不回显任何输入</b>。早先的实现把 system prompt 原样拼进回复，
 * 而 system prompt 里含 RAG 知识与长期记忆——等于在无 Key 部署下把内部上下文，
 * 包括其他用户沉淀的记忆，直接读给用户。降级路径必须"功能变弱"，不能"变成泄漏"。
 * <p>
 * 它也不假装在工作：明确告诉调用方当前没接模型，避免把占位回复误当成真实回答。
 */
@Slf4j
public class MockLLMProvider implements LLMProvider {

    private static final String NOT_CONFIGURED_REPLY =
            "智能助手尚未接入模型（未配置 LLM_API_KEY），当前只能作为链路占位。";

    @Override
    public boolean supportsReasoning() {
        return false;
    }

    @Override
    public LLMResponse chat(List<ChatMessage> messages, LLMConfig config) {
        log.warn("[MockLLM] 未配置模型 Key，返回占位回复。设 LLM_API_KEY 后启用真实模型。");
        return LLMResponse.builder()
                .content(NOT_CONFIGURED_REPLY)
                .finishReason(LLMResponse.FinishReason.STOP)
                .promptTokens(0)
                .completionTokens(0)
                .build();
    }
}
