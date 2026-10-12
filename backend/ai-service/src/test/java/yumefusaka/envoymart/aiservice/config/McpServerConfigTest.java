package yumefusaka.envoymart.aiservice.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;
import yumefusaka.envoymart.agent.tool.ToolDefinition;
import yumefusaka.envoymart.aiservice.tool.AfterSaleTool;
import yumefusaka.envoymart.aiservice.tool.CheckoutTool;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * MCP 高危工具确认信号的解析边界。
 * <p>
 * 这个解析的失败方向必须是「未确认」：把没确认当确认放行一次取消/退款，
 * 比把确认当没确认多问一遍严重得多。
 */
class McpServerConfigTest {

    @Test
    void 只有明确为真的取值算已确认() {
        assertThat(McpServerConfig.parseConfirmed(Boolean.TRUE)).isTrue();
        assertThat(McpServerConfig.parseConfirmed("true")).isTrue();
        assertThat(McpServerConfig.parseConfirmed("TRUE")).isTrue();
    }

    @Test
    void 其余一切取值都算未确认() {
        assertThat(McpServerConfig.parseConfirmed(null)).isFalse();
        assertThat(McpServerConfig.parseConfirmed(Boolean.FALSE)).isFalse();
        assertThat(McpServerConfig.parseConfirmed("false")).isFalse();
        assertThat(McpServerConfig.parseConfirmed("yes")).isFalse();
        assertThat(McpServerConfig.parseConfirmed(1)).isFalse();
    }

    @Test
    void 参数Map插入顺序不同也生成完全相同且稳定的Schema() {
        var first = new LinkedHashMap<String, ToolDefinition.ParameterSpec>();
        first.put("receiverName", required("string"));
        first.put("receiverProvince", required("string"));
        first.put("receiverCity", required("string"));
        first.put("receiverPhone", required("string"));
        first.put("receiverDetail", required("string"));
        first.put("receiverDistrict", required("string"));
        var reverse = new LinkedHashMap<String, ToolDefinition.ParameterSpec>();
        first.reversed().forEach(reverse::put);

        var config = new McpServerConfig();
        var left = config.toJsonSchema(ToolDefinition.builder().name("cart_checkout").parameters(first).build());
        var right = config.toJsonSchema(ToolDefinition.builder().name("cart_checkout").parameters(reverse).build());

        assertThat(left).isEqualTo(right);
        assertThat(JsonMapper.builder().build().writeValueAsString(left))
                .isEqualTo(JsonMapper.builder().build().writeValueAsString(right));
        assertThat(left.get("required")).isEqualTo(java.util.List.of(
                "receiverCity", "receiverDetail", "receiverDistrict", "receiverName", "receiverPhone", "receiverProvince"));
        assertThat(left.get("properties")).isInstanceOf(Map.class);
    }

    @ParameterizedTest
    @MethodSource("真实写工具定义")
    void 售后和结算业务参数正反顺序不改变确认Schema序列(ToolDefinition definition) {
        var forward = new LinkedHashMap<String, ToolDefinition.ParameterSpec>();
        new TreeMap<>(definition.getParameters()).forEach(forward::put);
        var reverse = new LinkedHashMap<String, ToolDefinition.ParameterSpec>();
        forward.reversed().forEach(reverse::put);
        definition.setParameters(forward);
        var config = new McpServerConfig();
        var left = config.toJsonSchema(definition);
        definition.setParameters(reverse);
        var right = config.toJsonSchema(definition);

        var mapper = JsonMapper.builder().build();
        assertThat(mapper.writeValueAsString(left)).isEqualTo(mapper.writeValueAsString(right));
        var expected = new java.util.ArrayList<>(forward.entrySet().stream()
                .filter(entry -> entry.getValue().isRequired()).map(Map.Entry::getKey).toList());
        expected.add(McpServerConfig.CONFIRMED_ARG);
        expected.add(McpServerConfig.APPROVAL_TOKEN_ARG);
        assertThat(left.get("required")).isEqualTo(expected);
        assertThat(((Map<?, ?>) left.get("properties")).keySet().stream()
                .map(String::valueOf).toList()).containsAll(expected);
    }

    private static Stream<ToolDefinition> 真实写工具定义() {
        return Stream.of(new AfterSaleTool(null).getDefinition(), new CheckoutTool(null).getDefinition());
    }

    private static ToolDefinition.ParameterSpec required(String type) {
        return ToolDefinition.ParameterSpec.builder().type(type).required(true).description("").build();
    }
}
