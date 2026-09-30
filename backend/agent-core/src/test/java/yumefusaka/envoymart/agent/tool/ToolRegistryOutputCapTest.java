package yumefusaka.envoymart.agent.tool;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工具输出封顶与耗时回填 —— 出口处的两条契约。
 * <p>
 * 封顶防的是「成本随业务数据量静默增长」：订单明细、物流轨迹、图谱风险边这几处
 * 的长度都取决于数据而不是代码，一个健忘的没加分页就能让每轮调用多发几万 token，
 * 而且不报错。耗时则是「一次问话慢在哪」的唯一依据——轨迹上只有工具名和入参时，
 * 看不出来是商品检索扫了三秒。
 */
class ToolRegistryOutputCapTest {

    private Tool toolReturning(String name, String output) {
        return new Tool() {
            @Override
            public ToolDefinition getDefinition() {
                return ToolDefinition.builder()
                        .name(name).description(name).parameters(Map.of())
                        .build();
            }

            @Override
            public ToolResult execute(ToolCall call) {
                return ToolResult.builder().success(true).output(output).rawData(Map.of("k", "v")).build();
            }
        };
    }

    private ToolResult run(Tool tool) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool);
        return registry.execute(new ToolCall("1", tool.getDefinition().getName(), Map.of()));
    }

    @Test
    void 超长输出被截断并留下痕迹() {
        ToolResult result = run(toolReturning("order_query", "明细行".repeat(3000)));

        assertThat(result.getOutput())
                .as("不封顶的话，一个下过很多单的用户能让每轮调用都多烧几万 token")
                .hasSizeLessThan(4200);
        assertThat(result.getOutput())
                .as("不写「已截断」，模型会以为这就是全部，然后基于半截列表给出毫无保留的结论")
                .contains("已截断")
                .contains("完整长度 9000 字符");
    }

    @Test
    void 未超长的输出原样返回() {
        String output = "运单 SF123 已签收";

        assertThat(run(toolReturning("logistics_query", output)).getOutput()).isEqualTo(output);
    }

    @Test
    void 结构化结果不随文本一起截断() {
        ToolResult result = run(toolReturning("product_search", "商品".repeat(5000)));

        assertThat(result.getRawData())
                .as("rawData 是给前端做二次加工的，不进模型上下文，没有撑爆成本的问题")
                .isEqualTo(Map.of("k", "v"));
    }

    @Test
    void 每次调用都带上耗时() {
        ToolResult result = run(toolReturning("order_query", "ok"));

        assertThat(result.getLatencyMs())
                .as("耗时由注册中心统一测，工具自己不测——三个工具各测一遍必然有的测有的不测")
                .isNotNegative();
    }

    @Test
    void 失败的结果也带耗时() {
        ToolRegistry registry = new ToolRegistry();

        ToolResult result = registry.execute(new ToolCall("1", "not_registered", Map.of()));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getLatencyMs()).isNotNegative();
    }
}
