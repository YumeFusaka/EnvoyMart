package yumefusaka.envoymart.agent.core;

import org.junit.jupiter.api.Test;
import yumefusaka.envoymart.agent.llm.ChatMessage;
import yumefusaka.envoymart.agent.llm.LLMConfig;
import yumefusaka.envoymart.agent.llm.LLMProvider;
import yumefusaka.envoymart.agent.llm.LLMResponse;
import yumefusaka.envoymart.agent.tool.Tool;
import yumefusaka.envoymart.agent.tool.ToolCall;
import yumefusaka.envoymart.agent.tool.ToolDefinition;
import yumefusaka.envoymart.agent.tool.ToolRegistry;
import yumefusaka.envoymart.agent.tool.ToolResult;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReActEngineTest {

    private static ToolRegistry echoRegistry() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new Tool() {
            @Override
            public ToolDefinition getDefinition() {
                return ToolDefinition.builder().name("echo").description("回显").parameters(Map.of()).build();
            }

            @Override
            public ToolResult execute(ToolCall call) {
                return ToolResult.builder().success(true).output("echo-ok").build();
            }
        });
        return registry;
    }

    /** 永远请求同一个工具调用的模型，模拟原地打转。 */
    private static LLMProvider loopingProvider() {
        return new LLMProvider() {
            @Override
            public LLMResponse chat(List<ChatMessage> messages, LLMConfig config) {
                return LLMResponse.builder()
                        .content("再查一次")
                        .toolCalls(List.of(ChatMessage.ToolCallRequest.builder()
                                .id("call-1")
                                .name("echo")
                                .arguments(Map.of("q", "同一个参数"))
                                .build()))
                        .finishReason(LLMResponse.FinishReason.TOOL_CALL)
                        .build();
            }
        };
    }

    @Test
    void 重复调用同一工具会被判定为死循环并中止() {
        ReActEngine engine = new ReActEngine(
                loopingProvider(), LLMConfig.builder().model("stub").build(), echoRegistry(), 10);

        var result = engine.execute("系统提示", List.of());

        assertThat(result.getFinalAnswer()).contains("重复操作");
        assertThat(result.getTotalIterations()).isLessThan(10);
    }
}
