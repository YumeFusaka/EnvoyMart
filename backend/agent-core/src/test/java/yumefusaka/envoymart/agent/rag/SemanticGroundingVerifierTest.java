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

    /** 判官给出的档位应当原样落到去留上：META 与 CONVERSATIONAL 保留，UNSUPPORTED 剔除。 */
    @Test
    void 检索自述句判为元陈述时保留() {
        for (String meta : List.of("以上是知识库中与满减直接相关的全部内容。",
                "以上内容来自平台知识库。", "本次检索没有查到维生素 D 的推荐摄入量。",
                "本轮没有检索到相关依据。")) {
            var verdict = judge(meta, "META");
            assertThat(verdict.evaluated()).as(meta).isTrue();
            assertThat(verdict.unsupported()).as(meta).isEmpty();
        }
    }

    /**
     * 元陈述档位不能承接关于世界的事实：判官若把医学事实句标成 META，
     * 确定性事实识别仍须把它降级剔除（与 CONVERSATIONAL 同一条兜底）。
     */
    @Test
    void 医学事实句借元陈述档位逃逸仍被确定性判据拒绝() {
        for (String claim : List.of("知识库没有收录维生素 D 的推荐摄入量。",
                "阿司匹林是处方药。", "服用本品期间应监测凝血指标。",
                "本品所含钙可影响铁的吸收。")) {
            var verdict = judge(claim, "META");
            assertThat(verdict.evaluated()).as(claim).isTrue();
            assertThat(verdict.unsupported()).as(claim).containsExactly(claim);
        }
    }

    /** 档位表外取值说明判官输出不可信，必须整轮退回确定性审核而不是按未知档位放行。 */
    @Test
    void 表外判定档位视为判官不可用() {
        var verdict = judge("以上是知识库中与满减直接相关的全部内容。", "UNKNOWN");
        assertThat(verdict.evaluated()).isFalse();
        assertThat(verdict.unsupported()).isEmpty();
    }

    /**
     * 提示词声明的档位与代码接受的档位必须是同一套：只改一处，判官会整轮不可用，
     * 症状是「语义审核静默失效」而不是报错。
     */
    @Test
    void 提示词档位与代码解析档位一致() {
        java.util.concurrent.atomic.AtomicReference<String> system = new java.util.concurrent.atomic.AtomicReference<>();
        LLMProvider llm = (messages, config) -> {
            system.set(messages.getFirst().getContent());
            return LLMResponse.builder()
                    .content("[{\"index\":1,\"decision\":\"META\",\"refs\":[]}]").build();
        };
        SemanticGroundingVerifier.verify(llm, null, "维生素K2与华法林有相互作用吗？",
                "以上是知识库中与满减直接相关的全部内容。",
                List.of("以上是知识库中与满减直接相关的全部内容。"), EVIDENCE, "无");
        assertThat(system.get())
                .contains("\"SUPPORTED|CONVERSATIONAL|META|UNSUPPORTED|CONFLICTED\"")
                .contains("本次检索没有查到维生素 D 的推荐摄入量」只报告本次检索结果")
                .contains("「知识库没有收录维生素 D 的推荐摄入量」是对知识库内容的断言");
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
