package yumefusaka.envoymart.aiservice.skill;

import org.junit.jupiter.api.Test;
import yumefusaka.envoymart.agent.skill.SkillContext;
import yumefusaka.envoymart.agent.skill.SkillResult;
import yumefusaka.envoymart.agent.tool.Tool;
import yumefusaka.envoymart.agent.tool.ToolCall;
import yumefusaka.envoymart.agent.tool.ToolDefinition;
import yumefusaka.envoymart.agent.tool.ToolRegistry;
import yumefusaka.envoymart.agent.tool.ToolResult;
import yumefusaka.envoymart.aiservice.model.OrderResponse;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AfterSaleSkillTest {

    private final AfterSaleSkill skill = new AfterSaleSkill();

    /** 用假订单查询工具替换真实 Feign 调用。 */
    private SkillContext contextWithOrder(String status) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new Tool() {
            @Override
            public ToolDefinition getDefinition() {
                return ToolDefinition.builder().name("order_query").description("查订单")
                        .parameters(Map.of()).build();
            }

            @Override
            public ToolResult execute(ToolCall call) {
                OrderResponse order = new OrderResponse();
                order.setId(1L);
                order.setOrderNo("YS20260909");
                order.setStatus(status);
                order.setTotalAmount(new BigDecimal("99.00"));
                return ToolResult.builder()
                        .success(true)
                        .output("订单状态: " + status)
                        .rawData(order)
                        .build();
            }
        });
        return SkillContext.builder()
                .userId("u1001").sessionId("s1")
                .userMessage("订单 1 我想退货")
                .toolRegistry(registry)
                .build();
    }

    @Test
    void 有售后意图且带订单号才命中() {
        assertThat(skill.matches("订单 3 我想退货")).isTrue();
        assertThat(skill.matches("帮我给订单 12 申请退款")).isTrue();
    }

    @Test
    void 只问政策或没有订单号时不命中() {
        // 纯政策咨询应交给 ReAct + RAG，而不是走确定性流程
        assertThat(skill.matches("七天无理由怎么算")).isFalse();
        assertThat(skill.matches("我想退货")).isFalse();
        assertThat(skill.matches(null)).isFalse();
        assertThat(skill.matches("")).isFalse();
    }

    @Test
    void 未取消订单给出退货步骤() {
        SkillContext context = contextWithOrder("DELIVERING");

        SkillResult result = skill.execute(context);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("可以申请退货").contains("YS20260909");
    }

    @Test
    void 已取消订单不再引导退货() {
        SkillContext context = contextWithOrder("CANCELLED");

        SkillResult result = skill.execute(context);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("已经是取消状态");
    }
}
