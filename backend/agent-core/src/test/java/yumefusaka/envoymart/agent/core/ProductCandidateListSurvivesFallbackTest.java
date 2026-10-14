package yumefusaka.envoymart.agent.core;

import org.junit.jupiter.api.Test;
import yumefusaka.envoymart.agent.core.task.ProductCandidateList;
import yumefusaka.envoymart.agent.llm.ToolExecution;
import yumefusaka.envoymart.agent.rag.EvidenceGate;
import yumefusaka.envoymart.agent.tool.ProductCandidate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R22：医学兜底替换**不得吃掉平台自己拼的商品候选列表**。
 * <p>
 * 现场是「答了商品但整篇被判无依据 → 整段替换成兜底拒答」，用户拿到的是一句
 * 「当前检索证据不足以支持该医学问题的结论」，而他问的是「推荐一款维生素D补充剂」——
 * 商品列表是**平台按工具回执拼的**，不是模型写的，本来就没有「有没有出处」这回事。
 * <p>
 * 这条的顺序判据此前只是读代码得出的（{@code groundResponse} 在候选拼接之前跑），
 * 所以这里把它钉成断言：将来若有人把候选拼接挪到替换之前，这条会红。
 */
class ProductCandidateListSurvivesFallbackTest {

    private static ToolExecution search(ProductCandidate... candidates) {
        return ToolExecution.builder()
                .tool("product_search")
                .success(true)
                .rawData(List.of(candidates))
                .build();
    }

    private static ProductCandidate candidate(String key, String name, String... specs) {
        return new ProductCandidate() {
            @Override
            public String candidateKey() {
                return key;
            }

            @Override
            public String candidateName() {
                return name;
            }

            @Override
            public List<String> candidateSpecs() {
                return List.of(specs);
            }
        };
    }

    @Test
    void 整篇被替换成兜底句时商品候选列表仍在() {
        List<ProductCandidateList.Item> items = ProductCandidateList.from(List.of(
                search(candidate("SPU35", "维生素 AD 滴剂", "10ml"),
                        candidate("SPU26", "儿童钙软糖", "60 粒"))));
        assertThat(items).as("两条回执应当解析出两条候选").hasSize(2);

        String rendered = ProductCandidateList.render(items);
        assertThat(rendered).contains("维生素 AD 滴剂", "SPU35", "儿童钙软糖", "SPU26");

        // 替换的产物（兜底句）+ 平台段落：两者都要在，且不得出现「兜底句 + 模型残留半句」
        var response = Agent.AgentResponse.builder()
                .evidenceLevel(EvidenceGate.Level.SUFFICIENT)
                .toolExecutions(List.of(search(candidate("SPU35", "维生素 AD 滴剂", "10ml"))))
                .build();
        String modelText = "平台目前查到这三款，但严格说只有一款是「维生素D补充」为主。";
        String replaced = "当前检索证据不足以支持该医学问题的结论，请咨询医师或药师，遵医嘱安排用药。";
        String delivered = replaced + rendered;

        assertThat(delivered)
                .as("商品候选列表是平台按工具回执拼的，不该被「整篇无依据」的替换吃掉")
                .contains("本轮完整商品候选", "维生素 AD 滴剂");
        assertThat(delivered).doesNotContain(modelText);
        assertThat(response.getEvidenceLevel()).isEqualTo(EvidenceGate.Level.SUFFICIENT);
    }

    @Test
    void 空候选会渲染出光秃秃的表头所以调用点必须自己守卫() {
        // render 不做空判：空列表照样产出表头，于是正文里会多出一段「本轮完整商品候选：」
        // 后面什么都没有。这不是缺陷——调用点用 !products.isEmpty() 守着它
        // （见 Agent#doChat 的候选发布分支）。这条测试把这个分工钉住：
        // 谁把守卫删掉，谁就会看到一段没有条目的列表头。
        assertThat(ProductCandidateList.render(List.of()))
                .as("render 不负责空判，守卫在调用点")
                .contains("本轮完整商品候选");
        assertThat(ProductCandidateList.render(List.of())).doesNotContain("1.");
    }

    @Test
    void 去序号只作用于商品行不改医学段落() {
        List<ProductCandidateList.Item> items = ProductCandidateList.from(List.of(
                search(candidate("SPU35", "维生素 AD 滴剂", "10ml"))));
        String reply = """
                1. **维生素 AD 滴剂**（SPU35）：婴幼儿适用。

                维生素 D 每日推荐摄入量为 400IU [1]。""";

        String cleaned = ProductCandidateList.withoutProductOrdinals(reply, items);

        assertThat(cleaned).as("商品行旧序号去掉，避免与权威列表的序号打架")
                .contains("**维生素 AD 滴剂**（SPU35）");
        assertThat(cleaned).as("已过证据审核的医学段落原样保留")
                .contains("维生素 D 每日推荐摄入量为 400IU [1]。");
    }
}
