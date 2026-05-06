package yumefusaka.envoymart.agent.llm;

import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Mock 实现 —— 不调用真实 LLM，仅回显最近一条用户消息。
 * 切换至 OpenaiLLMProvider 即可接入真实模型。
 */
@Slf4j
public class MockLLMProvider implements LLMProvider {

    @Override
    public LLMResponse chat(List<ChatMessage> messages, LLMConfig config) {
        String lastUser = messages.stream()
                .filter(m -> m.getRole() == ChatMessage.Role.USER)
                .reduce((first, second) -> second)
                .map(ChatMessage::getContent)
                .orElse("");

        String knowledge = messages.stream()
                .filter(m -> m.getRole() == ChatMessage.Role.SYSTEM)
                .map(ChatMessage::getContent)
                .collect(Collectors.joining("; "));

        log.debug("[MockLLM] userMsg={}, knowledge={}", lastUser, knowledge);

        return LLMResponse.builder()
                .content("已收到你的消息。结合已有信息，" + (knowledge.isEmpty() ? "暂无相关知识匹配。" : "可参考：" + knowledge))
                .finishReason(LLMResponse.FinishReason.STOP)
                .promptTokens(0)
                .completionTokens(0)
                .build();
    }
}
