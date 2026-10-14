package yumefusaka.envoymart.agent.rag;

import org.junit.jupiter.api.Test;
import yumefusaka.envoymart.agent.llm.ToolExecution;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * N1 判定粒度修复的边界契约（§2.14 发现 T/U/V 的零成本归因结论）。
 *
 * <p><b>三条修法的分工</b>：
 * <ul>
 *   <li>M1 证据门按<b>轮级</b>判一次——它回答的是「这一轮证据够不够」，不是「这一句引用的那几片够不够」；</li>
 *   <li>M2 商品行的出处是<b>工具回执</b>，不是知识切片——商品信息（价格/规格/编号）本就不在语料里；</li>
 *   <li>M3 疑问句不是断言——澄清问句不构成需要出处的医学事实。</li>
 * </ul>
 *
 * <p><b>本类的反例一律不得放宽</b>：它们守的是「商品事实」与「医学结论」的分界——
 * 能对上回执的商品行放行，一旦句中带上医学结论就必须回到医学链。
 */
class MedicalGateGranularityTest {

    private static DocumentChunk chunk(String docId, String chunkId, double score, String title, String content) {
        return DocumentChunk.builder().docId(docId).chunkId(chunkId).score(score).title(title).content(content).build();
    }

    /** 生产现场原样回执（`complex-...-CA-011` 第 3 轮，分数与正文都是原样）。 */
    private static ToolExecution calciumSearch() {
        return ToolExecution.builder().tool("product_search").success(true)
                .output("找到 3 个商品：\n"
                        + "- 候选[0] 编号 SPU25：孕妇钙片（柠檬酸钙 500mg 与维生素 D3 400IU，孕期哺乳期适用），"
                        + "价格 129.00 元，类目 骨骼关节，品牌 康倍适，有货，已售 1320 件，标签 孕期、骨骼健康\n"
                        + "    规格：SKU34 129.00 元 有货\n"
                        + "- 候选[1] 编号 SPU39：氨糖维 D 钙片（中老年）（氨糖 500mg、钙 300mg 与维生素 D3），"
                        + "价格 178.00 元，类目 骨骼关节，品牌 康倍适，有货，标签 关节、骨骼健康、老年人\n"
                        + "    规格：SKU50 178.00 元 有货\n"
                        + "- 候选[2] 编号 SPU24：氨糖软骨素钙片（氨糖 750mg、软骨素 250mg 与钙 200mg），"
                        + "价格 168.00 元，类目 骨骼关节，品牌 康倍适，有货，已售 860 件，标签 关节、骨骼健康\n"
                        + "    规格：SKU33 168.00 元 有货\n")
                .build();
    }

    /** 生产现场原样切片（三条低分片，全部低于重排阈值 0.20）。 */
    private static List<DocumentChunk> calciumChunks() {
        return List.of(
                chunk("KB-0048", "KB-0048_1", 0.1934, "碳酸钙 D3 咀嚼片产品说明书",
                        "《碳酸钙 D3 咀嚼片产品说明书》 > 第一章 产品概述 > 1.2 规格为每片含钙 600 毫克\n"
                        + "1.2 规格为每片含钙 600 毫克、维生素 D3 400IU，每瓶 60 片。\n"
                        + "1.3 维生素 D3 可促进钙在肠道的吸收，两者联合补充有助于钙的利用。\n"
                        + "1.4 本品不能替代药物治疗疾病，不能替代医生处方的钙剂或抗骨质疏松治疗。"),
                chunk("KB-0025", "KB-0025_1", 0.1926, "孕妇钙片产品说明书",
                        "《孕妇钙片产品说明书》 > 第一章 产品概述 > 1.2 规格为每片含柠檬酸钙 500 毫克（以钙\n"
                        + "1.2 规格为每片含柠檬酸钙 500 毫克（以钙计 100 毫克）、维生素 D3 400IU，每瓶 60 片。"),
                chunk("KB-0047", "KB-0047_3", 0.1759, "氨糖维 D 钙片（中老年）产品说明书",
                        "《氨糖维 D 钙片（中老年）产品说明书》 > 第三章 用法用量\n"
                        + "3.1 成人推荐每日 1 至 2 片，随餐服用。\n"
                        + "3.2 中国居民膳食指南建议 50 岁以上人群每日钙推荐摄入量为 1000 毫克。"));
    }

    // ---------- M2：商品行的出处是工具回执 ----------

