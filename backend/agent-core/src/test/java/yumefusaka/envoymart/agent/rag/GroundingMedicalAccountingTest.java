package yumefusaka.envoymart.agent.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GroundingMedicalAccountingTest {
    private static GroundingEvaluator.CaseOutcome vitaminUpperOutcome(String source, String answer, List<DocumentChunk> extra) {
        var evidence = new java.util.ArrayList<DocumentChunk>();
        evidence.add(doc("KB-0016", "中国居民膳食营养素参考摄入量速查", source));
        evidence.addAll(extra);
        var sample = new GroundingEvaluator.Sample("G-14", GroundingFixtures.Kind.MULTI_HOP, false,
                List.of("2000IU"), evidence, answer, EvidenceGate.Level.SUFFICIENT, false,
                List.of(), true, "成人维生素D的每日可耐受最高摄入量是多少？");
        return GroundingEvaluator.evaluate(List.of(sample)).cases().getFirst();
    }

    @Test
    void G14显式同数国际单位引用覆盖IU锚点() {
        String source = "维生素D推荐摄入量成人10微克每日（折合400国际单位），可耐受最高摄入量50微克每日（折合2000国际单位）。";
        var outcome = vitaminUpperOutcome(source,
                "**成人维生素D的每日可耐受最高摄入量是2000IU**，折合50微克[1]。", List.of());
        assertThat(outcome.factSentences()).isEqualTo(1);
        assertThat(outcome.unsupported()).isZero();
        assertThat(outcome.wrongCitations()).isZero();
        assertThat(outcome.answerComplete()).isTrue();
        assertThat(outcome.multiHopHit()).isTrue();
    }

    @Test
    void 单位别名不能换算异数跨主体跨剂量角色或借未引切片() {
        String answer = "成人维生素D的每日可耐受最高摄入量是2000IU[1]。";
        for (String source : List.of("成人维生素D每日可耐受最高摄入量是4000国际单位。",
                "成人维生素A每日可耐受最高摄入量是2000国际单位。",
                "成人维生素D每日推荐摄入量是2000国际单位。",
                "成人维生素D每日可耐受最高摄入量是50微克。")) {
            var outcome = vitaminUpperOutcome(source, answer, List.of());
            assertThat(outcome.unsupported()).as(source).isEqualTo(1);
            assertThat(outcome.answerComplete()).as(source).isFalse();
        }
        var crossed = vitaminUpperOutcome("成人维生素D每日可耐受最高摄入量是50微克。", answer,
                List.of(doc("other", "另一切片", "成人维生素D每日可耐受最高摄入量是2000国际单位。")));
        assertThat(crossed.unsupported()).isEqualTo(1);
    }

    @Test
    void 七天与七日只在独立完整性认可等价() {
        for (String anchor : List.of("七日", "七天")) {
            String unit = anchor.equals("七日") ? "七天" : "七日";
            var sample = new GroundingEvaluator.Sample("G-02", GroundingFixtures.Kind.MULTI_HOP, false,
                    List.of(anchor), List.of(doc("policy", "售后规则", "商品支持七日无理由退货，也就是七天无理由退货。")),
                    "商品支持" + unit + "无理由退货 [1]。", EvidenceGate.Level.SUFFICIENT, false, List.of(), true, "退货期限？");
            var outcome = GroundingEvaluator.evaluate(List.of(sample)).cases().getFirst();
            assertThat(outcome.unsupported()).isZero();
            assertThat(outcome.answerComplete()).isTrue();
            assertThat(outcome.multiHopHit()).isFalse();
        }
        var wrongDays = new GroundingEvaluator.Sample("G-02", GroundingFixtures.Kind.ANSWERABLE, false,
                List.of("七日"), List.of(doc("policy", "售后规则", "商品支持六天退货。")),
                "商品支持六天退货 [1]。", EvidenceGate.Level.SUFFICIENT, false);
        assertThat(GroundingEvaluator.evaluate(List.of(wrongDays)).cases().getFirst().answerComplete()).isFalse();
    }

    private static GroundingEvaluator.Sample unitAliasSample(GroundingFixtures.Kind kind, boolean literalUnits) {
        var fixture = GroundingFixtures.CASES.stream().filter(item -> item.id().equals("G-05")).findFirst().orElseThrow();
        String answer = "维生素D3成人每日1粒 [1]。维生素D3每粒含400IU [1]。维生素D3成人每日最高摄入量2000IU [1]。";
        return new GroundingEvaluator.Sample(fixture.id(), kind, fixture.expectRefuse(), fixture.mustMention(),
                List.of(doc("vitamin-d3", "维生素D3说明书", "维生素D3成人每日1粒。维生素D3每粒含400IU。维生素D3成人每日最高摄入量2000IU。IU是国际单位。")),
                literalUnits ? answer + "IU是国际单位。" : answer,
                EvidenceGate.Level.SUFFICIENT, false, List.of(), true, fixture.question());
    }

    @Test
    void G05原金标仅用IU仍满足独立完整性() {
        var outcome = GroundingEvaluator.evaluate(List.of(unitAliasSample(GroundingFixtures.Kind.ANSWERABLE, false)))
                .cases().getFirst();
        assertThat(outcome.unsupported()).isZero();
        assertThat(outcome.answerComplete()).isTrue();
        assertThat(outcome.missingRequired()).isEmpty();
    }

    @Test
    void 单位别名只影响独立完整性不放宽原逐字多跳() {
        var outcome = GroundingEvaluator.evaluate(List.of(unitAliasSample(GroundingFixtures.Kind.MULTI_HOP, false)))
                .cases().getFirst();
        assertThat(outcome.unsupported()).isZero();
        assertThat(outcome.answerComplete()).isTrue();
        assertThat(outcome.multiHopHit()).isFalse();
    }

    @Test
    void 同时出现两种单位写法仍满足原逐字多跳() {
        var outcome = GroundingEvaluator.evaluate(List.of(unitAliasSample(GroundingFixtures.Kind.MULTI_HOP, true)))
                .cases().getFirst();
        assertThat(outcome.unsupported()).isZero();
        assertThat(outcome.answerComplete()).isTrue();
        assertThat(outcome.multiHopHit()).isTrue();
    }

    @Test
    void 剔光医学事实与正确商业回答分别结算完整性() {
        var medical = new GroundingEvaluator.Sample("T7-empty", GroundingFixtures.Kind.MULTI_HOP, false,
                List.of(), List.of(doc("calcium", "钙说明书", "钙与四环素类需间隔至少2小时。")),
                "请咨询医师并遵医嘱。", EvidenceGate.Level.SUFFICIENT, true, List.of(), true,
                "钙与抗生素怎么安排服用？");
        var outcome = GroundingEvaluator.evaluate(List.of(medical)).cases().getFirst();
        assertThat(outcome.unsupported()).isZero();
        assertThat(outcome.answerComplete()).isFalse();
        assertThat(outcome.missingRequired()).containsExactly("NO_SUPPORTED_MEDICAL_FACT");
        var commerce = new GroundingEvaluator.Sample("T7-commerce", GroundingFixtures.Kind.MULTI_HOP, false,
                List.of("质量问题"), List.of(doc("policy", "售后规则", "质量问题可退货。")),
                "质量问题可退货 [1]。", EvidenceGate.Level.SUFFICIENT, true, List.of(), true,
                "我买的维生素D3能退货吗？");
        assertThat(GroundingEvaluator.evaluate(List.of(commerce)).cases().getFirst().answerComplete()).isTrue();
    }

    @Test
    void 缺失另一版本不能因零未支撑而算完整() {
        var sample = new GroundingEvaluator.Sample("T7-version", GroundingFixtures.Kind.ANSWERABLE, false,
                List.of("15毫克", "10毫克", "400微克"),
                List.of(doc("iron", "铁叶酸说明书", "每片含铁15毫克与叶酸400微克。")),
                "每片含铁15毫克与叶酸400微克 [1]。", EvidenceGate.Level.SUFFICIENT, false,
                List.of(), false, "铁叶酸每片含多少？");
        var outcome = GroundingEvaluator.evaluate(List.of(sample)).cases().getFirst();
        assertThat(outcome.unsupported()).isZero();
        assertThat(outcome.answerComplete()).isFalse();
        assertThat(outcome.missingRequired()).containsExactly("10毫克");
    }

    @Test
    void G06真实旧锚点不足时独立契约仍要求两版本() {
        var evidence = List.of(doc("v15", "铁叶酸片产品说明书", "每片含铁15毫克与叶酸400微克。"),
                doc("v10", "铁叶酸片产品说明书", "每片含铁10毫克与叶酸400微克。"));
        var sample = new GroundingEvaluator.Sample("G-06", GroundingFixtures.Kind.ANSWERABLE, false,
                List.of("10毫克", "400微克"), evidence, "每片含铁10毫克与叶酸400微克 [2]。",
                EvidenceGate.Level.SUFFICIENT, false, List.of(), false, "铁叶酸片每片含多少铁和叶酸？");
        var outcome = GroundingEvaluator.evaluate(List.of(sample)).cases().getFirst();
        assertThat(outcome.unsupported()).isZero();
        assertThat(outcome.answerComplete()).isFalse();
        assertThat(outcome.missingRequired()).contains("版本成分:15毫克:400微克");
    }

    @Test
    void G13原关系被截断仍欠间隔上下文完整句才可结算通过() {
        String answer = "铁剂与左旋多巴合用会降低左旋多巴的吸收 [1]。";
        var partial = new GroundingEvaluator.Sample("G-13", GroundingFixtures.Kind.MULTI_HOP, false,
                List.of("左旋多巴", "2小时"), List.of(doc("KB-0010", "相互作用指南", "铁剂与左旋多巴合用会降低左旋多巴的吸收。")),
                answer, EvidenceGate.Level.SUFFICIENT, true, List.of(), true, "铁剂与左旋多巴会相互作用吗？");
        var outcome = GroundingEvaluator.evaluate(List.of(partial)).cases().getFirst();
        assertThat(outcome.unsupported()).isZero();
        assertThat(outcome.answerComplete()).isFalse();
        assertThat(outcome.rootCause()).isEqualTo("MISSING_RETRIEVAL_CONTEXT");
        assertThat(outcome.missingRequired()).anyMatch(point -> point.startsWith("MISSING_RETRIEVAL_CONTEXT"));
        var complete = new GroundingEvaluator.Sample("G-13", GroundingFixtures.Kind.MULTI_HOP, false,
                partial.mustMention(), List.of(doc("KB-0010", "相互作用指南", "铁剂与左旋多巴合用会降低左旋多巴的吸收，应间隔至少2小时。")),
                answer + "铁剂与左旋多巴至少间隔2小时 [1]。", EvidenceGate.Level.SUFFICIENT, true, List.of(), true, partial.userMessage());
        assertThat(GroundingEvaluator.evaluate(List.of(complete)).cases().getFirst().answerComplete()).isTrue();
    }

    @Test
    void KG05双收录用当前关系未决结算而非旧未知锚点() {
        var current = yumefusaka.envoymart.agent.llm.ToolExecution.builder().tool("interaction_check").success(true)
                .rawData(java.util.Map.of("available", true, "items", List.of(
                        java.util.Map.of("input", "SPU41", "label", "磷虾油软胶囊", "found", true),
                        java.util.Map.of("input", "阿司匹林", "label", "阿司匹林", "found", true)))).build();
        var fixture = GraphMultiHopFixtures.CASES.stream().filter(item -> item.id().equals("KG-05")).findFirst().orElseThrow();
        String answer = "磷虾油与阿司匹林的具体关系，无法根据本次图谱检查判断，请咨询医师。";
        var sample = new GroundingEvaluator.Sample(fixture.id(), GroundingFixtures.Kind.MULTI_HOP, fixture.expectRefuse(),
                fixture.mustMention(), List.of(), answer, EvidenceGate.Level.NONE, true, List.of(), true,
                fixture.question(), false, List.of(current));
        var outcome = GroundingEvaluator.evaluate(List.of(sample)).cases().getFirst();
        assertThat(outcome.answerComplete()).isTrue();
        assertThat(outcome.missingRequired()).isEmpty();
        assertThat(outcome.multiHopHit()).isFalse();
        var stale = new GroundingEvaluator.Sample(fixture.id(), GroundingFixtures.Kind.MULTI_HOP, fixture.expectRefuse(),
                fixture.mustMention(), List.of(), "磷虾油没有收录，阿司匹林已收录，本次无法判断；没有收录不等于安全。",
                EvidenceGate.Level.NONE, true, List.of(), true, fixture.question(), false, List.of(current));
        assertThat(GroundingEvaluator.evaluate(List.of(stale)).cases().getFirst().answerComplete()).isFalse();
        var failed = yumefusaka.envoymart.agent.llm.ToolExecution.builder().tool("interaction_check").success(false).build();
        var latestFailed = new GroundingEvaluator.Sample(fixture.id(), GroundingFixtures.Kind.MULTI_HOP, fixture.expectRefuse(),
                fixture.mustMention(), List.of(), answer, EvidenceGate.Level.NONE, true, List.of(), true,
                fixture.question(), false, List.of(current, failed));
        assertThat(GroundingEvaluator.evaluate(List.of(latestFailed)).cases().getFirst().answerComplete()).isFalse();
        var unrelatedRisk = new GroundingEvaluator.Sample(fixture.id(), GroundingFixtures.Kind.MULTI_HOP, fixture.expectRefuse(),
                fixture.mustMention(), List.of(doc("aspirin", "阿司匹林风险提示", "阿司匹林可能增加出血风险。")),
                "**磷虾油与阿司匹林**\n\n阿司匹林可能增加出血风险 [1]。", EvidenceGate.Level.SUFFICIENT,
                true, List.of(), true, fixture.question(), false, List.of(current));
        var unrelated = GroundingEvaluator.evaluate(List.of(unrelatedRisk)).cases().getFirst();
        assertThat(unrelated.unsupported()).isZero();
        assertThat(unrelated.answerComplete()).isFalse();
        assertThat(unrelated.missingRequired()).contains("当前产品风险或关系未决");
        var unrelatedBoundary = new GroundingEvaluator.Sample(fixture.id(), GroundingFixtures.Kind.MULTI_HOP, fixture.expectRefuse(),
                fixture.mustMention(), List.of(), "**磷虾油与阿司匹林**\n\n本次无法判断钙与铁的关系。", EvidenceGate.Level.NONE,
                true, List.of(), true, fixture.question(), false, List.of(current));
        var unrelatedOutcome = GroundingEvaluator.evaluate(List.of(unrelatedBoundary)).cases().getFirst();
        assertThat(unrelatedOutcome.unsupported()).isZero();
        assertThat(unrelatedOutcome.answerComplete()).isFalse();
        assertThat(unrelatedOutcome.missingRequired()).contains("当前产品风险或关系未决");
        var sameSentence = new GroundingEvaluator.Sample(fixture.id(), GroundingFixtures.Kind.MULTI_HOP, fixture.expectRefuse(),
                fixture.mustMention(), List.of(), "磷虾油与阿司匹林，本次无法判断钙与铁的关系。", EvidenceGate.Level.NONE,
                true, List.of(), true, fixture.question(), false, List.of(current));
        assertThat(GroundingEvaluator.evaluate(List.of(sameSentence)).cases().getFirst().answerComplete()).isFalse();
        var postObject = new GroundingEvaluator.Sample(fixture.id(), GroundingFixtures.Kind.MULTI_HOP, fixture.expectRefuse(),
                fixture.mustMention(), List.of(), "本次无法判断磷虾油与阿司匹林的具体关系，请咨询医师。", EvidenceGate.Level.NONE,
                true, List.of(), true, fixture.question(), false, List.of(current));
        assertThat(GroundingEvaluator.evaluate(List.of(postObject)).cases().getFirst().answerComplete()).isTrue();
    }
    @Test
    void 医学事实分母不能依赖多跳验收锚点且悬空指代必须计入未支撑() {
        var evidence = List.of(
                doc("vitamin-manual", "维生素D3说明书", "成人推荐每日1粒，随餐服用。健康成年人每日维生素D推荐摄入量为400IU，可耐受最高摄入量为2000IU。"),
                doc("vitamin-guideline", "摄入上限指南", "维生素D成人每日可耐受最高摄入量为2000IU，长期超过该剂量应在医生监测下进行。"));
        String answer = "长期超过这个剂量，建议在医生监测下进行 [2]。\n\n健康成年人每日维生素D推荐摄入量是400IU，成人推荐每日1粒、随餐服用 [1]。";
        var sample = new GroundingEvaluator.Sample("G-14-fragment", GroundingFixtures.Kind.MULTI_HOP, false,
                List.of("2000IU"), evidence, answer, EvidenceGate.Level.SUFFICIENT, false, List.of(), false,
                "维生素D每日可耐受最高摄入量是多少？吃维生素D3软胶囊会超量吗？");

        var outcome = GroundingEvaluator.evaluate(List.of(sample)).cases().getFirst();

        assertThat(outcome.factSentences()).isEqualTo(2);
        assertThat(outcome.checks()).isEqualTo(2);
        assertThat(outcome.unsupported()).isEqualTo(1);
        assertThat(outcome.multiHopHit()).isFalse();
    }

    @Test
    void 医学用户问题中的订单金额和合同时间不增加医学事实分母() {
        var sample = new GroundingEvaluator.Sample("medical-commerce", GroundingFixtures.Kind.ANSWERABLE, false,
                List.of("未出现锚点"), List.of(doc("commerce", "订单规则", "订单总价为2000元，订单7天内可以退款。")),
                "订单总价为2000元 [1]。订单7天内可以退款 [1]。", EvidenceGate.Level.SUFFICIENT, true,
                List.of(), false, "我买的维生素D3订单金额和退款规则是什么？");

        assertThat(GroundingEvaluator.evaluate(List.of(sample)).cases().getFirst().factSentences()).isZero();
    }

    @Test
    void 已支撑的表格与标题剂量不因排版或语义豁免从分母消失() {
        var evidence = List.of(doc("calcium", "钙片说明书", "钙片成人每日1粒。"));
        for (String answer : List.of("## 钙片成人每日1粒 [1]", "| 钙片 | 成人每日1粒 [1] |")) {
            var sample = new GroundingEvaluator.Sample("medical-format", GroundingFixtures.Kind.ANSWERABLE, false,
                    List.of("未出现锚点"), evidence, answer, EvidenceGate.Level.SUFFICIENT, false,
                    List.of(answer), false, "钙片每日服用多少？");
            var outcome = GroundingEvaluator.evaluate(List.of(sample)).cases().getFirst();
            assertThat(outcome.factSentences()).isEqualTo(1);
            assertThat(outcome.checks()).isEqualTo(1);
            assertThat(outcome.unsupported()).isZero();
        }
    }

    @Test
    void 真实KG02短关系残句无验收锚点仍进入事实和未支撑分母() {
        String answer = "会相互影响 [1]。";
        var sample = new GroundingEvaluator.Sample("KG-02-fragment", GroundingFixtures.Kind.MULTI_HOP, false,
                List.of("钙", "喹诺酮类抗生素", "2 小时"),
                List.of(doc("calcium", "钙说明书", "钙与喹诺酮类抗生素合用会影响吸收，应间隔至少2小时。")),
                answer, EvidenceGate.Level.SUFFICIENT, true, List.of(), true,
                "碳酸钙D3咀嚼片里的钙与喹诺酮抗生素会相互影响吗？");
        var outcome = GroundingEvaluator.evaluate(List.of(sample)).cases().getFirst();
        assertThat(outcome.factSentences()).isEqualTo(1);
        assertThat(outcome.unsupported()).isEqualTo(1);
        assertThat(outcome.multiHopHit()).isFalse();
    }

    @Test
    void 医学用户问题中的天气配送和价格选择不进入医学分母() {
        var sample = new GroundingEvaluator.Sample("medical-commerce-effect", GroundingFixtures.Kind.ANSWERABLE, false,
                List.of("未出现锚点"), List.of(doc("commerce", "购物说明", "天气会影响配送。价格会影响选择。")),
                "天气会影响配送 [1]。价格会影响选择 [1]。", EvidenceGate.Level.SUFFICIENT, true,
                List.of(), false, "我买的维生素D3价格和配送受什么影响？");
        assertThat(GroundingEvaluator.evaluate(List.of(sample)).cases().getFirst().factSentences()).isZero();
    }

    @Test
    void 中文数量与缺失来源人口范围必须进入医学事实和未支撑分母() {
        for (String answer : List.of("维生素D3成人每日一百粒 [1]。", "维生素D3每日1粒 [1]。")) {
            var evidence = List.of(doc("vitamin", "维生素D3说明书", "维生素D3儿童每日1粒。"));
            var sample = new GroundingEvaluator.Sample("medical-chinese-scope", GroundingFixtures.Kind.ANSWERABLE, false,
                    List.of("未出现锚点"), evidence, answer, EvidenceGate.Level.SUFFICIENT, false, List.of(), false,
                    "成人维生素D3每日多少？");
            var outcome = GroundingEvaluator.evaluate(List.of(sample)).cases().getFirst();
            assertThat(outcome.factSentences()).as(answer).isEqualTo(1);
            assertThat(outcome.unsupported()).as(answer).isEqualTo(1);
        }
    }

    private static DocumentChunk doc(String id, String title, String content) {
        return DocumentChunk.builder().docId(id).chunkId(id + "_0").title(title).content(content)
                .source("manual").reranked(true).score(0.9).build();
    }

    @Test
    void 被强调拆分的剂量必须进入事实分母并拒绝错数值() {
        String answer = "维生素D成人每日上限为**9000** IU [1]。";
        var sample = new GroundingEvaluator.Sample("medical-markup", GroundingFixtures.Kind.ANSWERABLE, false,
                List.of("2000IU"), List.of(doc("vitamin", "维生素D说明书", "维生素D成人每日上限为2000IU。")),
                answer, EvidenceGate.Level.SUFFICIENT, false, List.of(), false, "维生素D每日摄入上限是多少？");
        var outcome = GroundingEvaluator.evaluate(List.of(sample)).cases().getFirst();
        assertThat(outcome.factSentences()).isEqualTo(1);
        assertThat(outcome.unsupported()).isEqualTo(1);
    }
}
