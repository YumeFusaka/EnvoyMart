package yumefusaka.envoymart.aiservice.tool;

import org.junit.jupiter.api.Test;
import yumefusaka.envoymart.agent.tool.ToolDefinition;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** T1 商业工具的结构化输出与高危确认契约。 */
class AgentCommerceToolContractTest {

    @Test
    void 商品候选保留顺序与每个候选的规格事实() {
        var first = new ProductSearchResult(null, null, null, null, null, null,
                List.of(new ProductSearchResult.SkuOption(101L, "500mg", 29L, 8, true)));
        var second = new ProductSearchResult(null, null, null, null, null, null,
                List.of(new ProductSearchResult.SkuOption(202L, "1000mg", 49L, 0, false)));

        assertThat(List.of(first, second)).extracting(ProductSearchResult::skus)
                .containsExactly(
                        List.of(new ProductSearchResult.SkuOption(101L, "500mg", 29L, 8, true)),
                        List.of(new ProductSearchResult.SkuOption(202L, "1000mg", 49L, 0, false)));
    }

    @Test
    void 新增写工具都声明高危确认且不是无幂等策略() {
        ToolDefinition update = new CartUpdateTool(null).getDefinition();
        ToolDefinition remove = new CartRemoveTool(null).getDefinition();
        ToolDefinition receive = new CouponReceiveTool(null).getDefinition();
        ToolDefinition ticket = new TicketCreateTool(null).getDefinition();

        assertThat(List.of(update, remove, receive, ticket))
                .allSatisfy(definition -> {
                    assertThat(definition.isRequiresConfirmation()).isTrue();
                    assertThat(definition.getIdempotencyPolicy())
                            .isNotEqualTo(ToolDefinition.IdempotencyPolicy.NONE);
                    assertThat(definition.getParameters()).isNotEmpty();
                });
    }
}