    @Test
    void M2生产现场商品行在有回执时必须放行() {
        var tools = List.of(calciumSearch());
        // 夹具必须与回执同源：编号、规格、价格三者都能在 calciumSearch() 的回执里找到。
        // 反例（编号或金额对不上）见下一个用例。
        for (String row : List.of(
                "| 1 | 孕妇钙片（康倍适） | 柠檬酸钙 500mg + 维生素 D3 400IU，孕期哺乳期适用 · SKU34 | 129.00 元 | [2]",
                "- 规格：SKU34，129.00 元，有货 [2]",
                "- 成分：氨糖 750mg、软骨素 250mg 与钙 200mg（康倍适） · SKU33 [1]",
                "**1. 孕妇钙片**（编号 SPU25）— 康倍适 [3]")) {
            assertThat(MedicalEvidenceVerifier.verify(true, row, calciumChunks(), tools,
                    EvidenceGate.Thresholds.defaults(), java.util.Set.of()).unsupported())
                    .as(row).isEmpty();
        }
    }

    @Test
    void M2商品行数值与回执不符时必须拒() {
        var tools = List.of(calciumSearch());
        for (String row : List.of(
                // 价格改一个数字：回执里 SKU34 是 129.00
                "| 1 | 孕妇钙片（康倍适） | 柠檬酸钙 500mg + 维生素 D3 400IU · SKU34 | 139.00 元 | [2]",
                // 编号对不上商品：SPU25 是孕妇钙片，写成氨糖软骨素
                "| 1 | 氨糖软骨素钙片（康倍适） | 氨糖 750mg · SPU25 | 129.00 元 | [2]")) {
            assertThat(MedicalEvidenceVerifier.verify(true, row, calciumChunks(), tools,
                    EvidenceGate.Thresholds.defaults(), java.util.Set.of()).unsupported())
                    .as(row).isNotEmpty();
        }
    }

    @Test
    void M2没有回执时商品行不获得豁免() {
        String row = "| 1 | 孕妇钙片（康倍适） | 柠檬酸钙 500mg + 维生素 D3 400IU · SKU34 | 129.00 元 | [2]";
        assertThat(MedicalEvidenceVerifier.verify(true, row, calciumChunks(), List.of(),
                EvidenceGate.Thresholds.defaults(), java.util.Set.of()).unsupported()).as("无回执").isNotEmpty();
    }

    @Test
    void M2商品行带医学结论时必须回到医学链() {
        var tools = List.of(calciumSearch());
        for (String row : List.of(
                "| 1 | 孕妇钙片（康倍适） | 柠檬酸钙 500mg · SKU34 | 129.00 元 | 孕期每天需补钙 1000mg [2]",
                "| 1 | 孕妇钙片（康倍适） | 柠檬酸钙 500mg · SKU34 | 129.00 元 | 适合哺乳期女性 [2]",
                "| 1 | 孕妇钙片（康倍适） | 柠檬酸钙 500mg · SKU34 | 129.00 元 | 与华法林合用增加出血风险 [2]")) {
            assertThat(MedicalEvidenceVerifier.verify(true, row, calciumChunks(), tools,
                    EvidenceGate.Thresholds.defaults(), java.util.Set.of()).unsupported())
                    .as(row).isNotEmpty();
        }
    }

    // ---------- M3：疑问句不是断言 ----------

    @Test
    void M3澄清问句不构成医学事实句() {
        // 疑问句是向用户提问，不是向用户断言事实——它没有「出处」可言。
        // 判据收在「不含具体数值/编号」上：带数值的疑问句仍在确认一个事实，
        // 数值错了一样会误导用户，因此照常核验。
        for (String question : List.of(
                "需要我帮你看看益生菌粉的其他规格，或者换一个商品吗？",
                "要不要我再查一下维生素 D 补充剂的服用依据？",
                "需要我按说明书上的用法帮你加购吗？")) {
            assertThat(MedicalEvidenceVerifier.factSentences(question)).as(question).isEmpty();
            assertThat(MedicalEvidenceVerifier.verify(true, question, calciumChunks(), List.of(),
                    EvidenceGate.Thresholds.defaults(), java.util.Set.of()).unsupported()).as(question).isEmpty();
        }
    }

    @Test
    void M3带数值的疑问句仍照常核验() {
        // 反例：把「疑问句」当免检通道会放过「每日推荐摄入量为 9999IU 吗？」这类
        // 在错误数值上征求同意的句子。数值在，责任就在。
        for (String claim : List.of(
                "每日推荐摄入量为 9999IU 吗？",
                "成人推荐每日 3 粒吗？")) {
            assertThat(MedicalEvidenceVerifier.factSentences(claim)).as(claim).isNotEmpty();
        }
    }

    @Test
    void M2澄清问句里可核对的商品规格同样由回执放行() {
        String question = "需要我帮你看看孕妇钙片的其他规格，或者换一个商品吗？";
        assertThat(MedicalEvidenceVerifier.verify(true, question, calciumChunks(), List.of(calciumSearch()),
                EvidenceGate.Thresholds.defaults(), java.util.Set.of()).unsupported()).as(question).isEmpty();
    }
}
