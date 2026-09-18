package yumefusaka.envoymart.aiservice.llm;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
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
 * 工具循环是否真的会执行工具 —— 这条是迁移的核心回归防线。
 * <p>
 * 迁到 LangChain4j 后循环由我们自己写，失败模式与在 Spring AI 下相反：那时要防的是
 * 「框架的 advisor 没挂上，tool_call 静默没人消费」；现在要防的是「循环写漏了某一步」——
 * 少执行一次工具、少回填一次结果、或者护栏判定挪出了循环，都会表现为模型答非所问，
 * 而不是报错。这类静默失效只能靠"断言工具被调用了"来防。
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
        public ChatResponse chat(ChatRequest request) {
            if (modelCalls.incrementAndGet() == 1) {
                return ChatResponse.builder()
                        .aiMessage(AiMessage.from("", List.of(ToolExecutionRequest.builder()
                                .id("call-1").name(TOOL_NAME).arguments("{}").build())))
                        .build();
            }
            return ChatResponse.builder().aiMessage(AiMessage.from("最终回答")).build();
        }
    };

    private LangChain4jLLMProvider provider(ChatModel model) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(echoTool);
        return new LangChain4jLLMProvider(model, null, registry,
                LLMConfig.builder().model(MODEL).build(), new SimpleMeterRegistry());
    }

    private List<ChatMessage> messages() {
        return List.of(ChatMessage.builder().role(ChatMessage.Role.USER).content("你好").build());
    }

    @Test
    void 带工具循环的调用会执行模型请求的工具并二次调用模型() {
        LLMResponse response = provider(stubModel)
                .chatWithTools(messages(), LLMConfig.builder().model(MODEL).build(), Map.of());

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
        LLMResponse response = provider(stubModel)
                .chat(messages(), LLMConfig.builder().model(MODEL).build(), Map.of());

        assertThat(modelCalls.get())
                .as("规划、分类、抽取这类内部调用不该驱动工具循环")
                .isEqualTo(1);
        assertThat(executed.get())
                .as("单次调用路径上不下发工具定义，也绝不该执行它")
                .isNull();
        assertThat(response.getContent()).isEmpty();
    }

    /**
     * 护栏必须对工具循环生效。
     * <p>
     * 迁移前护栏要经 toolContext 下发到框架的 ToolCallback 里才能生效（那时它是"送进框架的"
     * 一件外物）；现在它就在循环体内。这条测试守的是迁完仍然生效——少了它，
     * 「预算」就只是文档里的说法。
     */
    @Test
    void 工具循环仍受我们的护栏约束() {
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
            public ChatResponse chat(ChatRequest request) {
                if (calls.incrementAndGet() <= 2) {
                    return ChatResponse.builder()
                            .aiMessage(AiMessage.from("", List.of(ToolExecutionRequest.builder()
                                    .id("c" + calls.get()).name(TOOL_NAME).arguments("{}").build())))
                            .build();
                }
                return ChatResponse.builder().aiMessage(AiMessage.from("结束")).build();
            }
        };

        LangChain4jLLMProvider provider = new LangChain4jLLMProvider(greedy, null, registry,
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
