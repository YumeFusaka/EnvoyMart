package yumefusaka.envoymart.agent.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「知识依据」段的渲染契约。
 * <p>
 * 锁的不是措辞好不好看，而是<b>模型能不能据此给出可核对的引用</b>：
 * 位置与版本有没有进去、编号规则有没有写死、拒答态有没有把条目一起塞回去。
 * 这些一旦退化，表现是回答照样流畅、只是引用变成了摆设。
 */
class KnowledgePromptTest {

    private static final EvidenceGate.Thresholds T = EvidenceGate.Thresholds.defaults();

    private DocumentChunk chunk(String id, String position, Double score) {
        return DocumentChunk.builder()
                .chunkId(id + "_0").docId(id)
                .title("维生素 D3 软胶囊说明书")
                .position(position)
                .source("manual").version("v2026.03")
                .scope("nutrition")
                .content("每日推荐摄入量为 400IU，上限 2000IU。")
                .score(score).reranked(Boolean.FALSE)
                .build();
    }

    @Test
    void 证据充分时带编号位置来源版本并强制标注引用() {
        var section = KnowledgePrompt.render(
                List.of(chunk("d1", "《维生素 D3 软胶囊说明书》 > 第二章 > 3.2", 0.78)), T);

        assertThat(section.decision().isSufficient()).isTrue();
        assertThat(section.text())
                .contains("[1]")
                .contains("第二章 > 3.2")
                .contains("来源：manual")
                .contains("版本：v2026.03")
                .contains("每日推荐摄入量为 400IU")
                .contains("标注来源编号")
                .contains("不得编造编号");
    }

    @Test
    void 多条证据各自编号且顺序与输入一致() {
        var section = KnowledgePrompt.render(List.of(
                chunk("d1", "《甲》 > 第一章", 0.80),
                chunk("d2", "《乙》 > 第二章", 0.70)), T);

        assertThat(section.text())
                .contains("[1] 《甲》 > 第一章")
                .contains("[2] 《乙》 > 第二章");
    }

    /**
     * 拒答态的措辞必须显式在场。
     * <p>
     * 没有这段指令时，模型面对空证据的默认行为是「用自己的知识答」——
     * 而这恰恰是编造条款与数字的来源。
     */
    @Test
    void 没有召回时给出拒答指令且不混入任何条目() {
        var section = KnowledgePrompt.render(List.of(), T);

        assertThat(section.decision().level()).isEqualTo(EvidenceGate.Level.NONE);
        assertThat(section.text())
                .contains("没有找到相关依据")
                .contains("不得依据常识、经验或训练数据编造")
                .doesNotContain("[1]");
    }

    @Test
    void 证据不足时明确禁止作为结论依据但仍保留线索() {
        var section = KnowledgePrompt.render(
                List.of(chunk("d1", "《维生素 D3 软胶囊说明书》 > 第二章", 0.12)), T);

        assertThat(section.decision().level()).isEqualTo(EvidenceGate.Level.WEAK);
        assertThat(section.text())
                .contains("不得作为结论依据")
                .contains("依据不足")
                .as("低相关度条目仍要展示，让用户知道系统找到了什么")
                .contains("第二章");
    }

    /**
     * 位置缺失时退到标题，<b>不能退到 docId</b>。
     * <p>
     * 曾经 {@code KnowledgeSnippet.title} 填的就是 docId，前端渲染成「[1] doc_12」——
     * 一串内部 ID 摆在用户面前，既不能核对也不能理解，却看起来像一条引用。
     */
    @Test
    void 位置缺失时退到文档标题而不是内部标识() {
        DocumentChunk noPosition = chunk("d1", null, 0.80);
        var section = KnowledgePrompt.render(List.of(noPosition), T);

        assertThat(section.text())
                .contains("《维生素 D3 软胶囊说明书》")
                .doesNotContain("d1");
    }
}
