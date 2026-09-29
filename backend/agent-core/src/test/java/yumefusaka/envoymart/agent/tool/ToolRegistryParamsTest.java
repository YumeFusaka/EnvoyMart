package yumefusaka.envoymart.agent.tool;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 必填参数校验：模型给的键名写错时，要报出<b>能指向原因</b>的错，而不是让工具自己炸。
 * <p>
 * 实测就是这样：规划路径把 {@code orderId} 写成了 {@code order_id}，工具按声明名取值
 * 取到 {@code null}，抛出来是一句 {@code NullPointerException}——既不说是哪个参数，
 * 也不说是键名写错还是漏传，只能去读工具源码。
 */
class ToolRegistryParamsTest {

    private final AtomicInteger executions = new AtomicInteger();

    private ToolRegistry registry() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new Tool() {
            @Override
            public ToolDefinition getDefinition() {
                return ToolDefinition.builder()
                        .name("order_cancel")
                        .description("取消未支付的订单")
                        .parameters(Map.of(
                                "orderId", ToolDefinition.ParameterSpec.builder()
                                        .type("integer").description("订单 ID").required(true).build(),
                                "reason", ToolDefinition.ParameterSpec.builder()
                                        .type("string").description("取消原因").required(false).build()))
                        .build();
            }

            @Override
            public ToolResult execute(ToolCall call) {
                executions.incrementAndGet();
                // 真实工具就是这么取值的：取不到就 NPE
                return ToolResult.builder()
                        .success(true)
                        .output("订单 " + call.getArguments().get("orderId") + " 已取消")
                        .build();
            }
        });
        return registry;
    }

    @Test
    void 键名写成下划线时报错要说清缺哪个_实际给了哪个() {
        ToolResult result = registry().execute(
                new ToolCall("1", "order_cancel", Map.of("order_id", 12)));

        assertThat(result.isSuccess()).isFalse();
        assertThat(executions.get())
                .as("参数不全就不该进到工具里 —— 进去就是一句 NPE，看不出真实原因")
                .isZero();
        assertThat(result.getErrorMessage())
                .as("两个键都报出来，才能一眼分清是键名写错还是漏传")
                .contains("orderId").contains("order_id");
    }

    @Test
    void 必填项齐了照常执行() {
        ToolResult result = registry().execute(
                new ToolCall("1", "order_cancel", Map.of("orderId", 12)));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("订单 12 已取消");
        assertThat(executions.get()).isEqualTo(1);
    }

    @Test
    void 选填项缺席不算缺参数() {
        ToolResult result = registry().execute(
                new ToolCall("1", "order_cancel", Map.of("orderId", 12)));

        assertThat(result.isSuccess())
                .as("只校验必填项；把选填也拦下会让本来能跑通的调用白白失败")
                .isTrue();
    }

    @Test
    void 一个参数都没给也要能过校验并给出可读报错() {
        ToolResult result = registry().execute(new ToolCall("1", "order_cancel", Map.of()));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("orderId").contains("（无）");
    }
}
