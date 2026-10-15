package yumefusaka.envoymart.agent.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-10-15 现场回归：两条多跳 FAIL 的确定性复现。
 * <p>
 * 句子与切片均取自真实快照 {@code d17c59ed}（08:01 那轮）的请求现场，
 * 不是构造出来的边界样例——目的是把「解析缺口」与「模型措辞方差」分开：
 * 只要这两句在同一份切片上仍被判未支撑，缺口就还在。
 */
class N1RecallAndParsingRegressionTest {

    private static DocumentChunk chunk(String chunkId, String docId, String title, String position,
                                       String content, double score) {
        return DocumentChunk.builder().chunkId(chunkId).docId(docId).title(title).position(position)
                .content(content).source("manual").score(score).reranked(true).build();
    }

    private static final String KB0027_5 = "4.1 本品所含钙可影响左甲状腺素、四环素类抗生素、喹诺酮类抗生素的吸收，"
            + "与上述药物合用时应间隔至少 2 小时。\n4.2 本品与噻嗪类利尿剂合用时可能增加高钙血症风险，合用期间应咨询医师。";

    /**
     * KG-02：现场两条拒绝理由分别是「关系对象或极性与引用切片不一致」与「数量主体与引用切片不一致」。
     * <p>
     * <b>承载全部锚点的是句 2，不是句 1。</b> 句 2 逐字复述了 KB-0027 4.1 的钙—喹诺酮类间隔结论，
     * 三个验收锚点（钙 / 喹诺酮类抗生素 / 2 小时）全在它身上。它此前被判「数量主体与引用切片不一致」，
     * 根因是句尾的「与这类药物」被当成新主体收进主体集，于是「钙 + 喹诺酮类」这一对凑不齐。
     * <p>
     * <b>句 1「会相互影响。」本来就该被拒。</b> 它一个药名都没提，主体只能靠猜——
     * 既有契约明确要求这类孤立断言不得借问题或来源猜主体（见
     * {@code MedicalEvidenceVerifierTest#孤立关系存在否定和省略谓语必须计为事实且不能借问题或来源猜主体}）。
     * 它被剔除不影响这一题的验收：锚点不在它身上，删掉它正文仍在。
     * 断言因此收在「承载锚点的句子必须被支撑」上，而不是「整篇一句都不能少」。
     */
    @Test
    void KG02承载验收锚点的句子在真实切片上必须被支撑() {
        var evidence = List.of(
                chunk("KB-0027_5", "KB-0027", "碳酸钙 D3 咀嚼片产品说明书",
                        "《碳酸钙 D3 咀嚼片产品说明书》 > 第四章 相互作用与注意事项", KB0027_5, 0.8499),
                chunk("KB-0027_1", "KB-0027", "碳酸钙 D3 咀嚼片产品说明书",
                        "《碳酸钙 D3 咀嚼片产品说明书》 > 第一章 产品概述",
                        "1.2 规格为每片含钙 600 毫克、维生素 D3 400IU，每瓶 60 片。", 0.3080),
                chunk("KB-0034_4", "KB-0034", "儿童钙片与维生素 D 补充及过量说明书",
                        "《儿童钙片与维生素 D 补充及过量说明书》 > 第四章 相互作用与注意事项",
                        "4.1 本品所含钙可影响四环素类抗生素、喹诺酮类抗生素的吸收，儿童服用上述药物期间应与本品间隔至少 2 小时，并遵医嘱。", 0.2846));

        String anchorSentence = "碳酸钙 D3 咀嚼片所含的钙可影响喹诺酮类抗生素的吸收，与这类药物合用时应间隔至少 2 小时（《碳酸钙 D3 咀嚼片产品说明书》） [1]。";
        var verdict = MedicalEvidenceVerifier.verify(true, anchorSentence, evidence);
        assertThat(verdict.reasons()).as("承载锚点的句子：%s", verdict.reasons()).isEmpty();

        // 现场全文：句 1 被剔除后，句 2 必须活下来 —— 锚点与正文都在它身上
        String reply = "会相互影响 [1]。\n" + anchorSentence;
        var full = MedicalEvidenceVerifier.verify(true, reply, evidence);
        assertThat(full.unsupported()).as("只有无主体的句 1 可以被剔除").containsExactly("会相互影响 [1]。");
    }

    /**
     * KG-09：现场四条拒绝理由里两条是证据门、一条是极性、一条是本品归属。
     * <p>
     * 句 2 引用 KB-0010_5（重排分 0.1992，比阈值 0.20 低 0.0008）：它拿的是「图谱路触达过的切片」，
     * 但评测侧此前传 {@code Set.of()}，生产侧传 {@code graphChunkIds}——同一句在两条口径下结论相反。
     * 这里断言的是**同一口径**下（传图谱 id）该句能被支撑，包括它的「两者」回指主体。
     */
    @Test
    void KG09现场句在同口径图谱豁免下必须被支撑() {
        String source = "2.3 钙剂与四环素类、喹诺酮类抗生素合用会形成难吸收的络合物，两者服用时间应间隔 2 小时以上。";
        var chunk = chunk("KB-0010_5", "KB-0010", "膳食补充剂摄入上限与常见药物相互作用",
                "《膳食补充剂摄入上限与常见药物相互作用》 > 第二章 常见药物相互作用 > 2.3 钙剂与四环素类", source, 0.1992);
        String claim = "具体来说：钙剂与四环素类（以及喹诺酮类）抗生素合用，会形成难吸收的络合物，"
                + "导致药物吸收下降，所以两者服用时间应间隔 **2 小时以上** [1]。";
        assertThat(MedicalEvidenceVerifier.verify(true, claim, List.of(chunk), List.of(),
                EvidenceGate.Thresholds.defaults(), java.util.Set.of(chunk.getChunkId()))
                .unsupported()).as("同一口径（评测侧与生产侧都传图谱 id）下应当放行").isEmpty();
    }
}