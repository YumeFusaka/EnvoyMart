package yumefusaka.envoymart.agent.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 发现 M（§2.13）：主体在标题/引导句声明、正文承前省略时，剂量断言被判「数量主体不完整」。
 * <p>
 * 这是「检索成功却答不出」的最大单一来源——同一问题 12 轮里 4 轮因它被整篇兜底。
 * <p>
 * <b>用例 1 与用例 3 当前是红的（记录现状），R16 落地后转绿。</b>用例 2/4/5 与末尾两条
 * 安全性断言是<b>不得放宽</b>的边界——R16 放行主体继承时，它们必须保持现有判定。
 */
class MedicalSubjectInheritanceTest {

    private static DocumentChunk chunk(String docId, String chunkId, double score, String title, String content) {
        return DocumentChunk.builder().docId(docId).chunkId(chunkId).score(score).title(title).content(content).build();
    }

    /**
     * 三片取自生产现场 {@code u1003-1791831309905} 的真实命中（标题、评分、正文都是原样）。
     * <p>
     * <b>夹具必须与现场同形。</b>少一片、或把某半句数值删掉，测试就会因为「这个数字确实
     * 不在切片里」而红——那红的是夹具，不是产品，而这类假红灯最容易被误读成「修法没生效」。
     */
    private static List<DocumentChunk> vitaminD() {
        return List.of(
                chunk("KB-0016", "KB-0016_9", 0.579, "中国居民膳食营养素参考摄入量速查",
                        "《中国居民膳食营养素参考摄入量速查》 > 第三章 维生素参考摄入量\n"
                        + "3.1 脂溶性维生素。维生素 A 推荐摄入量成年男性 800 微克视黄醇活性当量每日、女性 700 微克每日，"
                        + "可耐受最高摄入量 3000 微克每日，孕期过量补充有致畸风险。"
                        + "维生素 D 推荐摄入量成人 10 微克每日（折合 400 国际单位），65 岁以上 15 微克每日，"
                        + "可耐受最高摄入量 50 微克每日（折合 2000 国际单位）。"
                        + "维生素 E 适宜摄入量 14 毫克 α-生育酚当量每日。"),
                chunk("KB-0005", "KB-0005_3", 0.565, "维生素 D3 软胶囊产品说明书",
                        "《维生素 D3 软胶囊产品说明书》 > 第三章 用法用量\n"
                        + "3.1 成人推荐每日 1 粒，随餐服用，餐后服用吸收更佳。\n"
                        + "3.2 健康成年人每日维生素 D 推荐摄入量为 400IU，可耐受最高摄入量为 2000IU。"),
                chunk("KB-0010", "KB-0010_0", 0.545, "膳食补充剂摄入上限与常见药物相互作用",
                        "《膳食补充剂摄入上限与常见药物相互作用》 > 第一章 常见营养素的每日可耐受最高摄入量\n"
                        + "1.1 维生素 D 的成人每日可耐受最高摄入量为 2000IU，长期超过该剂量应在医生监测下进行。\n"
                        + "1.2 铁的成人每日可耐受最高摄入量为 42 毫克，儿童按体重折算。"));
    }

    @Test
    void 标题声明主体后正文承前省略应被放行() {
        // R16：标题行声明的实体对其后同章节块的正文句有效。只补主体、不补数值——
        // 数值/人群/角色/约束仍逐项核对（见本类末尾两条反例）。
        String reply = "**维生素D的推荐摄入量与上限**\n\n"
                + "- 健康成年人每日推荐摄入量为 400IU，可耐受最高摄入量为 2000IU [1]。";
        var verdict = MedicalEvidenceVerifier.verify(true, reply, vitaminD());
        assertThat(verdict.unsupported()).as("标题已声明主体，正文承前省略不应判数量主体不完整").isEmpty();
    }

    @Test
    void 引导句声明主体后正文承前省略应被放行() {
        // 引导句形态在生产里极常见（「两份资料给的数字不一样：」后接两条），
        // 此前同样因主体不继承而连数字一起被删，留下悬空的引导句。
        String reply = "维生素D的推荐摄入量与上限如下：\n"
                + "- 健康成年人每日推荐摄入量为 400IU，可耐受最高摄入量为 2000IU [1]。";
        var verdict = MedicalEvidenceVerifier.verify(true, reply, vitaminD());
        assertThat(verdict.unsupported()).as("引导句已声明主体，紧随其后的列表项应继承").isEmpty();
    }

    @Test
    void 引导句自身含量值时不作为主体声明放行() {
        // 边界：含量值的句子已经是断言，不能因为「以冒号结尾」就被当成引导句跳过核验。
        String reply = "**用量说明**\n\n维生素D每日推荐摄入量为 9999IU：\n"
                + "- 健康成年人每日推荐摄入量为 400IU [1]。";
        var verdict = MedicalEvidenceVerifier.verify(true, reply, vitaminD());
        assertThat(verdict.unsupported()).as("含量值的句子必须照常核验").isNotEmpty();
    }

