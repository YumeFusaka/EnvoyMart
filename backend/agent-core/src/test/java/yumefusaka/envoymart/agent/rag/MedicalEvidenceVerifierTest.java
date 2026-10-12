package yumefusaka.envoymart.agent.rag;

import org.junit.jupiter.api.Test;
import yumefusaka.envoymart.agent.llm.ToolExecution;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MedicalEvidenceVerifierTest {

    private static ToolExecution productTool(boolean success) {
        return ToolExecution.builder().tool("product_search").success(success)
                .rawData(List.of(Map.of("summary", Map.of("id", 26), "skus", List.of(
                        Map.of("skuId", 38, "specText", "60片/瓶", "price", 3900),
                        Map.of("skuId", 39, "specText", "30片/瓶", "price", 2500)))))
                .build();
    }


    @Test
    void KG08症状处置必须按同切片强证据核验() {
        String answer = "服用期间如出现异常出血、皮肤瘀斑等表现应及时就医 [1]。";
        var unrelated = DocumentChunk.builder().docId("KB-0035").chunkId("KB-0035_4").score(0.9)
                .title("孕期 DHA 藻油软胶囊产品说明书").content("《孕期 DHA 藻油软胶囊产品说明书》 > 第四章 相互作用与注意事项\n4.1 本品所含 DHA 具有轻微抗血小板聚集作用，与华法林、阿司匹林等抗凝或抗血小板药物合用时可能增加出血风险，合用前须咨询医师。\n4.2 准备接受手术者建议提前两周停用，并告知主刀医师正在服用本品。\n4.3 出血性疾病患者、正在服用抗凝药物者使用前应咨询医师。").build();
        var correct = DocumentChunk.builder().docId("KB-0035").chunkId("KB-0035_5").score(0.9)
                .title("孕期 DHA 藻油软胶囊产品说明书").content("《孕期 DHA 藻油软胶囊产品说明书》 > 第四章 相互作用与注意事项 > 4.4 部分人群服用后可能出现嗳气\n4.4 部分人群服用后可能出现嗳气、口中异味、腹泻等不适，随餐服用或减量后多可缓解。\n4.5 对本品任一成分过敏者禁用。\n4.6 本品不能替代均衡膳食，出现异常出血、皮肤瘀斑等表现应及时就医。").build();
        var weak = DocumentChunk.builder().docId("KB-0035").chunkId("KB-0035_5").score(0.19916348767194514)
                .title(correct.getTitle()).content(correct.getContent()).build();
        assertThat(MedicalEvidenceVerifier.factSentences(answer)).containsExactly(answer);
        assertThat(MedicalEvidenceVerifier.verify(true, answer, List.of(unrelated)).unsupported()).containsExactly(answer);
        assertThat(MedicalEvidenceVerifier.verify(true, answer, List.of(correct)).unsupported()).isEmpty();
        assertThat(MedicalEvidenceVerifier.verify(true, answer, List.of(weak)).unsupported()).containsExactly(answer);
        for (String changed : List.of(answer.replace("异常出血", "呕血"), answer.replace("应及时就医", "应继续服用"))) {
            assertThat(MedicalEvidenceVerifier.factSentences(changed)).containsExactly(changed);
            assertThat(MedicalEvidenceVerifier.verify(true, changed, List.of(correct)).unsupported()).containsExactly(changed);
        }
        assertThat(MedicalEvidenceVerifier.factSentences("如有疑问请咨询医师或药师。")).isEmpty();
    }

    @Test
    void G06版本优先裁定必须有原文替代授权而客观规格可并列() {
        var older = DocumentChunk.builder().docId("KB-0038").chunkId("KB-0038_0").score(0.9).version("v2026.04")
                .title("铁叶酸片产品说明书").content("《铁叶酸片产品说明书》 > 第一章 产品概述\n1.1 本品为膳食营养补充剂，含铁与叶酸。\n1.2 规格为每片含铁 15 毫克（以元素铁计）与叶酸 400 微克。\n1.3 本品不能替代药物治疗缺铁性贫血。").build();
        var newer = DocumentChunk.builder().docId("KB-0009").chunkId("KB-0009_0").score(0.9).version("v2026.05")
                .title("铁叶酸片产品说明书").content("《铁叶酸片产品说明书》 > 第一章 产品概述\n1.1 本品为膳食营养补充剂，主要成分为富马酸亚铁与叶酸。\n1.2 规格为每片含铁 10 毫克、叶酸 400 微克，每瓶 60 片。\n1.3 本品不能替代药物治疗疾病，不能替代医生处方的补铁制剂。").build();
        String arbitrary = "知识库中两份《铁叶酸片产品说明书》对铁含量给出了不同数字，按版本取较新的一版：[1][2]";
        assertThat(MedicalEvidenceVerifier.factSentences(arbitrary)).containsExactly(arbitrary);
        assertThat(MedicalEvidenceVerifier.verify(true, arbitrary, List.of(older, newer)).unsupported()).containsExactly(arbitrary);
        String contrast = "每片含铁15毫克与叶酸400微克 [1]。每片含铁10毫克与叶酸400微克 [2]。";
        assertThat(MedicalEvidenceVerifier.verify(true, contrast, List.of(older, newer)).unsupported()).isEmpty();
        String authorized = "铁叶酸片产品说明书v2026.05明确替代v2026.04，应按新版说明书执行 [1]。";
        var replacement = DocumentChunk.builder().docId("revision").chunkId("revision_0").score(0.9)
                .title("铁叶酸片产品说明书").content("铁叶酸片产品说明书v2026.05明确替代v2026.04，应按新版说明书执行。").build();
        assertThat(MedicalEvidenceVerifier.factSentences(authorized)).containsExactly(authorized);
        assertThat(MedicalEvidenceVerifier.verify(true, authorized, List.of(replacement)).unsupported()).isEmpty();
    }

    @Test
    void KG02间隔复述句允许两者指代但数量与约束仍逐项核对() {
        var source = DocumentChunk.builder().docId("KB-0010").chunkId("KB-0010_5").score(0.9)
                .title("膳食补充剂摄入上限与常见药物相互作用")
                .content("《膳食补充剂摄入上限与常见药物相互作用》 > 第二章 常见药物相互作用\n2.3 钙剂与四环素类、喹诺酮类抗生素合用会形成难吸收的络合物，两者服用时间应间隔 2 小时以上。").build();
        String interval = "处理办法是错开时间：两者服用时间应间隔至少 2 小时以上 [1]。";
        assertThat(MedicalEvidenceVerifier.verify(true, interval, List.of(source)).unsupported()).isEmpty();
        String wrongHours = "处理办法是错开时间：两者服用时间应间隔至少 4 小时以上 [1]。";
        assertThat(MedicalEvidenceVerifier.verify(true, wrongHours, List.of(source)).unsupported()).containsExactly(wrongHours);
        String exact = "处理办法是错开时间：两者服用时间应恰好间隔 2 小时 [1]。";
        assertThat(MedicalEvidenceVerifier.verify(true, exact, List.of(source)).unsupported()).containsExactly(exact);
        String noPronoun = "服用时间应间隔至少 2 小时以上 [1]。";
        assertThat(MedicalEvidenceVerifier.verify(true, noPronoun, List.of(source)).unsupported()).containsExactly(noPronoun);
    }

    @Test
    void G17来源展示框架不进入临床数量参与组但必须匹配引用商品() {
        var source = DocumentChunk.builder().docId("KB-0022").chunkId("KB-0022_8").score(0.9)
                .title("复合维生素矿物质片产品说明书")
                .content("4.3 本品所含钙可影响左甲状腺素、四环素类抗生素、喹诺酮类抗生素的吸收，与上述药物合用时应间隔至少2小时。").build();
        String claim = "复合维生素矿物质片说明书里也写明：所含钙可影响四环素类、喹诺酮类抗生素的吸收，与这些药物合用时应间隔至少2小时 [1]。";
        assertThat(MedicalEvidenceVerifier.verify(true, claim, List.of(source)).unsupported()).isEmpty();
        assertThat(MedicalEvidenceVerifier.verify(true, claim.replace("复合维生素矿物质片说明书", "儿童钙片说明书"), List.of(source)).unsupported()).isNotEmpty();
        assertThat(MedicalEvidenceVerifier.verify(true, claim.replace("所含钙", "维生素D与钙"), List.of(source)).unsupported()).isNotEmpty();
    }

    @Test
    void KG10话语框架后的裸主体参与间隔核验() {
        var source = DocumentChunk.builder().docId("KB-0010").chunkId("KB-0010_5").score(0.9)
                .title("膳食补充剂摄入上限与常见药物相互作用")
                .content("2.5 益生菌与抗生素同服会显著降低活菌存活率，建议错开 2 小时以上。").build();
        // 引导框架（含冒号）不应吞掉词表外的裸主体（“抗生素”），间隔断言仍要能核验
        String framed = "平台知识库的原文依据是：益生菌与抗生素同服会显著降低活菌存活率，建议错开 2 小时以上 [1]。";
        assertThat(MedicalEvidenceVerifier.verify(true, framed, List.of(source)).unsupported()).isEmpty();
        // 框架后只剩单个主体时维持拒答，不能把“建议”等引导词当第二个主体
        String singleSubject = "平台知识库的原文依据是：建议钙错开 2 小时以上 [1]。";
        assertThat(MedicalEvidenceVerifier.verify(true, singleSubject, List.of(source)).unsupported()).containsExactly(singleSubject);
        // 无框架的原句行为不变
        String plain = "益生菌与抗生素同服会显著降低活菌存活率，建议错开 2 小时以上 [1]。";
        assertThat(MedicalEvidenceVerifier.verify(true, plain, List.of(source)).unsupported()).isEmpty();
    }

    @Test
    void KG09具体间隔引导词不进入裸主体() {
        var source = DocumentChunk.builder().docId("KB-0010").chunkId("KB-0010_5").score(0.9)
                .title("膳食补充剂摄入上限与常见药物相互作用")
                .content("2.3 钙剂与四环素类、喹诺酮类抗生素合用会形成难吸收的络合物，两者服用时间应间隔 2 小时以上。").build();
        String framed = "具体间隔：钙剂与四环素类、喹诺酮类抗生素合用会形成难吸收的络合物，两者服用时间应间隔 2 小时以上 [1]。";
        assertThat(MedicalEvidenceVerifier.verify(true, framed, List.of(source)).unsupported()).isEmpty();
        // 数量不符仍拒：“具体”不影响数量核验
        String wrong = "具体间隔：钙剂与四环素类、喹诺酮类抗生素合用会形成难吸收的络合物，两者服用时间应间隔 4 小时以上 [1]。";
        assertThat(MedicalEvidenceVerifier.verify(true, wrong, List.of(source)).unsupported()).containsExactly(wrong);
    }

    @Test
    void 商品规格金额仅由同一SKU结构化产物豁免() {
        for (String text : List.of("SKU38 60片39元。", "SPU26 SKU38，规格60片/瓶，单价39.00元。")) {
            assertThat(MedicalEvidenceVerifier.verify(true, text, List.of(), List.of(productTool(true)))
                    .unsupported()).as(text).isEmpty();
        }
        for (String text : List.of("SKU38 30片25元。", "SKU38 60片99元。", "SKU99 60片39元。",
                "SKU38每日服用60片，39元。", "SKU38 60片39元，与华法林合用增加出血风险。",
                "SKU38 60片39元，可以安全同服。", "SKU38 60片39元，有助于降低胆固醇。",
                "SKU38 60片39元，适合哺乳期女性。", "SKU38 60片39元，肺病患者可正常食用。")) {
            assertThat(MedicalEvidenceVerifier.verify(true, text, List.of(), List.of(productTool(true)))
                    .unsupported()).as(text).hasSize(1);
        }
        assertThat(MedicalEvidenceVerifier.verify(true, "SKU38 60片39元。", List.of(),
                List.of(productTool(false))).unsupported()).hasSize(1);
        assertThat(MedicalEvidenceVerifier.verify(true, "SKU38 60片39元。", List.of(), List.of(
                ToolExecution.builder().tool("product_search").success(true)
                        .entities(List.of("SKU38", "60片", "39元")).build())).unsupported()).hasSize(1);
    }

    private record ProductRow(Map<String, Object> summary, List<SkuRow> skus) { }
    private record SkuRow(Long skuId, String specText, Long price) { }

    @Test
    void 商品记录对象与Map沿用同一结构化规格契约() {
        var tool = ToolExecution.builder().tool("product_search").success(true)
                .rawData(List.of(new ProductRow(Map.of("id", 26), List.of(new SkuRow(38L, "60片/瓶", 3900L)))))
                .build();
        assertThat(MedicalEvidenceVerifier.verify(true, "SPU26 SKU38 60片39元。", List.of(), List.of(tool))
                .unsupported()).isEmpty();
    }

    @Test
    void 错误切片不能支撑医学风险句() {
        var evidence = List.of(
                doc("鱼油说明书", "出血性疾病患者使用前应咨询医师。", false),
                doc("鱼油说明书", "本品具有抗血小板作用，与华法林合用可能增加出血风险。", false));

        var verdict = MedicalEvidenceVerifier.verify(
                "哺乳期女性吃深海鱼油，同时需要吃抗凝药有什么提示？",
                "本品具有抗血小板作用，与华法林合用可能增加出血风险 [1]。",
                evidence);

        assertThat(verdict.unsupported()).containsExactly(
                "本品具有抗血小板作用，与华法林合用可能增加出血风险 [1]。");
    }

    @Test
    void 不能用切片局部内容证明整份说明书没有某项() {
        var evidence = List.of(
                doc("铁叶酸片产品说明书", "铁与钙存在吸收竞争，建议间隔两小时。", false),
                doc("铁叶酸片产品说明书", "茶、咖啡、牛奶会抑制铁吸收。", false));

        var verdict = MedicalEvidenceVerifier.verify(
                "铁叶酸片中的铁会影响左旋多巴吸收吗？",
                "铁叶酸片自身说明书没有涉及左旋多巴 [1][2]。",
                evidence);

        assertThat(verdict.unsupported()).hasSize(1);
    }

    @Test
    void 不同商品的证据不能迁移成当前商品结论() {
        var evidence = List.of(
                doc("磷虾油软胶囊产品说明书", "正在服用抗凝或抗血小板药物者，使用前请咨询医师。", false),
                doc("深海鱼油软胶囊产品说明书", "与阿司匹林合用可能增加出血风险。", false));

        var verdict = MedicalEvidenceVerifier.verify(
                "磷虾油和阿司匹林一起用要注意什么？",
                "磷虾油与阿司匹林同用可能增加出血风险 [2]。",
                evidence);

        assertThat(verdict.unsupported()).hasSize(1);
    }

    @Test
    void 图谱关系必须由图谱证据支撑() {
        var evidence = List.of(doc("深海鱼油软胶囊产品说明书",
                "与抗凝药物合用可能增加出血风险。", false));

        var verdict = MedicalEvidenceVerifier.verify(
                "深海鱼油和抗凝药合用有什么风险？",
                "知识图谱中深海鱼油与抗凝药物有关联，可能增加出血风险 [1]。",
                evidence);

        assertThat(verdict.unsupported()).hasSize(1);
    }

    @Test
    void 同实体同关系同切片的医学句保留() {
        var evidence = List.of(doc("深海鱼油软胶囊产品说明书",
                "本品具有抗血小板作用，与华法林合用可能增加出血风险。", false));

        var verdict = MedicalEvidenceVerifier.verify(
                "深海鱼油和抗凝药合用有什么风险？",
                "深海鱼油与华法林合用可能增加出血风险 [1]。",
                evidence);

        assertThat(verdict.unsupported()).isEmpty();
    }

    @Test
    void 不能拼接不同商品切片制造支撑() {
        var evidence = List.of(
                doc("磷虾油说明书", "使用前请咨询医师。", false),
                doc("鱼油说明书", "与阿司匹林合用可能增加出血风险。", false));
        assertThat(MedicalEvidenceVerifier.verify("磷虾油和阿司匹林怎么用？",
                "磷虾油与阿司匹林合用可能增加出血风险 [1][2]。", evidence).unsupported()).hasSize(1);
    }

    @Test
    void 回答新引入的实体也必须核对() {
        var evidence = List.of(doc("深海鱼油说明书", "与华法林合用可能增加出血风险。", false));
        assertThat(MedicalEvidenceVerifier.verify("深海鱼油有什么风险？",
                "磷虾油与阿司匹林合用可能增加出血风险 [1]。", evidence).unsupported()).hasSize(1);
    }

    @Test
    void 数值不同不能凭同类剂量词放行() {
        var evidence = List.of(doc("维生素D3说明书", "每日上限为2000IU。", false));
        assertThat(MedicalEvidenceVerifier.verify("维生素D3每日上限是多少？",
                "维生素D3每日上限为9000IU [1]。", evidence).unsupported()).hasSize(1);
        assertThat(MedicalEvidenceVerifier.verify("维生素D3每日上限是多少？",
                "维生素D3每日上限为2000IU [1]。", evidence).unsupported()).isEmpty();
    }

    @Test
    void 否定风险不能被增加风险的证据支持() {
        var evidence = List.of(doc("深海鱼油说明书", "与华法林合用可能增加出血风险。", false));
        assertThat(MedicalEvidenceVerifier.verify("深海鱼油和华法林合用有什么风险？",
                "深海鱼油与华法林合用不会增加出血风险 [1]。", evidence).unsupported()).hasSize(1);
    }

    @Test
    void 原文否定关系不得改写为肯定关系() {
        for (String[] pair : List.of(
                new String[]{"深海鱼油与华法林合用不会增加出血风险。", "深海鱼油与华法林合用可能增加出血风险 [1]。"},
                new String[]{"深海鱼油与华法林合用不增加出血风险。", "深海鱼油与华法林合用可能增加出血风险 [1]。"},
                new String[]{"深海鱼油与华法林合用没有出血风险。", "深海鱼油与华法林合用有出血风险 [1]。"},
                new String[]{"深海鱼油与华法林合用无风险。", "深海鱼油与华法林合用可能增加出血风险 [1]。"},
                new String[]{"钙剂与铁剂合用不影响吸收。", "钙剂与铁剂合用会影响吸收 [1]。"},
                new String[]{"钙剂与铁剂合用不会抑制吸收。", "钙剂与铁剂合用会抑制吸收 [1]。"})) {
            assertThat(MedicalEvidenceVerifier.verify(true, pair[1],
                    List.of(doc("同服说明", pair[0], false))).unsupported()).as(pair[0]).hasSize(1);
        }
    }

    @Test
    void 同一切片中的不同关系方向不得串用() {
        var evidence = List.of(doc("深海鱼油说明书",
                "深海鱼油与华法林合用不会影响吸收，但可能增加出血风险。", false));
        assertThat(MedicalEvidenceVerifier.verify(true,
                "深海鱼油与华法林合用可能增加出血风险 [1]。", evidence).unsupported()).isEmpty();
        assertThat(MedicalEvidenceVerifier.verify(true,
                "深海鱼油与华法林合用有出血风险 [1]。", evidence).unsupported()).isEmpty();
        assertThat(MedicalEvidenceVerifier.verify(true,
                "深海鱼油与华法林合用会影响吸收 [1]。", evidence).unsupported()).hasSize(1);
        var directions = List.of(doc("同服说明", "钙剂与铁剂合用降低吸收，但可能增加出血风险。", false));
        assertThat(MedicalEvidenceVerifier.verify(true,
                "钙剂与铁剂合用增加吸收 [1]。", directions).unsupported()).hasSize(1);
        assertThat(MedicalEvidenceVerifier.verify(true,
                "钙剂与铁剂合用降低出血风险 [1]。", directions).unsupported()).hasSize(1);
        assertThat(MedicalEvidenceVerifier.verify(true,
                "钙剂与铁剂合用影响吸收 [1]。", directions).unsupported()).isEmpty();
    }

    @Test
    void 原文和回答同为否定关系时保留() {
        for (String text : List.of("深海鱼油与华法林合用不会增加出血风险",
                "深海鱼油与华法林合用没有出血风险", "钙剂与铁剂合用不影响吸收")) {
            assertThat(MedicalEvidenceVerifier.verify(true, text + " [1]。",
                    List.of(doc("同服说明", text + "。", false))).unsupported()).isEmpty();
        }
    }

    @Test
    void 普通医学关系不要求图谱但图谱声明要求支撑切片() {
        var evidence = List.of(doc("深海鱼油说明书", "与华法林合用可能增加出血风险。", false),
                doc("钙片说明书", "每日1片。", true));
        assertThat(MedicalEvidenceVerifier.verify("深海鱼油和华法林有什么关系？",
                "深海鱼油与华法林存在合用关系，可能增加出血风险 [1]。", evidence).unsupported()).isEmpty();
        assertThat(MedicalEvidenceVerifier.verify("深海鱼油和华法林有什么关系？",
                "图谱显示深海鱼油与华法林合用可能增加出血风险 [1][2]。", evidence).unsupported()).hasSize(1);
    }

    @Test
    void 医学判错不能把同句重复计入评测分母() {
        var evidence = List.of(doc("磷虾油说明书", "正在服用抗血小板药物者请咨询医师。", false));
        var sample = new GroundingEvaluator.Sample("medical", GroundingFixtures.Kind.ANSWERABLE, false,
                List.of("抗血小板"), evidence, "磷虾油具有抗血小板作用，与阿司匹林合用可能增加出血风险 [1]。",
                EvidenceGate.Level.SUFFICIENT, false, List.of(), false, "磷虾油和阿司匹林能合用吗？");
        var outcome = GroundingEvaluator.evaluate(List.of(sample)).cases().getFirst();
        assertThat(outcome.factSentences()).isEqualTo(1);
        assertThat(outcome.checks()).isEqualTo(1);
        assertThat(outcome.unsupported()).isEqualTo(1);
        assertThat(outcome.wrongCitations()).isEqualTo(1);
        assertThat(outcome.citations()).hasSize(1);
    }

    @Test
    void 常见营养素问法进入医学严格模式() {
        assertThat(MedicalEvidenceVerifier.isMedicalQuestion("镁每天吃多少？")).isTrue();
        assertThat(MedicalEvidenceVerifier.isMedicalQuestion("锌和抗生素能一起吃吗？")).isTrue();
        assertThat(MedicalEvidenceVerifier.isMedicalQuestion("硒补充剂有什么禁忌？")).isTrue();
        assertThat(MedicalEvidenceVerifier.isMedicalQuestion("头疼怎么办")).isTrue();
        assertThat(MedicalEvidenceVerifier.isMedicalQuestion("肚子不舒服吃什么药")).isTrue();
        assertThat(MedicalEvidenceVerifier.isMedicalQuestion("这两种一起吃行吗")).isTrue();
        assertThat(MedicalEvidenceVerifier.isMedicalQuestion("吃了以后恶心怎么办")).isTrue();
        for (String question : List.of("那每天几粒", "每日几粒", "每次吃多少片", "每天吃几颗", "每日多少袋",
                "多久吃一次", "多长时间服用一次", "吃几片", "几毫克")) {
            assertThat(MedicalEvidenceVerifier.isMedicalQuestion(question)).as(question).isTrue();
        }
        assertThat(MedicalEvidenceVerifier.isMedicalQuestion("订单退款怎么办")).isFalse();
        assertThat(MedicalEvidenceVerifier.isMedicalQuestion("每天发货几件")).isFalse();
    }

    @Test
    void 纯查询边界和遵医嘱提醒保留但不得转成安全结论() {
        var weak = doc("用药说明", "请遵医嘱。", false).toBuilder().score(0.17).reranked(true).build();
        assertThat(MedicalEvidenceVerifier.verify(true, "⚠️ 图谱中「未收录」不等于「无风险」或「可安全合用」；", List.of())
                .unsupported()).isEmpty();
        assertThat(MedicalEvidenceVerifier.verify(true, "- 所有用药安排请务必遵医嘱，尤其存在慢性病或多药联用情况时[1]。", List.of(weak))
                .unsupported()).isEmpty();
        assertThat(MedicalEvidenceVerifier.verify(true, "图谱未收录，因此鱼油与华法林可以安全同服。", List.of())
                .unsupported()).hasSize(1);
        assertThat(MedicalEvidenceVerifier.verify(true, "请遵医嘱每日10粒 [1]。", List.of(weak))
                .unsupported()).hasSize(1);
    }

    @Test
    void 粒和片也属于必须逐字核对的剂量单位() {
        var evidence = List.of(doc("钙片说明书", "成人每日1粒。", false));
        assertThat(MedicalEvidenceVerifier.verify("钙片每天吃多少？",
                "成人每日10粒 [1]。", evidence).unsupported()).hasSize(1);
        assertThat(MedicalEvidenceVerifier.verify("钙片每天吃多少？",
                "成人每日1粒 [1]。", List.of(doc("钙片说明书", "成人每日10粒。", false))).unsupported()).hasSize(1);
    }

    @Test
    void 医学事实不能省略或编造引用但咨询提示保留() {
        var evidence = List.of(doc("深海鱼油说明书", "与华法林合用可能增加出血风险。", false));
        for (String answer : List.of("深海鱼油与华法林合用可能增加出血风险。",
                "深海鱼油与华法林合用可能增加出血风险 [99]。")) {
            assertThat(MedicalEvidenceVerifier.verify("深海鱼油和华法林能同服吗？", answer, evidence)
                    .unsupported()).hasSize(1);
        }
        assertThat(MedicalEvidenceVerifier.verify("深海鱼油和华法林能同服吗？",
                "请咨询医师或药师并遵医嘱安排用药。", evidence).unsupported()).isEmpty();
    }

    @Test
    void 表格事实与安全同服结论不能通过格式绕过医学闸门() {
        var evidence = List.<DocumentChunk>of();
        assertThat(MedicalEvidenceVerifier.verify(true, "| 鱼油 | 每日10粒 |", evidence)
                .unsupported()).hasSize(1);
        assertThat(MedicalEvidenceVerifier.verify(true, "## 鱼油每日10粒", evidence)
                .unsupported()).hasSize(1);
        for (String reply : List.of("鱼油与华法林可以安全同服。", "鱼油与华法林同服是安全的。", "鱼油与华法林没有冲突。")) {
            assertThat(MedicalEvidenceVerifier.verify(true, reply, evidence).unsupported()).as(reply).hasSize(1);
        }
        assertThat(MedicalEvidenceVerifier.verify(true, "| 商品 | 价格 |", evidence)
                .unsupported()).isEmpty();
    }

    @Test
    void 弱证据的合法编号不等于可支撑医学结论且不能改变其他编号() {
        var weak = doc("鱼油说明书", "鱼油每日10粒。", false).toBuilder().score(0.17).reranked(true).build();
        var strong = doc("钙片说明书", "钙片每日1粒。", false).toBuilder().score(0.8).reranked(true).build();
        var verdict = MedicalEvidenceVerifier.verify(true,
                "鱼油每日10粒 [1]。钙片每日1粒 [2]。平台没有查到明确依据，请咨询医师。", List.of(weak, strong));
        assertThat(verdict.unsupported()).containsExactly("鱼油每日10粒 [1]。");
    }

    @Test
    void 医学引用沿用配置阈值与对应图谱切片的豁免() {
        var chunk = doc("鱼油说明书", "鱼油每日10粒。", false).toBuilder().score(0.30).reranked(true).build();
        var thresholds = new EvidenceGate.Thresholds(0.45, 0.40);
        assertThat(MedicalEvidenceVerifier.verify(true, "鱼油每日10粒 [1]。", List.of(chunk), List.of(), thresholds, java.util.Set.of())
                .unsupported()).hasSize(1);
        assertThat(MedicalEvidenceVerifier.verify(true, "鱼油每日10粒 [1]。", List.of(chunk), List.of(), thresholds, java.util.Set.of(chunk.getChunkId()))
                .unsupported()).isEmpty();
    }

    @Test
    void 超长引用和空证据槽位按无有效依据拒绝而非审核崩溃() {
        assertThat(MedicalEvidenceVerifier.verify(true, "鱼油每日10粒 [999999999999999999999]。", List.of())
                .unsupported()).hasSize(1);
        assertThat(MedicalEvidenceVerifier.verify(true, "鱼油每日10粒 [1]。", java.util.Arrays.asList((DocumentChunk) null))
                .unsupported()).hasSize(1);
    }

    @Test
    void 短剂量句也必须具备有效医学引用() {
        for (String reply : List.of("每日10粒", "钙每日2克")) {
            assertThat(MedicalEvidenceVerifier.verify(true, reply, List.of()).unsupported())
                    .as(reply).containsExactly(reply);
        }
        assertThat(MedicalEvidenceVerifier.verify(true, "请遵医嘱", List.of()).unsupported()).isEmpty();
    }

    @Test
    void 安全肯定结论不能借用同服增加出血风险的来源() {
        var evidence = List.of(doc("鱼油与华法林说明书", "鱼油与华法林同服可能增加出血风险。", false));
        for (String claim : List.of("鱼油与华法林可以安全同服 [1]。", "鱼油与华法林同服是安全的 [1]。", "鱼油与华法林没有冲突 [1]。")) {
            assertThat(MedicalEvidenceVerifier.verify(true, claim, evidence).unsupported()).as(claim).hasSize(1);
        }
    }

    @Test
    void 风险方向反转必须拒绝且非医学风险不误触() {
        var evidence = List.of(doc("深海鱼油说明书", "与华法林合用可能增加出血风险。", false));
        assertThat(MedicalEvidenceVerifier.verify("深海鱼油和华法林能同服吗？",
                "深海鱼油与华法林合用可降低出血风险 [1]。", evidence).unsupported()).hasSize(1);
        assertThat(MedicalEvidenceVerifier.isMedicalQuestion("订单退款风险如何控制？")).isFalse();
    }

    @Test
    void 段落末尾引用不能让医学拒绝句重复计入分母() {
        var evidence = List.of(doc("钙片说明书", "钙片成人每日1粒。", false));
        var sample = new GroundingEvaluator.Sample("paragraph", GroundingFixtures.Kind.ANSWERABLE,
                false, List.of("钙片"), evidence, "钙片每日10粒。钙片成人每日1粒 [1]。",
                EvidenceGate.Level.SUFFICIENT, false, List.of(), false, "钙片每天吃多少？");
        var outcome = GroundingEvaluator.evaluate(List.of(sample)).cases().getFirst();
        assertThat(outcome.factSentences()).isEqualTo(2);
        assertThat(outcome.unsupported()).isEqualTo(1);
    }

    @Test
    void 缺少前置剂量的引用残句不能借源文的剂量获得支撑() {
        var evidence = List.of(doc("维生素D说明书", "维生素D成人每日可耐受最高摄入量为2000IU，长期超过该剂量应在医生监测下进行。", false));
        var verdict = MedicalEvidenceVerifier.verify(true, "长期超过这个剂量，建议在医生监测下进行 [1]。", evidence);
        assertThat(verdict.unsupported()).containsExactly("长期超过这个剂量，建议在医生监测下进行 [1]。");
    }

    @Test
    void 被拒绝的剂量前句不能给后续指代授予事实许可() {
        var evidence = List.of(doc("维生素D说明书", "维生素D成人每日可耐受最高摄入量为2000IU，长期超过该剂量应在医生监测下进行。", false));
        var verdict = MedicalEvidenceVerifier.verify(true,
                "维生素D成人每日上限为4000IU [1]。长期超过这个剂量，建议在医生监测下进行 [1]。", evidence);
        assertThat(verdict.unsupported()).hasSize(2);
    }

    @Test
    void 剂量指代只能绑定同段已支撑的唯一实体剂量且当前引用仍须单切片支撑() {
        var evidence = List.of(
                doc("维生素D说明书", "维生素D成人每日可耐受最高摄入量为2000IU，长期超过该剂量应在医生监测下进行。", false),
                doc("铁剂说明书", "铁的成人每日可耐受最高摄入量为42毫克，长期超过该剂量应在医生监测下进行。", false));
        String previous = "维生素D成人每日上限为2000IU [1]。";
        assertThat(MedicalEvidenceVerifier.verify(true,
                previous + "长期超过这个剂量，建议在医生监测下进行 [1]。", evidence).unsupported()).isEmpty();
        assertThat(MedicalEvidenceVerifier.verify(true,
                previous + "长期超过这个剂量，建议在医生监测下进行 [2]。", evidence).unsupported()).hasSize(1);
        assertThat(MedicalEvidenceVerifier.verify(true,
                previous + "\n\n长期超过这个剂量，建议在医生监测下进行 [1]。", evidence).unsupported()).hasSize(1);
    }

    @Test
    void 内联强调和链接不能拆散实体数值单位或否定关系绕过审核() {
        var dose = List.of(doc("维生素D说明书", "维生素D成人每日上限为2000IU。", false));
        for (String answer : List.of("维生素D每日上限为**9000** IU [1]。", "维生素D每日上限为`9000` IU。",
                "维生素D每日上限为[9000](https://example.invalid) IU [1]。", "维生素D每日上限为2000μg [1]。")) {
            assertThat(MedicalEvidenceVerifier.verify(true, answer, dose).unsupported()).as(answer).hasSize(1);
        }
        var relation = List.of(doc("鱼油说明书", "鱼油与华法林合用可能增加出血风险。", false));
        for (String answer : List.of("鱼油与华法林**不**会增加出血风险 [1]。",
                "鱼油与华法林[不会](https://example.invalid)增加出血风险 [1]。")) {
            assertThat(MedicalEvidenceVerifier.verify(true, answer, relation).unsupported()).as(answer).hasSize(1);
        }
        assertThat(MedicalEvidenceVerifier.verify(true, "维生素D成人每日上限为**2000** IU [1]。", dose).unsupported()).isEmpty();
    }

    @Test
    void 同一切片不同实体与推荐上限的数值不能互相借用() {
        var mixed = List.of(doc("营养素资料", "维生素D成人每日上限为2000IU；铁成人每日上限为42毫克。", false));
        assertThat(MedicalEvidenceVerifier.verify(true, "铁成人每日上限为2000IU [1]。", mixed).unsupported()).hasSize(1);
        var roles = List.of(doc("维生素D说明书", "维生素D成人每日推荐摄入量为400IU，可耐受最高摄入量为2000IU。", false));
        assertThat(MedicalEvidenceVerifier.verify(true, "维生素D成人每日推荐摄入量为2000IU [1]。", roles).unsupported()).hasSize(1);
        assertThat(MedicalEvidenceVerifier.verify(true, "维生素D成人每日推荐摄入量为400IU，上限为2000IU [1]。", roles).unsupported()).isEmpty();
    }

    @Test
    void 基本营养素医疗问法也必须进入严格审核且URL中编号不是可见引用() {
        assertThat(MedicalEvidenceVerifier.isMedicalQuestion("铁的成人每日可耐受最高摄入量是多少？")).isTrue();
        assertThat(MedicalEvidenceVerifier.isMedicalQuestion("钙的成人每日可耐受最高摄入量是多少？")).isTrue();
        var evidence = List.of(doc("钙片说明书", "钙片成人每日1粒。", false));
        assertThat(MedicalEvidenceVerifier.verify(true, "[钙片成人每日1粒](https://example.invalid/[1])。", evidence)
                .unsupported()).hasSize(1);
    }

    @Test
    void 可见实体和同值单位别名保留而代码链接与转义角标不能充当引用() {
        var evidence = List.of(doc("铁剂说明书", "铁剂每日推荐摄入量为42毫克。", false));
        for (String answer : List.of("**铁剂**每日推荐摄入量为42 mg [1]。",
                "[铁剂](https://example.invalid)每日推荐摄入量为42毫克 [1]。",
                "铁剂每日推荐摄入量为42毫克 [1](https://example.invalid)。")) {
            assertThat(MedicalEvidenceVerifier.verify(true, answer, evidence).unsupported()).as(answer).isEmpty();
        }
        for (String answer : List.of("**钙剂**每日推荐摄入量为42毫克 [1]。",
                "铁剂每日推荐摄入量为42毫克 `[1]`。", "铁剂每日推荐摄入量为42毫克 \\[1]。")) {
            assertThat(MedicalEvidenceVerifier.verify(true, answer, evidence).unsupported()).as(answer).hasSize(1);
        }
    }

    @Test
    void 同一句同时声明每粒含量与每日用量仍各自绑定角色() {
        var evidence = List.of(doc("维生素D说明书", "维生素D每粒400IU每日1粒。", false));
        assertThat(MedicalEvidenceVerifier.verify(true, "维生素D每日1粒 [1]。", evidence).unsupported()).isEmpty();
        assertThat(MedicalEvidenceVerifier.verify(true, "维生素D每粒400IU [1]。", evidence).unsupported()).isEmpty();
        assertThat(MedicalEvidenceVerifier.verify(true, "维生素D每日400IU [1]。", evidence).unsupported()).hasSize(1);
    }

    @Test
    void 风险存在与提高风险不能绕过无引用事实计数() {
        for (String answer : List.of("鱼油与华法林合用有出血风险。", "鱼油会提高出血风险。")) {
            assertThat(MedicalEvidenceVerifier.verify(true, answer, List.of()).unsupported()).as(answer).hasSize(1);
            assertThat(MedicalEvidenceVerifier.factSentences(answer)).as(answer).hasSize(1);
        }
    }

    @Test
    void 同片风险方向不能借另一实体关系但标题主体与同服对象可继承() {
        var evidence = List.of(doc("医学资料", "鱼油降低出血风险；阿司匹林增加出血风险。", false));
        assertThat(MedicalEvidenceVerifier.verify(true, "鱼油增加出血风险 [1]。", evidence).unsupported()).hasSize(1);
        assertThat(MedicalEvidenceVerifier.verify(true, "鱼油降低出血风险 [1]。", evidence).unsupported()).isEmpty();
        var manual = List.of(doc("深海鱼油说明书", "本品具有抗血小板作用，与华法林合用可能增加出血风险。", false));
        assertThat(MedicalEvidenceVerifier.verify(true, "深海鱼油与华法林合用可能增加出血风险 [1]。", manual).unsupported()).isEmpty();
    }

    @Test
    void 真实铁摄入资料男性女性孕期不能互借且复合实体不只核最后一项() {
        var evidence = List.of(doc("营养素摄入资料", "铁推荐摄入量成年男性12毫克每日、成年女性18毫克每日，孕期25毫克每日，可耐受最高摄入量42毫克每日。", false));
        for (String answer : List.of("铁成年男性每日推荐摄入量为12毫克 [1]。", "铁成年女性每日推荐摄入量为18毫克 [1]。",
                "铁孕期每日推荐摄入量为25毫克 [1]。", "铁成人每日上限为42毫克 [1]。")) {
            assertThat(MedicalEvidenceVerifier.verify(true, answer, evidence).unsupported()).as(answer).isEmpty();
        }
        for (String answer : List.of("铁成年男性每日推荐摄入量为18毫克 [1]。", "铁孕期每日推荐摄入量为12毫克 [1]。",
                "钙与铁每日上限为42毫克 [1]。")) {
            assertThat(MedicalEvidenceVerifier.verify(true, answer, evidence).unsupported()).as(answer).hasSize(1);
        }
    }

    @Test
    void 未知主体不得成为剂量通配符而仅省略主体的说明书用量可保留() {
        var evidence = List.of(doc("镁说明书", "镁每日330毫克。", false));
        assertThat(MedicalEvidenceVerifier.verify(true, "褪黑素每日330毫克 [1]。", evidence).unsupported()).hasSize(1);
        assertThat(MedicalEvidenceVerifier.verify(true, "褪黑素成人每日1片 [1]。成人每日1片 [1]。",
                List.of(doc("褪黑素说明书", "成人每日1片。", false))).unsupported()).isEmpty();
    }

    @Test
    void 词表外主体摄入服用与并列主体均完整绑定而不能通配已知实体() {
        var wrong = List.of(doc("镁说明书", "镁每日330毫克。", false));
        for (String name : List.of("氨基葡萄糖", "肌酸")) {
            for (String expression : List.of("摄入330毫克", "服用330毫克", "用量为330毫克", "330毫克", "与镁每日330毫克",
                    "，摄入330毫克", "与镁，摄入330毫克")) {
                String answer = name + expression + " [1]。";
                assertThat(MedicalEvidenceVerifier.verify(true, answer, wrong).unsupported()).as(answer).hasSize(1);
            }
            String answer = name + "每日330毫克 [1]。";
            assertThat(MedicalEvidenceVerifier.verify(true, answer, List.of(doc(name + "说明书", name + "每日330毫克。", false)))
                    .unsupported()).as(answer).isEmpty();
            for (String prefix : List.of("", "同时", "建议", "根据资料")) {
                for (String subject : List.of(name, name + "与镁")) {
                    var same = List.of(doc("医学资料", subject + "每日330毫克。", false));
                    for (String layout : List.of("每日330毫克", "，摄入330毫克")) {
                        String mixed = prefix + subject + layout + " [1]。";
                        assertThat(MedicalEvidenceVerifier.verify(true, mixed, wrong).unsupported()).as(mixed).hasSize(1);
                        assertThat(MedicalEvidenceVerifier.verify(true, mixed, same).unsupported()).as(mixed).isEmpty();
                    }
                }
            }
        }
        assertThat(MedicalEvidenceVerifier.verify(true, "肌酸成人每日1片 [1]。成人每日1片 [1]。",
                List.of(doc("肌酸说明书", "成人每日1片。", false))).unsupported()).isEmpty();
        assertThat(MedicalEvidenceVerifier.verify(true, "330毫克 [1]。", wrong).unsupported()).hasSize(1);
    }

    @Test
    void 孤立关系存在否定和省略谓语必须计为事实且不能借问题或来源猜主体() {
        var evidence = List.of(doc("钙与喹诺酮用药说明", "钙与喹诺酮类抗生素合用会影响吸收。", false));
        for (String answer : List.of("会相互影响 [1]。", "有相互作用 [1]。", "会影响 [1]。",
                "不会影响 [1]。", "没有相互作用 [1]。", "不存在相互作用 [1]。")) {
            assertThat(MedicalEvidenceVerifier.factSentences(answer)).as(answer).hasSize(1);
            assertThat(MedicalEvidenceVerifier.verify("钙与喹诺酮类抗生素能同服吗？", answer, evidence)
                    .unsupported()).as(answer).containsExactly(answer);
        }
    }

    @Test
    void 完整关系存在结论须保同一双主体与存在否定而不能空极性放行() {
        var evidence = List.of(doc("同服说明", "钙与喹诺酮类抗生素合用会影响吸收。", false));
        for (String answer : List.of("钙与喹诺酮类抗生素会相互影响 [1]。", "钙与喹诺酮类抗生素有相互作用 [1]。")) {
            assertThat(MedicalEvidenceVerifier.verify(true, answer, evidence).unsupported()).as(answer).isEmpty();
        }
        for (String answer : List.of("钙与喹诺酮类抗生素不存在相互作用 [1]。", "钙与喹诺酮类抗生素不会影响吸收 [1]。",
                "氨基葡萄糖与镁有相互作用 [1]。", "建议肌酸与镁，存在相互作用 [1]。")) {
            assertThat(MedicalEvidenceVerifier.verify(true, answer, evidence).unsupported()).as(answer).hasSize(1);
        }
        assertThat(MedicalEvidenceVerifier.verify(true, "肌酸与镁没有相互作用 [1]。",
                List.of(doc("同服说明", "肌酸与镁没有相互作用。", false))).unsupported()).isEmpty();
    }

    @Test
    void 省略关系主体只继承回答内同段上一条已核验的明确主体() {
        var evidence = List.of(doc("同服说明", "钙与喹诺酮类抗生素合用会影响吸收。", false));
        String accepted = "钙与喹诺酮类抗生素合用会影响吸收 [1]。";
        assertThat(MedicalEvidenceVerifier.verify(true, accepted + "有相互作用 [1]。", evidence).unsupported()).isEmpty();
        for (String prior : List.of("钙与喹诺酮类抗生素合用会影响吸收。", "钙与喹诺酮类抗生素合用会降低出血风险 [1]。",
                accepted + "\n\n", accepted + "请咨询医师。")) {
            assertThat(MedicalEvidenceVerifier.verify(true, prior + "有相互作用 [1]。", evidence).unsupported())
                    .as(prior).contains("有相互作用 [1]。");
        }
        assertThat(MedicalEvidenceVerifier.verify(true, accepted + "不会影响 [1]。", evidence).unsupported()).hasSize(1);
        var negative = List.of(doc("同服说明", "肌酸与镁没有相互作用。", false));
        assertThat(MedicalEvidenceVerifier.verify(true, "肌酸与镁没有相互作用 [1]。没有相互作用 [1]。", negative)
                .unsupported()).isEmpty();
    }

    @Test
    void 长枚举药名中的同一吸收关系须绑定主语和完整对象列表() {
        var evidence = List.of(doc("碳酸钙D3咀嚼片产品说明书",
                "本品所含钙可影响左甲状腺素、四环素类抗生素、喹诺酮类抗生素的吸收，与上述药物合用时应间隔至少2小时。", false));
        for (String answer : List.of("钙与喹诺酮类抗生素合用会影响吸收 [1]。", "钙与四环素类抗生素合用会影响吸收 [1]。")) {
            assertThat(MedicalEvidenceVerifier.verify(true, answer, evidence).unsupported()).as(answer).isEmpty();
        }
        assertThat(MedicalEvidenceVerifier.verify(true, "钙与喹诺酮类抗生素合用不会影响吸收 [1]。", evidence)
                .unsupported()).hasSize(1);
        assertThat(MedicalEvidenceVerifier.verify(true, "钙与华法林合用会影响吸收 [1]。", evidence)
                .unsupported()).hasSize(1);
    }

    @Test
    void 同一句转折两组实体不得互借相反吸收方向() {
        var evidence = List.of(doc("同服说明", "钙与铁合用会降低吸收但镁与锌合用会增加吸收。", false));
        assertThat(MedicalEvidenceVerifier.verify(true, "钙与铁合用会降低吸收 [1]。", evidence).unsupported()).isEmpty();
        assertThat(MedicalEvidenceVerifier.verify(true, "镁与锌合用会增加吸收 [1]。", evidence).unsupported()).isEmpty();
        assertThat(MedicalEvidenceVerifier.verify(true, "钙与铁合用会增加吸收 [1]。", evidence).unsupported()).hasSize(1);
        assertThat(MedicalEvidenceVerifier.verify(true, "镁与锌合用会降低吸收 [1]。", evidence).unsupported()).hasSize(1);
    }

    @Test
    void 单主体无对象关系不能从来源借另一端但单主体明确风险保留() {
        var evidence = List.of(doc("同服说明", "钙与喹诺酮类抗生素合用会影响吸收。", false));
        for (String answer : List.of("钙存在相互作用 [1]。", "钙会影响 [1]。")) {
            assertThat(MedicalEvidenceVerifier.verify(true, answer, evidence).unsupported()).as(answer).hasSize(1);
        }
        assertThat(MedicalEvidenceVerifier.verify(true,
                "钙与喹诺酮类抗生素合用会影响吸收 [1]。会影响 [1]。", evidence).unsupported()).isEmpty();
        assertThat(MedicalEvidenceVerifier.verify(true, "钙增加出血风险 [1]。",
                List.of(doc("用药说明", "钙增加出血风险。", false))).unsupported()).isEmpty();
    }

    @Test
    void 长于一百二十字枚举不得按字数截断且下一关系不能替上一关系背书() {
        String names = "左甲状腺素、四环素类抗生素、喹诺酮类抗生素、诺氟沙星、环丙沙星、氧氟沙星、左氧氟沙星、莫西沙星、吉米沙星、司帕沙星、培氟沙星、依诺沙星、洛美沙星、氟罗沙星、妥舒沙星、加替沙星、格帕沙星、曲伐沙星、阿拉沙星、帕珠沙星、西他沙星、德拉沙星、四环素、土霉素、金霉素、多西环素、米诺环素、替加环素、依拉环素、萨瑞环素、甲氯环素、地美环素";
        assertThat(names.length()).isGreaterThan(120);
        var longSource = List.of(doc("钙说明书", "钙可影响" + names + "的吸收，与上述药物合用时应间隔至少2小时。", false));
        assertThat(MedicalEvidenceVerifier.verify(true, "钙与喹诺酮类抗生素合用会影响吸收 [1]。", longSource)
                .unsupported()).isEmpty();
        var distinct = List.of(doc("同服说明", "钙与铁合用会增加出血风险并镁与锌合用会降低出血风险。", false));
        assertThat(MedicalEvidenceVerifier.verify(true, "钙与铁合用会降低出血风险 [1]。", distinct).unsupported()).hasSize(1);
        assertThat(MedicalEvidenceVerifier.verify(true, "镁与锌合用会增加出血风险 [1]。", distinct).unsupported()).hasSize(1);
    }

    @Test
    void 医学模式中明确商业对象的影响不当作医学事实或剥除() {
        for (String answer : List.of("天气会影响配送 [1]。", "价格会影响选择 [1]。")) {
            assertThat(MedicalEvidenceVerifier.factSentences(answer)).as(answer).isEmpty();
            assertThat(MedicalEvidenceVerifier.verify(true, answer,
                    List.of(doc("平台购物说明", answer.replace(" [1]", ""), false))).unsupported()).as(answer).isEmpty();
        }
    }

    @Test
    void 对象枚举后的共同间隔保同一主体与各对象且独立关系不能借时长() {
        var iron = List.of(doc("药物相互作用指南",
                "铁剂与左旋多巴、甲状腺素合用会降低后者吸收，应间隔至少2小时。", false));
        for (String answer : List.of("铁剂与左旋多巴合用应间隔至少2小时 [1]。", "铁剂与甲状腺素合用应间隔至少2小时 [1]。")) {
            assertThat(MedicalEvidenceVerifier.verify(true, answer, iron).unsupported()).as(answer).isEmpty();
        }
        var calcium = List.of(doc("碳酸钙D3咀嚼片产品说明书",
                "本品所含钙可影响左甲状腺素、四环素类抗生素、喹诺酮类抗生素的吸收，与上述药物合用时应间隔至少2小时。", false));
        assertThat(MedicalEvidenceVerifier.verify(true, "钙与喹诺酮类抗生素合用应间隔至少2小时 [1]。", calcium)
                .unsupported()).isEmpty();
        var independent = List.of(doc("用药说明",
                "铁剂与左旋多巴合用会降低吸收。铁剂与甲状腺素合用应间隔至少2小时。", false));
        assertThat(MedicalEvidenceVerifier.verify(true, "铁剂与左旋多巴合用应间隔至少2小时 [1]。", independent)
                .unsupported()).hasSize(1);
        assertThat(MedicalEvidenceVerifier.verify(true, "铁剂与左旋多巴合用应间隔至少2小时 [1]。",
                List.of(doc("图谱引文", "铁剂与左旋多巴合用会降低后者吸收。", false))).unsupported()).hasSize(1);
    }

    @Test
    void 泛化关系双主体约束按分句不能误伤具体风险() {
        var evidence = List.of(doc("用药说明",
                "钙与喹诺酮类抗生素合用会影响吸收。镁增加出血风险。", false));
        String supported = "钙与喹诺酮类抗生素存在相互作用，镁增加出血风险 [1]。";
        assertThat(MedicalEvidenceVerifier.verify(true, supported, evidence).unsupported()).isEmpty();
    }

    @Test
    void 前句缺少关系另一主体不能借后句具体关系逃逸() {
        var evidence = List.of(doc("用药说明",
                "钙与喹诺酮类抗生素合用会影响吸收。镁增加出血风险。", false));
        String incomplete = "钙会影响，镁增加出血风险 [1]。";
        assertThat(MedicalEvidenceVerifier.verify(true, incomplete, evidence).unsupported()).containsExactly(incomplete);
    }

    @Test
    void 中文粒数不能让剂量断言变空且保原句拒绝键() {
        var evidence = List.of(doc("维生素D3说明书", "维生素D3成人每日1粒。", false));
        for (String quantity : List.of("一百", "十", "两", "**一百**", "`一百`", "[一百](https://example.invalid)")) {
            String answer = "维生素D3成人每日" + quantity + "粒 [1]。";
            assertThat(MedicalEvidenceVerifier.verify(true, answer, evidence).unsupported()).as(answer).containsExactly(answer);
            assertThat(MedicalEvidenceVerifier.factSentences(answer)).as(answer).containsExactly(answer);
        }
        assertThat(MedicalEvidenceVerifier.verify(true, "维生素D3成人每日一粒 [1]。", evidence).unsupported()).isEmpty();
    }

    @Test
    void 中文两二与间隔同值互认但三小时不借两小时来源() {
        var evidence = List.of(doc("同服说明", "铁与左旋多巴间隔2小时。", false));
        for (String number : List.of("两", "二", "**两**")) {
            assertThat(MedicalEvidenceVerifier.verify(true, "铁与左旋多巴间隔" + number + "小时 [1]。", evidence)
                    .unsupported()).as(number).isEmpty();
        }
        assertThat(MedicalEvidenceVerifier.verify(true, "铁与左旋多巴间隔三小时 [1]。", evidence).unsupported()).hasSize(1);
        assertThat(MedicalEvidenceVerifier.verify(true, "铁与左旋多巴间隔2小时 [1]。",
                List.of(doc("同服说明", "铁与左旋多巴间隔两小时。", false))).unsupported()).isEmpty();
    }

    @Test
    void 中文十百与点小数仅同值同单位同角色互认() {
        for (String[] pair : List.of(new String[]{"十二", "12"}, new String[]{"二十", "20"},
                new String[]{"一百零五", "105"}, new String[]{"零点五", "0.5"}, new String[]{"一点二五", "1.25"})) {
            String chinese = "铁每片含" + pair[0] + "毫克 [1]。";
            var evidence = List.of(doc("铁剂说明书", "铁每片含" + pair[1] + "毫克。", false));
            assertThat(MedicalEvidenceVerifier.verify(true, chinese, evidence).unsupported()).as(chinese).isEmpty();
            assertThat(MedicalEvidenceVerifier.verify(true, "铁每片含" + pair[1] + "毫克 [1]。",
                    List.of(doc("铁剂说明书", chinese.replace(" [1]", ""), false))).unsupported()).isEmpty();
        }
        var evidence = List.of(doc("铁剂说明书", "铁每片含1.25毫克。", false));
        assertThat(MedicalEvidenceVerifier.verify(true, "铁每片含一点五毫克 [1]。", evidence).unsupported()).hasSize(1);
        assertThat(MedicalEvidenceVerifier.verify(true, "铁每片含一点二五微克 [1]。", evidence).unsupported()).hasSize(1);
        assertThat(MedicalEvidenceVerifier.verify(true, "铁每日推荐摄入量一点二五毫克 [1]。", evidence).unsupported()).hasSize(1);
    }

    @Test
    void 中文范围保两个端点不推理范围内标量也不借另一实体数值() {
        var evidence = List.of(doc("钙片说明书", "钙成人每日1至2粒。", false));
        for (String range : List.of("一至二", "一到两", "一—二")) {
            assertThat(MedicalEvidenceVerifier.verify(true, "钙成人每日" + range + "粒 [1]。", evidence).unsupported()).as(range).isEmpty();
        }
        for (String range : List.of("二至三", "一", "三")) {
            assertThat(MedicalEvidenceVerifier.verify(true, "钙成人每日" + range + "粒 [1]。", evidence).unsupported()).as(range).hasSize(1);
        }
        assertThat(MedicalEvidenceVerifier.verify(true, "钙成人每日一到二点五克 [1]。",
                List.of(doc("钙片说明书", "钙成人每日1-2.5克。", false))).unsupported()).isEmpty();
        assertThat(MedicalEvidenceVerifier.verify(true, "铁成人每日两粒 [1]。",
                List.of(doc("同服说明", "钙成人每日2粒；铁成人每日1粒。", false))).unsupported()).hasSize(1);
    }

    @Test
    void 中文人口刻度与药材名不混成用量且原文与偏移不变() {
        assertThat(MedicalEvidenceVerifier.normalize("三七片每日一片")).startsWith("三七片每日1片");
        assertThat(MedicalEvidenceVerifier.factSentences("三岁儿童。")).isEmpty();
        var chunk = doc("三七片说明书", "三七片每日一片。", false).toBuilder().charOffset(72).build();
        assertThat(MedicalEvidenceVerifier.verify(true, "三七片每日1片 [1]。", List.of(chunk)).unsupported()).isEmpty();
        assertThat(chunk.getContent()).isEqualTo("三七片每日一片。");
        assertThat(chunk.getCharOffset()).isEqualTo(72);
        var children = List.of(doc("儿童用量说明", "维生素D3三岁以上儿童每日一粒。", false));
        assertThat(MedicalEvidenceVerifier.verify(true, "维生素D3三岁以上儿童每日1粒 [1]。", children).unsupported()).isEmpty();
        assertThat(MedicalEvidenceVerifier.verify(true, "维生素D3四岁以上儿童每日1粒 [1]。", children).unsupported()).hasSize(1);
    }

    @Test
    void 来源限定儿童范围不能支持未限定回答且不从问题猜人口() {
        var evidence = List.of(doc("维生素D3说明书", "维生素D3儿童每日1粒。", false));
        assertThat(MedicalEvidenceVerifier.verify("成人维生素D3每日多少？", "维生素D3每日1粒 [1]。", evidence)
                .unsupported()).hasSize(1);
        assertThat(MedicalEvidenceVerifier.verify(true, "维生素D3儿童每日一粒 [1]。", evidence).unsupported()).isEmpty();
        assertThat(MedicalEvidenceVerifier.verify(true, "维生素D3每日一粒 [1]。",
                List.of(doc("通用用量说明", "维生素D3每日1粒。", false))).unsupported()).isEmpty();
        String scoped = "维生素D3儿童每日1粒 [1]。";
        assertThat(MedicalEvidenceVerifier.verify(true, scoped + "维生素D3每日一粒 [1]。", evidence).unsupported()).isEmpty();
        assertThat(MedicalEvidenceVerifier.verify(true, scoped + "\n\n维生素D3每日一粒 [1]。", evidence).unsupported()).hasSize(1);
    }

    @Test
    void 医学模式的食品售后中文七天不是疗程也不误剥合法业务事实() {
        String answer = "食品与保健食品类商品售后规定支持七天无理由退货 [1]。";
        var evidence = List.of(doc("食品与保健食品类商品售后规定", "食品与保健食品类商品售后规定支持七天无理由退货。", false));
        assertThat(MedicalEvidenceVerifier.factSentences(answer)).isEmpty();
        assertThat(MedicalEvidenceVerifier.verify("维生素D3拆封后还能退吗？", answer, evidence).unsupported()).isEmpty();
    }

    @Test
    void 中文起始时间可用平台真实标题引用但弱片仍不得支撑() {
        String answer = "备孕人群建议从孕前三个月开始补充叶酸（《备孕叶酸指南》）。";
        var strong = doc("备孕叶酸指南", "叶酸备孕人群建议从孕前三个月开始补充。", false)
                .toBuilder().score(0.9).reranked(true).build();
        assertThat(MedicalEvidenceVerifier.verify(true, answer, List.of(strong)).unsupported()).isEmpty();
        var weak = strong.toBuilder().score(0.18).build();
        assertThat(MedicalEvidenceVerifier.verify(true, answer, List.of(weak)).unsupported()).containsExactly(answer);
        assertThat(MedicalEvidenceVerifier.verify(true,
                "备孕人群建议从孕前四个月开始补充叶酸（《备孕叶酸指南》）。", List.of(strong)).unsupported()).hasSize(1);
    }

    @Test
    void 药材名豁免不能遮掉已明确每日每次的含混数词剂量() {
        var evidence = List.of(doc("维生素D3说明书", "维生素D3每日1片。", false));
        for (String context : List.of("每日", "每次")) {
            String answer = "维生素D3" + context + "三七片 [1]。";
            assertThat(MedicalEvidenceVerifier.verify(true, answer, evidence).unsupported()).as(answer).containsExactly(answer);
            assertThat(MedicalEvidenceVerifier.factSentences(answer)).as(answer).hasSize(1);
        }
        assertThat(MedicalEvidenceVerifier.verify(true, "三七片每日一片 [1]。",
                List.of(doc("三七片说明书", "三七片每日1片。", false))).unsupported()).isEmpty();
    }

    @Test
    void 时间量词个不改变小时数值且三小时不能借两小时来源() {
        var evidence = List.of(doc("同服说明", "铁与左旋多巴间隔2小时。", false));
        assertThat(MedicalEvidenceVerifier.verify(true, "铁与左旋多巴间隔两个小时 [1]。", evidence).unsupported()).isEmpty();
        assertThat(MedicalEvidenceVerifier.verify(true, "铁与左旋多巴间隔三个小时 [1]。", evidence).unsupported()).hasSize(1);
    }

    private static DocumentChunk doc(String title, String content, boolean graphBacked) {
        return DocumentChunk.builder()
                .chunkId(title + "-1")
                .docId(title)
                .title(title)
                .position("《" + title + "》 > 第四章")
                .content(content)
                .source("manual")
                .graphBacked(graphBacked)
                .build();
    }
}
