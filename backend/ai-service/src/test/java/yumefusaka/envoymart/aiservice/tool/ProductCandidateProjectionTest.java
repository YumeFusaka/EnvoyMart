package yumefusaka.envoymart.aiservice.tool;

import org.junit.jupiter.api.Test;
import yumefusaka.envoymart.agent.core.task.ProductCandidateList;
import yumefusaka.envoymart.agent.llm.ToolExecution;
import yumefusaka.envoymart.aiservice.service.impl.AiAssistantServiceImpl;
import yumefusaka.envoymart.contract.ProductSummary;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;

class ProductCandidateProjectionTest {
    @Test
    @SuppressWarnings("unchecked")
    void 商品卡片严格沿用权威候选顺序而非正文提名排序() throws Exception {
        var calcium = product(25L, "孕妇钙片");
        var other = product(39L, "氨糖维D钙片");
        var executions = List.of(ToolExecution.builder().tool("product_search").success(true)
                .rawData(List.of(new ProductSearchResult(calcium, 34L), new ProductSearchResult(other, 50L))).build());
        var authoritative = ProductCandidateList.from(executions);
        var service = mock(AiAssistantServiceImpl.class, CALLS_REAL_METHODS);
        var projection = AiAssistantServiceImpl.class.getDeclaredMethod("extractProducts", List.class, String.class, List.class);
        projection.setAccessible(true);

        var cards = (List<ProductSummary>) projection.invoke(service, executions, "先讨论氨糖维D钙片，正文暂未提其它。", authoritative);

        assertThat(cards).extracting(ProductSummary::getId).containsExactly(25L, 39L);
        assertThat(ProductCandidateList.restore(ProductCandidateList.snapshot(authoritative)))
                .extracting(ProductCandidateList.Item::key).containsExactly("SPU25", "SPU39");
        assertThat(ProductCandidateList.render(authoritative)).containsSubsequence("1. 孕妇钙片", "2. 氨糖维D钙片");
    }

    private static ProductSummary product(Long id, String name) {
        var product = new ProductSummary();
        product.setId(id);
        product.setName(name);
        return product;
    }
}
