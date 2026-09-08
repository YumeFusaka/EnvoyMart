package yumefusaka.envoymart.aiservice.tool;

import org.junit.jupiter.api.Test;
import yumefusaka.envoymart.agent.tool.ToolCall;
import yumefusaka.envoymart.agent.tool.ToolResult;
import yumefusaka.envoymart.aiservice.client.OrderClient;
import yumefusaka.envoymart.aiservice.model.OrderResponse;
import yumefusaka.envoymart.common.result.Result;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 工具身份来源的回归防线。
 * <p>
 * 曾经的做法是把 {@code userId} 声明成工具参数，取值直接来自模型生成的计划——
 * 而模型无从知道真实用户是谁，只能编（或按用户提示编）。下游 order-service 又只认
 * 工具透传的这个值做归属校验，于是一句话就能查到别人的订单。
 * <p>
 * 现在身份由执行上下文注入，与模型可见的参数彻底分离。这两条用例锁住该契约。
 */
class ToolIdentityTest {

    private static final String AUTHENTICATED = "u1001";
    private static final String FORGED = "u9999";

    @Test
    void 工具使用认证身份而非参数里伪造的身份() {
        OrderClient client = mock(OrderClient.class);
        AtomicReference<String> seenUserId = new AtomicReference<>();
        when(client.getOrder(anyString(), anyLong())).thenAnswer(invocation -> {
            seenUserId.set(invocation.getArgument(0));
            OrderResponse order = new OrderResponse();
            order.setStatus("DELIVERING");
            order.setTotalAmount(new BigDecimal("299.00"));
            return Result.success(order);
        });

        ToolResult result = new OrderTool(client).execute(new ToolCall(
                "t1", "order_query",
                // 模型（或 MCP 客户端）在参数里塞一个伪造身份，试图冒充他人
                Map.of("orderId", 1L, "userId", FORGED),
                false, AUTHENTICATED));

        assertThat(result.isSuccess()).isTrue();
        assertThat(seenUserId.get())
                .as("下游收到的身份必须来自认证结果，参数里的伪造值应被无视")
                .isEqualTo(AUTHENTICATED);
    }

    @Test
    void 缺少认证身份时拒绝执行且不触达下游() {
        OrderClient client = mock(OrderClient.class);

        // 无身份上下文，只有参数——这正是改法之前的调用形态
        ToolResult result = new OrderTool(client).execute(new ToolCall(
                "t1", "order_query", Map.of("orderId", 1L, "userId", FORGED)));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("用户身份");
        verifyNoInteractions(client);
    }

    @Test
    void 工具签名中不再暴露用户身份参数() {
        OrderClient client = mock(OrderClient.class);

        assertThat(new OrderTool(client).getDefinition().getParameters())
                .as("身份一旦出现在工具签名里，模型就会去填它")
                .doesNotContainKey("userId");
        assertThat(new CancelOrderTool(client).getDefinition().getParameters())
                .doesNotContainKey("userId");
        assertThat(new LogisticsTool(client).getDefinition().getParameters())
                .doesNotContainKey("userId");
    }
}
