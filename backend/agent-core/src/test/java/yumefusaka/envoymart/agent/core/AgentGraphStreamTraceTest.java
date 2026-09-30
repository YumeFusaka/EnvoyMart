package yumefusaka.envoymart.agent.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import yumefusaka.envoymart.agent.llm.ChatMessage;
import yumefusaka.envoymart.agent.llm.LLMConfig;
import yumefusaka.envoymart.agent.llm.LLMProvider;
import yumefusaka.envoymart.agent.llm.LLMResponse;
import yumefusaka.envoymart.agent.llm.ToolExecution;
import yumefusaka.envoymart.agent.loop.LoopBudget;
import yumefusaka.envoymart.agent.loop.LoopGuard;
import yumefusaka.envoymart.agent.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 流式下 ReAct 的工具轨迹必须回流到 {@link AgentGraph.GraphResult}。
 * <p>
 * 这条接线的失败方式很隐蔽：正文照常流式推到界面，工具也确实执行了、日志里
 * {@code toolExecutions=1} 打得明明白白，<b>只是那份轨迹在返回处被丢掉</b>。
 * 用户在界面上看到一段没有依据的正文，后置校验还会把它判成「无依据」挂上横幅——
 * 一条明明有工具依据的回答，被标成模型自己编的。
 * <p>
 * 之所以要专门守它：非流式那条分支早就回收了轨迹，流式这条是后来补的
 * （接口原先返回 {@code void}，正文走回调、轨迹无处可回）。两条分支写在一起，
 * 改一条忘一条不会报错、不会少一次模型调用，只会在界面上少一块。
 */
class AgentGraphStreamTraceTest {

    private static final LLMConfig CONFIG = LLMConfig.builder().model("stub").build();

    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    private AgentGraph.GraphResult run(LLMProvider provider, Consumer<String> onChunk) {
        return new AgentGraph(provider, CONFIG, new ToolRegistry(), executor)
                .run("u1", "我的订单到哪了", "", List.of(), new LoopGuard(new LoopBudget(8, 2, 2)), onChunk);
    }

    /** 规划一律返回空计划（直奔 answer 节点），对话轮次内自行执行了一次工具 */
    private static final class StubProvider implements LLMProvider {
        private final List<ToolExecution> executions;
        private final String answer;

        StubProvider(List<ToolExecution> executions, String answer) {
            this.executions = executions;
            this.answer = answer;
        }

        @Override
        public LLMResponse chat(List<ChatMessage> messages, LLMConfig config) {
            return LLMResponse.builder().content(answer).build();
        }

        @Override
        public LLMResponse chatWithTools(List<ChatMessage> messages, LLMConfig config,
                                         Map<String, Object> toolContext) {
            return LLMResponse.builder().content(answer).toolExecutions(executions).build();
        }

        @Override
        public List<ToolExecution> chatStreamWithTools(List<ChatMessage> messages, LLMConfig config,
                                                       Map<String, Object> toolContext,
                                                       Consumer<String> onChunk) {
            onChunk.accept(answer);
            return executions;
        }
    }

    private static ToolExecution execution(String tool) {
        return ToolExecution.builder()
                .tool(tool).input("{\"orderNo\":\"SO1\"}")
                .output("已从杭州发出").success(true).latencyMs(42).build();
    }

    @Test
    void 流式下ReAct的工具轨迹要回收到响应里() {
        List<String> pushed = new ArrayList<>();
        AgentGraph.GraphResult result = run(new StubProvider(List.of(execution("order_query")), "你的订单已从杭州发出"),
                pushed::add);

        assertThat(result.getToolExecutions())
                .as("流式分支丢了轨迹，界面就是一段没有依据的正文，后置校验还会把有依据的回答判成无依据")
                .extracting(ToolExecution::getTool)
                .containsExactly("order_query");
        assertThat(result.getAnswer()).isEqualTo("你的订单已从杭州发出");
        assertThat(pushed).as("正文照旧走回调，改成返回值就断了流式").containsExactly("你的订单已从杭州发出");
    }

    @Test
    void 非流式的轨迹回收不能因此被改坏() {
        AgentGraph.GraphResult result = run(new StubProvider(List.of(execution("order_query")), "你的订单已从杭州发出"), null);

        assertThat(result.getToolExecutions()).extracting(ToolExecution::getTool).containsExactly("order_query");
        assertThat(result.getAnswer()).isEqualTo("你的订单已从杭州发出");
    }

    @Test
    void 没调工具的流式轮次轨迹为空() {
        AgentGraph.GraphResult result = run(new StubProvider(List.of(), "你好，有什么可以帮你"), chunks -> {
        });

        assertThat(result.getToolExecutions()).isEmpty();
        assertThat(result.getAnswer()).isNotBlank();
    }
}
