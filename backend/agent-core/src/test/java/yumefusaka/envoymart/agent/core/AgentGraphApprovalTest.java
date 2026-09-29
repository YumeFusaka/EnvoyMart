package yumefusaka.envoymart.agent.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import yumefusaka.envoymart.agent.llm.ChatMessage;
import yumefusaka.envoymart.agent.llm.LLMConfig;
import yumefusaka.envoymart.agent.llm.LLMProvider;
import yumefusaka.envoymart.agent.llm.LLMResponse;
import yumefusaka.envoymart.agent.llm.PlanStep;
import yumefusaka.envoymart.agent.loop.LoopBudget;
import yumefusaka.envoymart.agent.loop.LoopGuard;
import yumefusaka.envoymart.agent.tool.Tool;
import yumefusaka.envoymart.agent.tool.ToolCall;
import yumefusaka.envoymart.agent.tool.ToolDefinition;
import yumefusaka.envoymart.agent.tool.ToolRegistry;
import yumefusaka.envoymart.agent.tool.ToolResult;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 高危操作的中断出口：<b>未确认时必须停下来，且要让用户看清停的是什么。</b>
 * <p>
 * 这条链路原先只有前半截——图确实会中断，但返回给调用方的是工具名 {@code order_cancel}
 * 三个字。用户看不到要取消的是哪一单，这个「确认」就只是走个形式；
 * 前端也无从渲染出一张有内容的确认卡片。
 * <p>
 * 另一半同样要守：<b>确认之后必须真的执行</b>。中断出口如果连确认路径也堵死，
 * 用户就永远取消不掉订单——一个自称支持高危确认、实则不可用的功能，
 * 比没有这个功能更糟。
 */
class AgentGraphApprovalTest {

    private static final LLMConfig CONFIG = LLMConfig.builder().model("stub").build();

    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final AtomicInteger cancellations = new AtomicInteger();

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    /** 只产出一个「取消订单 12」的高危步骤 */
    private static final class StubProvider implements LLMProvider {

        @Override
        public LLMResponse chat(List<ChatMessage> messages, LLMConfig config) {
            return LLMResponse.builder().content("好的").build();
        }

        @Override
        public LLMResponse chatWithTools(List<ChatMessage> messages, LLMConfig config,
                                         Map<String, Object> toolContext) {
            return LLMResponse.builder().content("好的").build();
        }

        @Override
        public List<PlanStep> plan(String userMessage, List<ToolDefinition> availableTools, String context) {
            return List.of(PlanStep.builder()
                    .tool("order_cancel")
                    .arguments(Map.of("orderId", 12))
                    .reason("帮用户取消订单")
                    .build());
        }
    }

    private Tool cancelTool() {
        return new Tool() {
            @Override
            public ToolDefinition getDefinition() {
                return ToolDefinition.builder()
                        .name("order_cancel")
                        .description("取消未支付的订单。不可撤销，需要用户确认。")
                        .requiresConfirmation(true)
                        .parameters(Map.of())
                        .build();
            }

            @Override
            public ToolResult execute(ToolCall call) {
                cancellations.incrementAndGet();
                return ToolResult.builder().success(true).output("订单已取消").build();
            }
        };
    }

    private AgentGraph.GraphResult run(boolean approved) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(cancelTool());
        AgentGraph graph = new AgentGraph(new StubProvider(), CONFIG, registry, executor);
        return graph.run("u1", "帮我取消订单 12", "", List.of(), approved,
                new LoopGuard(new LoopBudget(8, 2, 2)), null);
    }

    @Test
    void 未确认时中断并给出带参数的操作描述() {
        AgentGraph.GraphResult result = run(false);

        assertThat(result.getPendingApproval())
                .as("要给的是「取消哪一单」，只回一个工具名等于让用户盲签")
                .containsExactly("order_cancel(orderId=12)");
        assertThat(cancellations.get())
                .as("中断必须发生在调用工具之前")
                .isZero();
        assertThat(result.getAnswer())
                .as("图停在 END，没走 answer 节点 —— 此时没有回答可给，"
                        + "调用方应当把 pendingApproval 渲染成确认提示")
                .isNull();
    }

    @Test
    void 确认后高危工具真的执行() {
        AgentGraph.GraphResult result = run(true);

        assertThat(cancellations.get())
                .as("确认路径不通的话，用户永远取消不掉订单")
                .isEqualTo(1);
        assertThat(result.getPendingApproval())
                .as("已经执行完，不该再要求一次确认")
                .isNull();
        assertThat(result.getSteps()).hasSize(1);
    }

    @Test
    void 描述里的参数按key排序() {
        PlanStep step = PlanStep.builder()
                .tool("order_cancel")
                .arguments(Map.of("orderId", 12, "reason", "用户要求"))
                .build();

        assertThat(AgentGraph.describe(step))
                .as("顺序不固定的话，同一份计划每次拼出的文本都不同，日志对比与断言都得先做集合比较")
                .isEqualTo("order_cancel(orderId=12, reason=用户要求)");
    }

    @Test
    void 没有参数时退化为工具名() {
        PlanStep bare = PlanStep.builder().tool("order_cancel").build();

        assertThat(AgentGraph.describe(bare)).isEqualTo("order_cancel");
        assertThat(AgentGraph.describe(PlanStep.builder()
                .tool("order_cancel").arguments(Map.of()).build()))
                .isEqualTo("order_cancel");
    }
}
