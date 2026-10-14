package yumefusaka.envoymart.agent.core;

import org.junit.jupiter.api.Test;
import yumefusaka.envoymart.agent.core.task.SessionContext;
import yumefusaka.envoymart.agent.llm.ToolExecution;
import yumefusaka.envoymart.agent.tool.ToolResult;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 失败重试的<b>信息增量</b> —— 发现 O 第 2 条（N1 文档 §四 R9 第 3 项）。
 *
 * <p><b>实测形态：</b>{@code u1001-1791359634411} 第 25 与第 27 轮是<b>逐字相同</b>的
 * {@code order_cancel(orderId=YS2026...)}。失败之后模型手上没有任何「上次这样试过、
 * 没成」的信息，历史里只有它自己写的「执行失败」——而读到那句话时的自然反应就是再试一次。
 *
 * <p>这些用例守两件事：<b>失败要留痕</b>（否则下一轮无从知道），<b>成功要清除</b>
 * （否则旧失败会一直叮嘱，读起来像系统还在报错）。
 */
class AgentFailureMemoryTest {

    private static ToolExecution write(String tool, String input, boolean success, String output) {
        return ToolExecution.builder()
                .tool(tool).input(input).output(output).success(success)
                .operationState(success ? ToolResult.OperationState.COMMITTED
                        : ToolResult.OperationState.REJECTED)
                .build();
    }

    private static SessionContext withFailure(Map<String, Object> failure) {
        return SessionContext.empty("u1", "s1", 0)
                .switchTo("取消订单", 1)
                .advance("取消订单", failure.isEmpty() ? Map.of() : Map.of("lastFailure", failure), 1);
    }

    @Test
    void 失败的写操作被记成一条可核对的事实() {
        var response = Agent.AgentResponse.builder()
                .toolExecutions(List.of(write("order_cancel", "{\"orderId\":\"YS2026\"}", false,
                        "执行结果待确认：订单服务暂时不可用")))
                .build();

        Map<String, Object> failure = Agent.failureOf(response);

        assertThat(failure)
                .as("工具名、参数、失败原因三样都要：前两样是「是不是同一次调用」的判据，"
                        + "第三样才是模型能据以改变行为的增量")
                .containsEntry("tool", "order_cancel")
                .containsKey("input")
                .containsKey("error");
    }

    @Test
    void 成功的调用不留失败痕迹() {
        var response = Agent.AgentResponse.builder()
                .toolExecutions(List.of(write("order_cancel", "{\"orderId\":12}", true, "订单 12 已取消")))
                .build();

        assertThat(Agent.failureOf(response))
                .as("成功之后再留着失败，下一轮会对着一个已经解决的问题反复叮嘱")
                .isEmpty();
    }

    @Test
    void 只读查询失败不算需要留痕的失败() {
        var response = Agent.AgentResponse.builder()
                .toolExecutions(List.of(ToolExecution.builder()
                        .tool("order_query").input("{}").output("查询超时").success(false).build()))
                .build();

        assertThat(Agent.failureOf(response))
                .as("读查询失败重发是合理的（换个条件、等下游缓过来），"
                        + "把它记成「别再重发」会挡住正常重试")
                .isEmpty();
    }

    @Test
    void 失败事实写进提示时带工具参数原因与出路() {
        String hint = Agent.lastFailureHint(withFailure(Map.of(
                "tool", "order_cancel",
                "input", "{\"orderId\":\"YS2026\"}",
                "error", "订单服务暂时不可用")));

        assertThat(hint)
                .as("工具与参数必须出现：模型要据此认出「上一次就是这个调用」")
                .contains("order_cancel")
                .contains("YS2026");
        assertThat(hint)
                .as("失败原因是增量信息本身——没有它，模型只知道「失败过」，"
                        + "而它最可能做的还是原样再发一次")
                .contains("订单服务暂时不可用");
        assertThat(hint)
                .as("给的是出路而不是禁令：直接写「不得重发」会挡住「换个参数就能成」的正常重试")
                .contains("先处理这个原因")
                .doesNotContain("不得重试");
    }

    @Test
    void 没有失败事实时不产生提示() {
        assertThat(Agent.lastFailureHint(withFailure(Map.of())))
                .as("没有失败却给一段「上次失败了」，会让模型对着一个不存在的问题调整行为")
                .isNull();
        assertThat(Agent.lastFailureHint(null)).isNull();
    }
}