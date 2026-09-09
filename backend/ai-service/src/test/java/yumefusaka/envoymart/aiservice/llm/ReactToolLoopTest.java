package yumefusaka.envoymart.aiservice.llm;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import yumefusaka.envoymart.agent.llm.ChatMessage;
import yumefusaka.envoymart.agent.llm.LLMConfig;
import yumefusaka.envoymart.agent.llm.LLMResponse;
import yumefusaka.envoymart.agent.loop.LoopBudget;
import yumefusaka.envoymart.agent.loop.LoopGuard;
import yumefusaka.envoymart.agent.loop.ToolContextKeys;
import yumefusaka.envoymart.agent.tool.Tool;
import yumefusaka.envoymart.agent.tool.ToolCall;
import yumefusaka.envoymart.agent.tool.ToolDefinition;
import yumefusaka.envoymart.agent.tool.ToolRegistry;
import yumefusaka.envoymart.agent.tool.ToolResult;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工具循环是否真的会执行工具 —— 这条是本次改造的核心回归防线。
 * <p>
 * Spring AI 2.0 把工具执行循环从所有 {@code ChatModel} 上移除了，只在 {@code ChatClient}
 * 的 {@code ToolCallingAdvisor} 里保留。直接用 {@code ChatModel.call()} 时，模型返回的
 * tool_call 不会被任何人执行，也<b>不报错</b>——只会得到空内容，最后退化成人畜无害的
 * 「抱歉，我没能完成这个请求」。这类静默失效只能靠"断言工具被调用了"来防。
 */
class ReactToolLoopTest {

    private static final String MODEL = "stub-model";
    private static final String TOOL_NAME = "echo";

    private final AtomicInteger modelCalls = new AtomicInteger();
    private final AtomicReference<String> executed = new AtomicReference<>();

    private final Tool echoTool = new Tool() {
        @Override
        public ToolDefinition getDefinition() {
            return ToolDefinition.builder()
                    .name(TOOL_NAME)
                    .description("回显")
                    .parameters(Map.of())
                    .build();
        }

        @Override
        public ToolResult execute(ToolCall call) {
            executed.set(call.getToolName());
            return ToolResult.builder().success(true).output("工具返回值").build();
        }
    };

    /** 首次返回 tool_call，之后返回最终回答 —— 模拟 ReAct 的一轮往返 */
    private final ChatModel stubModel = new ChatModel() {
        @Override
        public ChatResponse call(Prompt prompt) {
            if (modelCalls.incrementAndGet() == 1) {
                return new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                        .content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", TOOL_NAME, "{}")))
                        .build())));
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage("最终回答"))));
        }

        @Override
        public ChatOptions getOptions() {
            // 与真实模型一致：选项是 ToolCallingChatOptions，工具回调才挂得上去
            return ToolCallingChatOptions.builder().build();
        }
    };

    private SpringAiLLMProvider provider() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(echoTool);
        return new SpringAiLLMProvider(stubModel, registry,
                LLMConfig.builder().model(MODEL).build(), new SimpleMeterRegistry());
    }

    private List<ChatMessage> messages() {
        return List.of(ChatMessage.builder().role(ChatMessage.Role.USER).content("你好").build());
    }

    @Test
    void 带工具循环的调用会执行模型请求的工具并二次调用模型() {
        LLMResponse response = provider().chatWithTools(messages(), LLMConfig.builder().model(MODEL).build(), Map.of());

        assertThat(executed.get())
                .as("模型发出了 tool_call，必须真的有人执行它")
                .isEqualTo(TOOL_NAME);
        assertThat(modelCalls.get())
                .as("执行完工具要把结果回填给模型再问一次，这才是完整的往返")
                .isEqualTo(2);
        assertThat(response.getContent())
                .as("返回的应是工具执行后的最终回答，而不是空内容")
                .isEqualTo("最终回答");
        assertThat(response.getToolExecutions())
                .as("工具轨迹要能回收，否则前端看不到这一段")
                .hasSize(1);
    }

    @Test
    void 单次调用不执行工具() {
        LLMResponse response = provider().chat(messages(), LLMConfig.builder().model(MODEL).build(), Map.of());

        assertThat(modelCalls.get())
                .as("规划、分类、抽取这类内部调用不该驱动工具循环")
                .isEqualTo(1);
        assertThat(executed.get())
                .as("单次调用路径上的 tool_call 没有任何人消费，所以更要确保它不会被执行")
                .isNull();
        assertThat(response.getContent()).isEmpty();
    }

    /**
     * 护栏必须仍然生效于框架驱动的循环。
     * <p>
     * 这是"循环交给框架、边界归我们"的落点：{@code LoopGuard} 随 toolContext 下发，
     * 在 {@code ToolCallback} 的调用点拦截。少了这条，护栏就只是文档里的说法。
     */
    @Test
    void 框架驱动的循环仍受我们的护栏约束() {
        AtomicInteger executions = new AtomicInteger();
        Tool counting = new Tool() {
            @Override
            public ToolDefinition getDefinition() {
                return ToolDefinition.builder().name(TOOL_NAME).description("计数").parameters(Map.of()).build();
            }

            @Override
            public ToolResult execute(ToolCall call) {
                executions.incrementAndGet();
                return ToolResult.builder().success(true).output("ok").build();
            }
        };
        ToolRegistry registry = new ToolRegistry();
        registry.register(counting);

        // 前两次都要求调用工具，第三次给最终回答
        AtomicInteger calls = new AtomicInteger();
        ChatModel greedy = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                if (calls.incrementAndGet() <= 2) {
                    return new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                            .content("")
                            .toolCalls(List.of(new AssistantMessage.ToolCall("c" + calls.get(), "function", TOOL_NAME, "{}")))
                            .build())));
                }
                return new ChatResponse(List.of(new Generation(new AssistantMessage("结束"))));
            }

            @Override
            public ChatOptions getOptions() {
                return ToolCallingChatOptions.builder().build();
            }
        };

        SpringAiLLMProvider provider = new SpringAiLLMProvider(greedy, registry,
                LLMConfig.builder().model(MODEL).build(), new SimpleMeterRegistry());
        // 预算：整轮只允许 1 次工具调用
        LoopGuard guard = new LoopGuard(new LoopBudget(1, 2, 2));

        provider.chatWithTools(messages(), LLMConfig.builder().model(MODEL).build(),
                Map.of(ToolContextKeys.LOOP_GUARD, guard));

        assertThat(executions.get())
                .as("第二次工具调用必须被护栏拦下，否则预算形同虚设")
                .isEqualTo(1);
        assertThat(guard.getStopReason()).contains("工具调用总数");
    }
}
