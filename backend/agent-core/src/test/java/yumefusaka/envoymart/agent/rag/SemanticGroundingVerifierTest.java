package yumefusaka.envoymart.agent.rag;

import org.junit.jupiter.api.Test;
import yumefusaka.envoymart.agent.llm.LLMProvider;
import yumefusaka.envoymart.agent.llm.LLMResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SemanticGroundingVerifierTest {
    private static final String QUOTE = "维生素 K 可拮抗华法林等维生素 K 拮抗剂类抗凝药物的作用，正在服用华法林者禁用本品，或必须在医师指导下使用并监测凝血指标";
    private static final String ORIGINAL = "另外，平台知识库中另有一条资料提到维生素 K 与华法林的关系：[1]第四章写明「" + QUOTE + "」。";
    private static final List<DocumentChunk> EVIDENCE = List.of(
            DocumentChunk.builder().chunkId("KB-0026_4").docId("KB-0026")
                    .title("维生素 K2 软胶囊产品说明书").score(0.4214574006557065)
                    .content("《维生素 K2 软胶囊产品说明书》 > 第四章 相互作用与注意事项\n4.1 " + QUOTE + "。").build(),
            DocumentChunk.builder().chunkId("other").docId("other").title("鱼油说明书")
                    .content("鱼油可能增加出血风险。").build());

    private static SemanticGroundingVerifier.Verdict judge(String sentence, String decision) {
        LLMProvider llm = (messages, config) -> LLMResponse.builder()
                .content("[{\"index\":1,\"decision\":\"" + decision + "\",\"refs\":[]}]").build();
        return SemanticGroundingVerifier.verify(llm, null, "维生素K2与华法林有相互作用吗？",
                sentence, List.of(sentence), EVIDENCE, "无");
    }

    @Test
    void KG04明确原文引用不被模型语义误删() {
        var verdict = judge(ORIGINAL, "UNSUPPORTED");
        assertThat(verdict.evaluated()).isTrue();
        assertThat(verdict.unsupported()).isEmpty();
        assertThat(verdict.citations()).containsEntry(ORIGINAL, List.of(1));
    }

    @Test
    void 引文改方向换实体换编号或追加结论仍拒绝() {
        for (String changed : List.of(ORIGINAL.replace("可拮抗", "可增强"),
                ORIGINAL.replace("维生素 K 可", "维生素 K2 可"), ORIGINAL.replace("[1]", "[2]"),
                ORIGINAL + "所以维生素K2可以与华法林安全合用。",
                "鱼油说明书写明「" + QUOTE + "」[1]。")) {
            assertThat(judge(changed, "UNSUPPORTED").unsupported()).as(changed).containsExactly(changed);
        }
    }

    @Test
    void 明确工具事实冲突不能被原文引用覆盖() {
        assertThat(judge(ORIGINAL, "CONFLICTED").unsupported()).containsExactly(ORIGINAL);
    }
}
