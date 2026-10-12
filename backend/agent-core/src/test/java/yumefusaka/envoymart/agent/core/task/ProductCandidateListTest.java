package yumefusaka.envoymart.agent.core.task;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import yumefusaka.envoymart.agent.llm.ToolExecution;
import yumefusaka.envoymart.agent.tool.ProductCandidate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProductCandidateListTest {
    @ParameterizedTest
    @ValueSource(strings = {"搜维C", "找鱼油", "查商品乳清蛋白", "有没有钙片", "推荐益生菌",
            "先搜两个商品再加购第二个", "重新搜索鱼油", "展示候选",
            "刚才列表没有结果；改搜乳清蛋白粉，至少展示两个", "原列表不合适，重新搜索鱼油"})
    void 明确找新商品会发布新列表(String message) {
        assertThat(ProductCandidateList.requestsNewList(message)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"原列表第2个加购1件", "第一款改选第二个", "核对乳清蛋白粉香草1kg规格",
            "乳清蛋白粉价格是多少", "将乳清蛋白粉加入1件等我确认", "只查刚才列表第二个的规格"})
    void 选择和单品核规格不会替换列表(String message) {
        assertThat(ProductCandidateList.requestsNewList(message)).isFalse();
    }

    @Test
    void 分组结果按真实轨迹合并去重且正文清除旧序号保留医学提示() {
        var first = new Candidate("SPU29", "维生素 C", List.of("60片（SKU38）"));
        var second = new Candidate("SPU7", "鱼油", List.of("100粒（SKU12）"));
        var items = ProductCandidateList.from(List.of(execution(List.of(first)), execution(List.of(first, second))));

        assertThat(items).extracting(ProductCandidateList.Item::key).containsExactly("SPU29", "SPU7");
        var original = "1. **维生素 C**\n3. **鱼油**\n| ③ | 鱼油 |\n有症状持续请就医。";
        var without = ProductCandidateList.withoutProductOrdinals(original, items);
        assertThat(without).doesNotContain("1. **维生素 C", "3. **鱼油", "| ③ |")
                .contains("有症状持续请就医。");
        assertThat(ProductCandidateList.render(items)).containsSubsequence("1. 维生素 C", "2. 鱼油")
                .contains("60片", "100粒").doesNotContain("每日", "可以同服", "安全");
        assertThat(ProductCandidateList.restore(ProductCandidateList.snapshot(items))).isEqualTo(items);
    }

    @Test
    void 不成功和非商品工具不能污染候选表() {
        var candidate = new Candidate("SPU1", "商品", List.of());
        assertThat(ProductCandidateList.from(List.of(
                ToolExecution.builder().tool("product_search").success(false).rawData(List.of(candidate)).build(),
                ToolExecution.builder().tool("cart_query").success(true).rawData(List.of(candidate)).build()))).isEmpty();
    }

    private static ToolExecution execution(List<Candidate> candidates) {
        return ToolExecution.builder().tool("product_search").success(true).rawData(candidates).build();
    }
    public record Candidate(String candidateKey, String candidateName, List<String> candidateSpecs) implements ProductCandidate { }
}