    @Test
    void 句内声明主体与同一句内联声明仍应放行() {
        for (String reply : List.of(
                "- 健康成年人每日维生素D推荐摄入量为 400IU，可耐受最高摄入量为 2000IU [1]。",
                "维生素D：健康成年人每日推荐摄入量为 400IU，可耐受最高摄入量为 2000IU [1]。")) {
            assertThat(MedicalEvidenceVerifier.verify(true, reply, vitaminD()).unsupported())
                    .as("句内有主体，不得因 R16 以外的原因被拒：%s", reply).isEmpty();
        }
    }

    @Test
    void 主体继承不得放行未声明来源的本品断言() {
        var manual = DocumentChunk.builder().docId("KB-0035").chunkId("KB-0035_4").score(0.9)
                .title("孕期 DHA 藻油软胶囊产品说明书")
                .content("《孕期 DHA 藻油软胶囊产品说明书》 > 第四章 相互作用与注意事项\n"
                        + "4.1 本品所含 DHA 具有轻微抗血小板聚集作用，与华法林、阿司匹林等抗凝或抗血小板药物合用时可能增加出血风险，合用前须咨询医师。")
                .build();
        String reply = "**注意事项**\n\n- 本品所含 DHA 可影响凝血功能 [1]。";
        var verdict = MedicalEvidenceVerifier.verify(true, reply, List.of(manual));
        assertThat(verdict.unsupported()).as("「本品」没有声明来源归属，主体继承不得把它一并放行").isNotEmpty();
    }

    @Test
    void 主体继承不得放行数值或人群不一致() {
        String wrongNumber = "**维生素D的推荐摄入量与上限**\n\n- 健康成年人每日推荐摄入量为 800IU [1]。";
        assertThat(MedicalEvidenceVerifier.verify(true, wrongNumber, vitaminD()).unsupported())
                .as("主体可继承，数值仍须逐项核对").isNotEmpty();
        String wrongPopulation = "**维生素D的推荐摄入量与上限**\n\n- 婴幼儿每日推荐摄入量为 400IU [1]。";
        assertThat(MedicalEvidenceVerifier.verify(true, wrongPopulation, vitaminD()).unsupported())
                .as("主体可继承，人群范围仍须逐项核对").isNotEmpty();
    }

    /**
     * 生产现场回归（N1 §2.14）：{@code u1003-1791831309905} 等 4 轮的回复被整篇替换成兜底拒答。
     * <p>
     * 现场回复的形态是「标题行声明主体 + 列表项承前省略」的<b>标准 Markdown</b>，
     * 引用的三片里两片高分、内容也确实覆盖（400IU / 2000IU 都能在切片里找到）。
     * 它不是幻觉，也不是检索失败——是主体继承缺口把整篇吃掉了。
     * <p>
     * 与上面两条用例的区别：这里用的是<b>生产原样文本</b>（含「折合国际单位」这类换写、
     * 含多个人群分档），而不是为了测试简化过的句子。
     */
    @Test
    void 生产现场维生素D剂量回复不得被整篇拒绝() throws java.io.IOException {
        String reply = "**维生素D的推荐摄入量与上限**\n\n"
                + "- 成人推荐摄入量：每日 10 微克，折合 400 国际单位 [1]；\n"
                + "65 岁以上为每日 15 微克 [1]。\n"
                + "- 成人可耐受最高摄入量：每日 50 微克，折合 2000 国际单位 [1]。\n"
                + "- 产品说明书中的表述与上述一致：健康成年人每日推荐 400IU，可耐受最高摄入量 2000IU [2]。\n"
                + "- 补充剂资料也给出同一上限：成人每日可耐受最高摄入量 2000IU [3]。";
        var verdict = MedicalEvidenceVerifier.verify(true, reply, vitaminD());
        // R16 的净效果：这一轮从「5 句全删 → 整篇兜底拒答」变成「答 4 句、少 1 句」。
        // 残留的那句是「65 岁以上为每日 15 微克」——承前省略主体 + 人群分档的列表续行。
        //
        // 缺口位置已定位到 `MedicalDoseAssertions.subjectText()`：人群前缀只剥「成人/儿童」
        // 这类词（`POPULATION_PREFIX`），不剥「65 岁以上」这种「数字+岁+以上」形态，于是
        // 系动词「为」露到句首，被 `SUBJECT_HEAD` 当成裸主体解析出来——探针实测
        // `entities("65 岁以上为每日 15 微克")` 返回 `[为]`，与切片的 `[维生素d]` 比对必然不一致。
        //
        // 本条**记录现状**，不是认可现状：修掉解析缺口后，这个断言应改成 `.isEmpty()`。
        // 之所以不在这里顺手改判据——主体继承的边界是安全性边界，放宽要先想清它同时放行了什么
        //（R5 与 R16 第一版各被打回一次，KG-02/KG-04/KG-11 守着那条线）。
        assertThat(verdict.unsupported())
                .as("R16 已覆盖同形态的前四句；这一句是「人群分档 + 系动词」的解析残留")
                .containsExactly("65 岁以上为每日 15 微克 [1]。");
    }
}
