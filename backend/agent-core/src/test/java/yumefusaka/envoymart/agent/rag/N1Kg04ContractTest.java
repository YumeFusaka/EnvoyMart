package yumefusaka.envoymart.agent.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * KG-04 契约（零成本）：模型答对了 K2 与华法林的拮抗关系，整段却被剔除。
 * 这里用真实快照里的句子与切片，跑生产同源的核对器，看是哪一条判据动手。
 */
class N1Kg04ContractTest {

    private static DocumentChunk chunk(String title, String content) {
        return DocumentChunk.builder()
                .chunkId("KB-0036_5").docId("KB-0036").chunkIndex(5)
                .title(title).content(content).source("manual")
                .score(0.42).reranked(true)
                .build();
    }

    @Test
    void 编号框架替换标题后本品仍能定位归属() {
        // 真实切片（来自 production-grounding.json 的 KG-04 证据 [1]）
        DocumentChunk k2 = chunk("维生素 K2 软胶囊产品说明书",
                "《维生素 K2 软胶囊产品说明书》 > 第四章 相互作用与注意事项\n"
                        + "4.1 维生素 K 可拮抗华法林等维生素 K 拮抗剂类抗凝药物的作用，正在服用华法林者禁用本品，"
                        + "或必须在医师指导下使用并监测凝血指标。");
        List<DocumentChunk> evidence = List.of(k2);

        // 模型原文 → numberTitles 之后（标题被换成编号）
        String beforeNumbering = "《维生素 K2 软胶囊产品说明书》第四章写明：维生素 K 可拮抗华法林等"
                + "维生素 K 拮抗剂类抗凝药物的作用，正在服用华法林者禁用本品，或必须在医师指导下使用并监测凝血指标 [1]。";
        String numbered = CitationVerifier.numberTitles(beforeNumbering, evidence);
        System.out.println("[PROBE] numbered = " + numbered);

        var verdict = MedicalEvidenceVerifier.verify(true, numbered, evidence);
        System.out.println("[PROBE] unsupported = " + verdict.unsupported());
        System.out.println("[PROBE] reasons = " + verdict.reasons());

        assertThat(verdict.unsupported())
                .as("有原文依据的正确句子不该被剔除（当前现场是被剔除的）")
                .isEmpty();
    }

    @Test
    void 句首编号被切掉后仍能定位归属() {
        DocumentChunk k2 = chunk("维生素 K2 软胶囊产品说明书",
                "4.1 维生素 K 可拮抗华法林等维生素 K 拮抗剂类抗凝药物的作用，正在服用华法林者禁用本品，"
                        + "或必须在医师指导下使用并监测凝血指标。");
        // 现场 MEDICAL_REVIEW 日志里那一句（句首 [1] 已被切走）
        String sentence = "第四章写明：维生素 K 可拮抗华法林等维生素 K 拮抗剂类抗凝药物的作用，"
                + "正在服用华法林者禁用本品，或必须在医师指导下使用并监测凝血指标 [1]。";

        var verdict = MedicalEvidenceVerifier.verify(true, sentence, List.of(k2));
        System.out.println("[PROBE2] unsupported = " + verdict.unsupported());
        System.out.println("[PROBE2] reasons = " + verdict.reasons());

        assertThat(verdict.unsupported()).as("这一句有原文依据，不该被剔除").isEmpty();
    }
}