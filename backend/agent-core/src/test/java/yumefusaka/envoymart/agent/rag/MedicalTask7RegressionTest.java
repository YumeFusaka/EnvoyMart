package yumefusaka.envoymart.agent.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MedicalTask7RegressionTest {
    private static final List<String> TERMS = List.of("钙", "四环素类", "铁", "叶酸", "益生菌", "抗生素", "左旋多巴");

    @Test
    void 错开与间隔使用同一时间角色() {
        assertThat(MedicalDoseAssertions.supports("钙与四环素类抗生素至少间隔2小时。",
                "钙与四环素类抗生素需错开两小时以上服用。", TERMS)).isTrue();
        assertThat(MedicalDoseAssertions.supports("钙与四环素类抗生素至少间隔3小时。",
                "钙与四环素类抗生素需错开两小时以上服用。", TERMS)).isFalse();
    }

    @Test
    void 时间下界不能省略或变成精确值() {
        String source = "钙与四环素类抗生素需错开两小时以上服用。";
        assertThat(MedicalDoseAssertions.supports("钙与四环素类抗生素间隔2小时。", source, TERMS)).isFalse();
        assertThat(MedicalDoseAssertions.supports("钙与四环素类抗生素恰好间隔2小时即可。", source, TERMS)).isFalse();
        assertThat(MedicalDoseAssertions.supports("钙与四环素类抗生素至少间隔2小时。", source, TERMS)).isTrue();
    }

    @Test
    void 时间不能借另一实体的条件() {
        assertThat(MedicalDoseAssertions.supports("铁与左旋多巴至少间隔2小时。",
                "铁与钙至少间隔2小时。", TERMS)).isFalse();
        assertThat(MedicalDoseAssertions.supports("陌生药物每日服用2片。",
                "左旋多巴每日服用2片。", TERMS)).isFalse();
    }

    @Test
    void 时间关系不能省略另一参与方() {
        assertThat(MedicalDoseAssertions.supports("钙至少间隔2小时。",
                "钙与四环素类抗生素需错开2小时以上服用。", TERMS)).isFalse();
    }

    @Test
    void 后置临床否定不能漏审或借正向下降证据() {
        for (String claim : List.of("铁与左旋多巴同服时，左旋多巴的吸收不会下降。", "左旋多巴的疗效不会下降。")) {
            assertThat(MedicalEvidenceVerifier.factSentences(claim)).containsExactly(claim);
            assertThat(MedicalEvidenceVerifier.verify(true, claim, List.of()).unsupported()).containsExactly(claim);
            String cited = claim.replace("。", " [1]。");
            assertThat(MedicalEvidenceVerifier.verify(true, cited, List.of(doc("铁与左旋多巴同服会降低左旋多巴的吸收。"))).unsupported()).hasSize(1);
        }
    }

    @Test
    void 服用后再食用的相对时间与实体绑定() {
        String source = "建议在服用抗生素2小时后再食用益生菌。";
        assertThat(MedicalDoseAssertions.supports("在服用抗生素2小时后再食用益生菌。", source, TERMS)).isTrue();
        assertThat(MedicalDoseAssertions.supports("在服用益生菌2小时后再食用抗生素。", source, TERMS)).isFalse();
        assertThat(MedicalDoseAssertions.supports("在服用抗生素3小时后再食用益生菌。", source, TERMS)).isFalse();
    }

    @Test
    void 版本话语不改变成分主体() {
        assertThat(MedicalDoseAssertions.supports("一版是每片含铁15毫克与叶酸400微克。",
                "每片含铁15mg与叶酸400µg。", TERMS)).isTrue();
        assertThat(MedicalDoseAssertions.supports("另一版是每片含铁10毫克、叶酸400微克。",
                "每片含铁10mg、叶酸400µg。", TERMS)).isTrue();
        assertThat(MedicalDoseAssertions.supports("另一版是每片含铁15毫克、叶酸400微克。",
                "每片含铁10mg、叶酸400µg。", TERMS)).isFalse();
    }

    @Test
    void 后置临床对象与疗效总结必须统计并审核() {
        String summary = "也就是说，同时服用可能让左旋多巴的吸收变差、药效打折扣。";
        assertThat(MedicalEvidenceVerifier.factSentences(summary)).containsExactly(summary);
        assertThat(MedicalEvidenceVerifier.verify(true, summary, List.of()).unsupported()).containsExactly(summary);
        String claim = "铁与左旋多巴同服可能使左旋多巴吸收变差、药效打折扣 [1]。";
        assertThat(MedicalEvidenceVerifier.verify(true, claim,
                List.of(doc("铁与左旋多巴同服会降低左旋多巴的吸收和生物利用度。"))).unsupported()).containsExactly(claim);
    }

    @Test
    void 后置吸收方向允许同对象转述并拒绝反转() {
        var evidence = List.of(doc("铁与左旋多巴同服会降低左旋多巴的吸收和生物利用度。"));
        assertThat(MedicalEvidenceVerifier.verify(true,
                "铁与左旋多巴同服可能使左旋多巴吸收变差 [1]。", evidence).unsupported()).isEmpty();
        assertThat(MedicalEvidenceVerifier.verify(true,
                "铁与左旋多巴同服可能使左旋多巴吸收提高 [1]。", evidence).unsupported()).hasSize(1);
    }

    @Test
    void G06两个强版本均保留铁与共同叶酸量() {
        String reply = "一版是每片含铁15毫克与叶酸400微克 [1]。另一版是每片含铁10毫克与叶酸400微克 [2]。";
        assertThat(MedicalEvidenceVerifier.verify(true, reply, List.of(
                doc("每片含铁15mg与叶酸400µg。"), doc("每片含铁10mg与叶酸400µg。"))).unsupported()).isEmpty();
    }

    @Test
    void G13后置生物利用度下降只支持吸收对象() {
        assertThat(MedicalEvidenceVerifier.verify(true, "铁剂与左旋多巴合用时，左旋多巴的生物利用度可明显下降 [1]。",
                List.of(doc("铁剂与左旋多巴合用会降低左旋多巴的吸收和生物利用度。"))).unsupported()).isEmpty();
    }

    @Test
    void G17强同片间隔下界保留且角色不借剂量() {
        String source = "钙剂与四环素类抗生素合用会影响吸收，两者服用时间应间隔2小时以上。";
        assertThat(MedicalEvidenceVerifier.verify(true, "钙与四环素类抗生素至少间隔2小时 [1]。", List.of(doc(source))).unsupported()).isEmpty();
    }

    @Test
    void KG09儿童时间范围保留且不支持成人() {
        String source = "钙与四环素类抗生素合用会影响吸收，儿童服用上述药物期间应间隔至少2小时。";
        assertThat(MedicalEvidenceVerifier.verify(true, "儿童服用钙与四环素类抗生素应间隔至少2小时 [1]。", List.of(doc(source))).unsupported()).isEmpty();
        assertThat(MedicalEvidenceVerifier.verify(true, "成人服用钙与四环素类抗生素应间隔至少2小时 [1]。", List.of(doc(source))).unsupported()).hasSize(1);
    }

    @Test
    void KG10标题绑定本品且保留抗生素先用约束() {
        var evidence = List.of(DocumentChunk.builder().title("益生菌冻干粉使用与贮存指南").chunkId("KB-0008_3")
                .content("建议在服用抗生素2小时后再食用本品。").score(0.9).build());
        assertThat(MedicalEvidenceVerifier.verify(true, "在服用抗生素至少2小时后再食用益生菌 [1]。", evidence).unsupported()).isEmpty();
        assertThat(MedicalEvidenceVerifier.verify(true, "在服用益生菌至少2小时后再食用抗生素 [1]。", evidence).unsupported()).hasSize(1);
    }

    @Test
    void 一个强片独立支持的完整句只修复额外弱引用() {
        String claim = "DHA与氯吡格雷合用可能增加出血风险 [1][2]。";
        var evidence = List.of(doc("DHA与氯吡格雷合用可能增加出血风险。"),
                DocumentChunk.builder().title("风险指南").content("DHA与阿司匹林合用可能增加出血风险。")
                        .chunkId("weak").score(0.19).build());
        var repairs = MedicalEvidenceVerifier.supportedCitationRepairs(claim, evidence, EvidenceGate.Thresholds.defaults(), java.util.Set.of());
        assertThat(repairs).containsEntry(claim, List.of(1));
        String finalReply = CitationVerifier.repairCitations(claim, repairs, evidence);
        assertThat(finalReply).contains("DHA与氯吡格雷合用可能增加出血风险").contains("[1]").doesNotContain("[2]");
        assertThat(MedicalEvidenceVerifier.verify(true, finalReply, evidence).unsupported()).isEmpty();
        assertThat(MedicalEvidenceVerifier.supportedCitationRepairs("DHA与氯吡格雷合用可能增加出血风险 [2]。",
                evidence, EvidenceGate.Thresholds.defaults(), java.util.Set.of())).isEmpty();
    }

    @Test
    void KG08原文并列药类的共享后缀仍支持明确抗凝风险() {
        var evidence = List.of(DocumentChunk.builder().title("孕期DHA藻油软胶囊产品说明书").chunkId("KB-0035_4")
                .content("本品所含DHA具有轻微抗血小板聚集作用，与华法林、阿司匹林等抗凝或抗血小板药物合用时可能增加出血风险，合用前须咨询医师。")
                .score(0.9).build());
        assertThat(MedicalEvidenceVerifier.verify(true, "孕期DHA与抗凝药物合用可能增加出血风险 [1]。", evidence).unsupported()).isEmpty();
        assertThat(MedicalEvidenceVerifier.verify(true, "孕期DHA与抗凝药合用可能增加出血风险 [1]。", evidence).unsupported()).isEmpty();
        assertThat(MedicalEvidenceVerifier.verify(true, "孕期DHA与陌生药物合用可能增加出血风险 [1]。", evidence).unsupported()).hasSize(1);
    }

    @Test
    void G06真实规格来源的有限版本框架不成为药物主体() {
        var evidence = List.of(source("铁叶酸片产品说明书",
                "1.1 本品为膳食营养补充剂，主要成分为富马酸亚铁与叶酸。\n"
                        + "1.2 规格为每片含铁10毫克、叶酸400微克，每瓶60片。"));
        for (String prefix : List.of("另一种规格是", "一种规格为", "另一份说明是", "另一版是")) {
            assertThat(MedicalEvidenceVerifier.verify(true,
                    prefix + "每片含铁10毫克、叶酸400微克 [1]。", evidence).unsupported()).as(prefix).isEmpty();
        }
        assertThat(MedicalEvidenceVerifier.verify(true,
                "另一种药物每片含铁10毫克、叶酸400微克 [1]。", evidence).unsupported()).hasSize(1);
        assertThat(MedicalEvidenceVerifier.verify(true,
                "另一种规格是每片含铁15毫克、叶酸400微克 [1]。", evidence).unsupported()).hasSize(1);
    }

    @Test
    void G14同句人口前置框架保留且儿童不借成人上限() {
        var evidence = List.of(source("膳食补充剂摄入上限与常见药物相互作用",
                "1.1 维生素D的成人每日可耐受最高摄入量为2000IU，长期超过该剂量应在医生监测下进行。"));
        assertThat(MedicalEvidenceVerifier.verify(true,
                "对于健康成年人，维生素D每日可耐受最高摄入量为2000IU [1]。", evidence).unsupported()).isEmpty();
        var bothRoles = List.of(source("维生素D3软胶囊产品说明书",
                "3.2 健康成年人每日维生素D推荐摄入量为400IU，可耐受最高摄入量为2000IU。"));
        assertThat(MedicalEvidenceVerifier.verify(true,
                "对于健康成年人，维生素D每日推荐摄入量为400IU，每日上限为2000IU [1]。", bothRoles).unsupported()).isEmpty();
        for (String reply : List.of("对于儿童，维生素D每日上限为2000IU [1]。",
                "对于健康成年人。维生素D每日上限为2000IU [1]。",
                "对于健康成年人，铁每日上限为2000IU [1]。")) {
            assertThat(MedicalEvidenceVerifier.verify(true, reply, evidence).unsupported()).as(reply).hasSize(1);
        }
    }

    @Test
    void G13相隔与间隔共享角色仍保下界数值及双方() {
        var evidence = List.of(source("膳食补充剂摄入上限与常见药物相互作用",
                "2.4 铁剂与左旋多巴、甲状腺素合用会降低后者吸收，应间隔至少2小时。"));
        assertThat(MedicalEvidenceVerifier.verify(true,
                "铁剂与左旋多巴的服用时间至少相隔2小时 [1]。", evidence).unsupported()).isEmpty();
        for (String reply : List.of("铁剂与左旋多巴的服用时间相隔2小时 [1]。",
                "铁剂与左旋多巴的服用时间至少相隔3小时 [1]。",
                "铁剂与陌生药物的服用时间至少相隔2小时 [1]。")) {
            assertThat(MedicalEvidenceVerifier.verify(true, reply, evidence).unsupported()).as(reply).hasSize(1);
        }
    }

    @Test
    void KG07抗凝对象的同向转述与否定反转分别核验() {
        var evidence = List.of(source("辅酶Q10软胶囊产品说明书",
                "4.1 辅酶Q10的化学结构与维生素K相似，可能减弱华法林等抗凝药物的抗凝作用，"
                        + "正在服用华法林者使用前必须咨询医师，并在合用期间监测凝血指标。"));
        for (String reply : List.of("辅酶Q10可能降低华法林的抗凝作用 [1]。",
                "辅酶Q10可能影响华法林的抗凝效果 [1]。",
                "辅酶Q10与华法林合用时，抗凝作用可能下降 [1]。")) {
            assertThat(MedicalEvidenceVerifier.factSentences(reply)).as(reply).containsExactly(reply);
            assertThat(MedicalEvidenceVerifier.verify(true, reply, evidence).unsupported()).as(reply).isEmpty();
        }
        String restored = "辅酶Q10可能降低华法林的抗凝作用 [1]。"
                + "正在服用华法林者使用前必须咨询医师，并在合用期间监测凝血指标 [1]。";
        assertThat(MedicalEvidenceVerifier.verify(true, restored, evidence).unsupported()).isEmpty();
        for (String reply : List.of("辅酶Q10可能增强华法林的抗凝作用 [1]。",
                "辅酶Q10不会降低华法林的抗凝作用 [1]。",
                "辅酶Q10可能降低华法林的吸收 [1]。",
                "维生素K可能降低华法林的抗凝作用 [1]。",
                "氨基葡萄糖可能降低华法林的抗凝作用 [1]。")) {
            assertThat(MedicalEvidenceVerifier.verify(true, reply, evidence).unsupported()).as(reply).hasSize(1);
        }
    }

    @Test
    void KG10真实指南先后约束不得反转或借其他本品() {
        var evidence = List.of(source("益生菌冻干粉使用与贮存指南",
                "3.1 与抗生素同服会显著降低活菌存活率，建议在服用抗生素2小时后再食用本品。"));
        assertThat(MedicalEvidenceVerifier.verify(true,
                "服用抗生素至少2小时后再食用益生菌 [1]。", evidence).unsupported()).isEmpty();
        for (String reply : List.of("服用益生菌至少2小时后再食用抗生素 [1]。",
                "服用抗生素至少2小时后再食用铁剂 [1]。")) {
            assertThat(MedicalEvidenceVerifier.verify(true, reply, evidence).unsupported()).as(reply).hasSize(1);
        }
    }

    @Test
    void KG05实际处方分类不能作为遵医嘱承接漏审() {
        String claim = "阿司匹林通常是处方药或已确诊疾病下的长期用药，能不能和磷虾油软胶囊一起用，需要医生判断。";
        var evidence = List.of(source("磷虾油软胶囊产品说明书",
                "3.2 正在服用抗凝或抗血小板药物者，使用前请咨询医师。"));
        assertThat(MedicalEvidenceVerifier.factSentences(claim)).containsExactly(claim);
        assertThat(MedicalEvidenceVerifier.verify(true, claim, evidence).unsupported()).containsExactly(claim);
        assertThat(MedicalEvidenceVerifier.verify(true, claim.replace("。", " [1]。"), evidence).unsupported()).hasSize(1);
        assertThat(MedicalEvidenceVerifier.verify(true, "处方药需由医生判断，请遵医嘱。", evidence).unsupported()).isEmpty();
        assertThat(MedicalEvidenceVerifier.factSentences("处方药需由医生判断，请遵医嘱。")).isEmpty();
    }

    @Test
    void 实际无来源分类措辞不能通过纯提醒或未知主体逃逸() {
        for (String claim : List.of("左旋多巴是处方药，请遵医嘱。", "华法林是处方抗凝药，请咨询医师。",
                "抗生素属于处方药，具体服药时间请遵医嘱安排。")) {
            assertThat(MedicalEvidenceVerifier.factSentences(claim)).as(claim).containsExactly(claim);
            assertThat(MedicalEvidenceVerifier.verify(true, claim, List.of()).unsupported()).as(claim).containsExactly(claim);
        }
    }

    @Test
    void 模型将实际处方分类标承接时确定性事实识别仍拒绝() {
        String claim = "阿司匹林通常是处方药或已确诊疾病下的长期用药，需要医生判断。";
        yumefusaka.envoymart.agent.llm.LLMProvider llm = (messages, config) ->
                yumefusaka.envoymart.agent.llm.LLMResponse.builder()
                        .content("[{\"index\":1,\"decision\":\"CONVERSATIONAL\",\"refs\":[]}]").build();
        var verdict = SemanticGroundingVerifier.verify(llm, null, "磷虾油和阿司匹林能合用吗？", claim,
                List.of(claim), List.of(), "无");
        assertThat(verdict.evaluated()).isTrue();
        assertThat(verdict.unsupported()).containsExactly(claim);
    }

    @Test
    void 处方分类句法的否定不能借同一冻结断言转正() {
        // 冻结回答的断言仅用作句法夹具；它不代表知识库实际证明了该分类。
        var syntax = List.of(source("分类句法核对夹具", "华法林是处方抗凝药。"));
        assertThat(MedicalEvidenceVerifier.verify(true, "华法林是处方抗凝药 [1]。", syntax).unsupported()).isEmpty();
        for (String claim : List.of("华法林不为处方药 [1]。", "华法林不算是处方药 [1]。")) {
            assertThat(MedicalEvidenceVerifier.verify(true, claim, syntax).unsupported()).as(claim).containsExactly(claim);
        }
    }

    @Test
    void G14与KG03不把本次未查得升为全知识库不存在() {
        for (String claim : List.of("你手上这瓶每粒的具体含量，知识库里没有收录，所以无法判断是否超量。",
                "知识库里没有专门针对铁叶酸片这一商品的相互作用条目。")) {
            assertThat(MedicalEvidenceVerifier.factSentences(claim)).as(claim).containsExactly(claim);
            assertThat(MedicalEvidenceVerifier.verify(true, claim, List.of()).unsupported()).as(claim).containsExactly(claim);
        }
        String limited = "本次检索未查到你手上这瓶每粒的具体含量，所以无法判断是否超量。";
        assertThat(MedicalEvidenceVerifier.verify(true, limited, List.of()).unsupported()).isEmpty();
        String scoped = "仅依据本次图谱检查，无法判断磷虾油与阿司匹林的具体相互作用。";
        String absence = "图谱没有收录磷虾油。";
        assertThat(MedicalEvidenceVerifier.verify(true, scoped + absence, List.of()).unsupported()).isEmpty();
        String unsafe = "图谱没有收录磷虾油，所以可以安全同服。";
        assertThat(MedicalEvidenceVerifier.verify(true, scoped + unsafe, List.of()).unsupported()).containsExactly(unsafe);
        assertThat(MedicalEvidenceVerifier.factSentences(scoped + unsafe)).containsExactly(unsafe);
        String unrelated = "图谱没有收录华法林。";
        assertThat(MedicalEvidenceVerifier.verify(true, scoped + unrelated, List.of()).unsupported()).containsExactly(unrelated);
        assertThat(MedicalEvidenceVerifier.factSentences(scoped + unrelated)).containsExactly(unrelated);
    }

    @Test
    void KG06监测事实必须绑定当前标题的药物宾语() {
        var evidence = List.of(source("褪黑素与γ氨基丁酸类助眠产品说明书",
                "4.2 褪黑素与华法林等抗凝药物合用可能影响凝血功能，合用期间应监测凝血指标并咨询医师。"));
        String directive = "合用期间应监测凝血指标 [1]。";
        String right = "**褪黑素与华法林**\n" + directive;
        String wrong = "**褪黑素与降压药**\n" + directive;
        assertThat(MedicalEvidenceVerifier.factSentences(directive)).containsExactly(directive);
        assertThat(MedicalEvidenceVerifier.verify(true, right, evidence).unsupported()).isEmpty();
        assertThat(MedicalEvidenceVerifier.verify(true, wrong, evidence).unsupported()).containsExactly(directive);
        String broadened = "华法林应监测凝血指标 [1]。";
        assertThat(MedicalEvidenceVerifier.verify(true, broadened, evidence).unsupported()).containsExactly(broadened);
    }

    @Test
    void KG06监测要求不能支持无需监测或监测没有必要() {
        var evidence = List.of(source("褪黑素与γ氨基丁酸类助眠产品说明书",
                "4.2 褪黑素与华法林等抗凝药物合用可能影响凝血功能，合用期间应监测凝血指标并咨询医师。"));
        for (String claim : List.of("褪黑素与华法林合用期间不需要监测凝血指标 [1]。",
                "褪黑素与华法林合用期间监测凝血指标没有必要 [1]。")) {
            assertThat(MedicalEvidenceVerifier.verify(true, claim, evidence).unsupported()).as(claim).containsExactly(claim);
        }
    }

    @Test
    void KG11本品不能将胶原来源借给未声明的乳清商品() {
        var evidence = List.of(source("胶原蛋白肽粉产品说明书",
                "4.1 本品为蛋白质类补充剂，与左旋多巴合用可能影响左旋多巴的吸收，"
                        + "服用左旋多巴者应避免与本品同时服用，并遵医嘱安排服药与进餐时间。"));
        String claim = "本品为蛋白质类补充剂，与左旋多巴合用可能影响左旋多巴的吸收 [1]。";
        assertThat(MedicalEvidenceVerifier.verify(true, claim, evidence).unsupported()).containsExactly(claim);
        String attributed = "胶原蛋白肽粉与左旋多巴合用可能影响左旋多巴的吸收 [1]。";
        assertThat(MedicalEvidenceVerifier.verify(true, attributed, evidence).unsupported()).isEmpty();
        String migrated = "乳清蛋白粉与左旋多巴合用可能影响左旋多巴的吸收 [1]。";
        assertThat(MedicalEvidenceVerifier.verify(true, migrated, evidence).unsupported()).containsExactly(migrated);
        String introduction = "以下资料来自胶原蛋白肽粉产品说明书，所述商品是胶原蛋白肽粉。\n\n";
        assertThat(MedicalEvidenceVerifier.verify(true, introduction + claim, evidence).unsupported()).isEmpty();
        assertThat(MedicalEvidenceVerifier.verify(true, introduction + "现在讨论乳清蛋白粉。\n" + claim, evidence)
                .unsupported()).containsExactly(claim);
    }

    @Test
    void 医学拒因区分人口剂量角色与关系方向() {
        String population = "对于儿童，维生素D每日上限2000IU [1]。";
        var upper = List.of(source("膳食补充剂摄入上限与常见药物相互作用",
                "维生素D成人每日可耐受最高摄入量为2000IU。"));
        assertThat(MedicalEvidenceVerifier.verify(true, population, upper).reasons().get(population)).contains("人口范围");
        String role = "维生素D成人每日推荐摄入量为2000IU [1]。";
        assertThat(MedicalEvidenceVerifier.verify(true, role, upper).reasons().get(role)).contains("剂量角色");
        String direction = "辅酶Q10可能增强华法林的抗凝作用 [1]。";
        assertThat(MedicalEvidenceVerifier.verify(true, direction, List.of(source("辅酶Q10软胶囊产品说明书",
                "辅酶Q10可能减弱华法林的抗凝作用。"))).reasons().get(direction)).contains("关系对象或极性");
    }

    @Test
    void G13真实v7两者间隔必须保完整关系参与组() {
        String answer = g13V7Answer();
        var evidence = g13V7Evidence();
        assertThat(MedicalEvidenceVerifier.verify(true, answer, evidence).unsupported()).isEmpty();
        var fixture = GroundingFixtures.CASES.stream().filter(item -> item.id().equals("G-13")).findFirst().orElseThrow();
        var sample = new GroundingEvaluator.Sample(fixture.id(), fixture.kind(), fixture.expectRefuse(), fixture.mustMention(),
                evidence, answer, EvidenceGate.Level.SUFFICIENT, true, List.of(), true, fixture.question());
        var outcome = GroundingEvaluator.evaluate(List.of(sample)).cases().getFirst();
        assertThat(outcome.multiHopHit()).isTrue();
        assertThat(outcome.unsupported()).isZero();
        assertThat(outcome.answerComplete()).isTrue();
        assertThat(outcome.missingRequired()).isEmpty();
        for (String pronoun : List.of("两者", "二者")) {
            assertThat(MedicalDoseAssertions.supports("铁剂与左旋多巴至少间隔2小时",
                    answer.replace("两者应间隔", pronoun + "应间隔"), TERMS)).as(pronoun).isTrue();
        }
    }

    @Test
    void G13两者时间不能越过转题空段新主体或借错值与先后() {
        String relation = "铁剂与左旋多巴合用，会降低左旋多巴的吸收，左旋多巴的生物利用度可能明显下降。";
        String interval = "两者应间隔至少2小时服用。";
        for (String source : List.of(relation + "现在讨论钙与铁。" + interval,
                relation + "\n\n" + interval, relation + "钙会影响吸收。" + interval,
                relation + "陌生药物。" + interval,
                relation + "铁剂与陌生药物合用会降低吸收。" + interval,
                relation + interval.replace("2小时", "3小时"))) {
            assertThat(MedicalDoseAssertions.supports("铁剂与左旋多巴至少间隔2小时", source, TERMS)).as(source).isFalse();
        }
        assertThat(MedicalDoseAssertions.supports("铁剂与左旋多巴至少间隔2小时", interval, TERMS)).isFalse();
        String ordered = "在服用抗生素至少2小时后再食用益生菌。";
        assertThat(MedicalDoseAssertions.supports("在服用益生菌至少2小时后再食用抗生素。", ordered, TERMS)).isFalse();
        assertThat(MedicalDoseAssertions.supports("益生菌与抗生素至少间隔2小时。", ordered, TERMS)).isFalse();
    }

    private static String g13V7Answer() {
        // 原v7最终回答；报告SHA76b312…5bcc、requestId1ef891269ea441c9，不是重建草稿。
        return "**具体风险**：铁剂与左旋多巴合用，会降低左旋多巴的吸收，左旋多巴的生物利用度可能明显下降 [4]。"
                + "同一份资料给出的处理建议是：两者应间隔至少 2 小时服用 [4]。\n\n"
                + "另外，中也记载了同一方向的提示：铁剂与左旋多巴合用时，左旋多巴的生物利用度可明显下降 [1]。\n\n"
                + "**关于收录情况**：\n"
                + "- 「铁剂」在平台知识图谱中已收录，未发现其他已知相互作用或人群禁忌——但这不等于「铁剂与左旋多巴没问题」，两者的相互作用已单独列出 [4]。\n"
                + "- 「左旋多巴」已收录，上述两条相互作用即来自它的记录 [1][4]。\n\n"
                + "如果服药后出现药效变化，请及时联系开方医生。\n\n"
                + "平台知识库中没有查到针对你个人情况的进一步依据，以上仅为资料原文的复述。";
    }

    private static List<DocumentChunk> g13V7Evidence() {
        return List.of(
                DocumentChunk.builder().docId("KB-0029").chunkId("KB-0029_6").title("孕期复合营养包产品说明书")
                        .position("《孕期复合营养包产品说明书》 > 第四章 相互作用与注意事项")
                        .content("《孕期复合营养包产品说明书》 > 第四章 相互作用与注意事项\n"
                                + "4.1 本品所含铁可影响左旋多巴的吸收，服用左旋多巴者应避免与本品同时服用，并遵医嘱调整服药与进餐时间。"
                                + "铁剂与左旋多巴合用时，左旋多巴的生物利用度可明显下降。")
                        .score(0.4288576939493044).reranked(true).graphBacked(true).source("manual").build(),
                DocumentChunk.builder().docId("KB-0023").chunkId("KB-0023_5").title("植物蛋白粉产品说明书")
                        .position("《植物蛋白粉产品说明书》 > 第四章 相互作用与注意事项")
                        .content("《植物蛋白粉产品说明书》 > 第四章 相互作用与注意事项\n"
                                + "4.1 蛋白质与左旋多巴合用可能影响左旋多巴的吸收，服用左旋多巴者应避免与高蛋白食物或本品同时服用，"
                                + "并遵医嘱调整服药与进餐时间。")
                        .score(0.26136984074194725).reranked(true).source("manual").build(),
                DocumentChunk.builder().docId("KB-0030").chunkId("KB-0030_7").title("全营养配方粉产品说明书")
                        .position("《全营养配方粉产品说明书》 > 第四章 相互作用与注意事项 > 4.3 本品含蛋白质与钙")
                        .content("《全营养配方粉产品说明书》 > 第四章 相互作用与注意事项 > 4.3 本品含蛋白质与钙\n"
                                + "4.3 本品含蛋白质与钙，与左旋多巴同用可能影响左旋多巴的吸收，服用左旋多巴者应遵医嘱安排给药与喂养时间。")
                        .score(0.2565867637231406).reranked(true).source("manual").build(),
                DocumentChunk.builder().docId("KB-0010").chunkId("KB-0010_5").title("膳食补充剂摄入上限与常见药物相互作用")
                        .position("《膳食补充剂摄入上限与常见药物相互作用》")
                        .content("2.4 铁剂与左旋多巴、甲状腺素合用会降低后者吸收，应间隔至少 2 小时。")
                        .graphBacked(true).source("graph").build());
    }

    @Test
    void KG08真实后置出血风险方向保留但反向与陌生药不借来源() {
        var evidence = List.of(source("孕期 DHA 藻油软胶囊产品说明书",
                "本品所含DHA具有轻微抗血小板聚集作用，与华法林、阿司匹林等抗凝或抗血小板药物合用时可能增加出血风险，合用前须咨询医师。"));
        String claim = "孕期 DHA 藻油软胶囊与抗凝药合用的主要风险是**出血风险增加** [1]。";
        assertThat(MedicalEvidenceVerifier.verify(true, claim, evidence).unsupported()).isEmpty();
        for (String wrong : List.of(claim.replace("增加", "降低"), claim.replace("增加", "不会增加"), claim.replace("抗凝药", "陌生药物"))) {
            assertThat(MedicalEvidenceVerifier.verify(true, wrong, evidence).unsupported()).as(wrong).containsExactly(wrong);
        }
    }

    @Test
    void KG10真实结论标签及同句错开保留完整参与组() {
        var evidence = List.of(source("膳食补充剂摄入上限与常见药物相互作用",
                "2.5 益生菌与抗生素同服会显著降低活菌存活率，建议错开2小时以上。"));
        String claim = "**结论：** 益生菌与抗生素同服会显著降低活菌存活率，建议错开 2 小时以上 [1]。";
        assertThat(MedicalEvidenceVerifier.verify(true, claim, evidence).unsupported()).isEmpty();
        for (String wrong : List.of(claim.replace("2 小时", "3 小时"), claim.replace("抗生素", "陌生药物"))) {
            assertThat(MedicalEvidenceVerifier.verify(true, wrong, evidence).unsupported()).as(wrong).containsExactly(wrong);
        }
        assertThat(MedicalDoseAssertions.supports("益生菌与抗生素至少间隔2小时",
                "益生菌与抗生素同服会显著降低活菌存活率。钙与铁同服，建议错开2小时以上。", TERMS)).isFalse();
    }

    @Test
    void G14原文折合数量保同一营养素角色而不做单位推算() {
        var evidence = List.of(source("中国居民膳食营养素参考摄入量速查",
                "维生素D推荐摄入量成人10微克每日（折合400国际单位），65岁以上15微克每日，可耐受最高摄入量50微克每日（折合2000国际单位）。"));
        String claim = "**成人维生素 D 的每日可耐受最高摄入量是 2000IU**，另一份资料给出的是同一数值的另一种写法——50 微克每日，折合 2000 国际单位 [1]。";
        assertThat(MedicalEvidenceVerifier.verify(true, claim, evidence).unsupported()).isEmpty();
        for (String wrong : List.of(claim.replace("50 微克", "60 微克"), claim.replace("维生素 D", "铁"), claim.replace("可耐受最高摄入量", "推荐摄入量"))) {
            assertThat(MedicalEvidenceVerifier.verify(true, wrong, evidence).unsupported()).as(wrong).containsExactly(wrong);
        }
        assertThat(MedicalEvidenceVerifier.verify(true, claim, List.of(source("维生素D摄入资料",
                "维生素D成人每日可耐受最高摄入量为2000IU。"))).unsupported()).containsExactly(claim);
    }

    @Test
    void G06真实版本行的每瓶数量保持产品主体而不借叶酸() {
        var evidence = List.of(source("铁叶酸片产品说明书",
                "《铁叶酸片产品说明书》 > 第一章 产品概述\n1.1 本品为膳食营养补充剂，主要成分为富马酸亚铁与叶酸。\n"
                        + "1.2 规格为每片含铁 10 毫克、叶酸 400 微克，每瓶 60 片。"));
        String claim = "- 版本 v2026.05：每片含铁 **10 毫克**、叶酸 **400 微克**，每瓶 60 片 [1]；";
        assertThat(MedicalEvidenceVerifier.verify(true, claim, evidence).unsupported()).isEmpty();
        assertThat(MedicalEvidenceVerifier.verify(true, claim.replace("60 片", "90 片"), evidence).unsupported()).hasSize(1);
        assertThat(MedicalEvidenceVerifier.verify(true, claim.replace("10 毫克", "15 毫克"), evidence).unsupported()).hasSize(1);
    }

    @Test
    void KG08真实完整并列与逗号后风险保同一参与组() {
        var evidence = List.of(source("孕期 DHA 藻油软胶囊产品说明书",
                "《孕期 DHA 藻油软胶囊产品说明书》 > 第四章 相互作用与注意事项\n"
                        + "4.1 本品所含 DHA 具有轻微抗血小板聚集作用，与华法林、阿司匹林等抗凝或抗血小板药物合用时可能增加出血风险，合用前须咨询医师。"));
        String claim = "孕期 DHA 藻油软胶囊所含的 DHA 具有轻微抗血小板聚集作用，与华法林、阿司匹林等抗凝或抗血小板药物合用时，可能增加出血风险，合用前须咨询医师（《孕期 DHA 藻油软胶囊产品说明书》） [1]。";
        assertThat(MedicalEvidenceVerifier.verify(true, claim, evidence).unsupported()).isEmpty();
        for (String wrong : List.of(claim.replace("增加出血", "降低出血"), claim.replace("华法林", "陌生药物"))) {
            assertThat(MedicalEvidenceVerifier.verify(true, wrong, evidence).unsupported()).as(wrong).hasSize(1);
        }
    }

    @Test
    void KG12真实双成分并列药物和氯吡格雷总结保关系生命周期() {
        var evidence = List.of(source("深海鱼油胶丸（高纯度）产品说明书",
                "《深海鱼油胶丸（高纯度）产品说明书》 > 第四章 相互作用与注意事项\n"
                        + "4.1 本品所含 EPA 与 DHA 具有抗血小板聚集作用，与华法林、阿司匹林、氯吡格雷等抗凝或抗血小板药物合用时可能增加出血风险，合用前须咨询医师。"));
        String full = "- 鱼油中的 EPA 与 DHA 具有抗血小板聚集作用，与华法林、阿司匹林、氯吡格雷等抗凝或抗血小板药物合用时，可能增加出血风险，**合用前须咨询医师**（《深海鱼油胶丸（高纯度）产品说明书》） [1]。";
        String summary = "**结论：高纯度鱼油的 EPA 与 DHA 与氯吡格雷合用，可能增加出血风险，合用前须咨询医师 [1]。";
        for (String claim : List.of(full, summary)) {
            assertThat(MedicalEvidenceVerifier.verify(true, claim, evidence).unsupported()).as(claim).isEmpty();
            assertThat(MedicalEvidenceVerifier.verify(true, claim.replace("增加出血", "不会增加出血"), evidence).unsupported()).hasSize(1);
            assertThat(MedicalEvidenceVerifier.verify(true, claim.replace("氯吡格雷", "陌生药物"), evidence).unsupported()).hasSize(1);
        }
        var unrelated = List.of(source("同服说明", "EPA与DHA合用会降低出血风险。华法林与氯吡格雷合用会增加出血风险。"));
        assertThat(MedicalEvidenceVerifier.verify(true, "EPA与DHA和氯吡格雷合用，可能增加出血风险 [1]。", unrelated).unsupported()).hasSize(1);
        assertThat(MedicalEvidenceVerifier.verify(true, full, List.of(source("磷虾油软胶囊产品说明书",
                "本品所含EPA与DHA，与华法林、阿司匹林、氯吡格雷合用时可能增加出血风险。"))).unsupported()).hasSize(1);
        assertThat(MedicalEvidenceVerifier.verify(true, full, List.of(source("深海鱼油胶丸（高纯度）产品说明书",
                "EPA与DHA，与华法林、阿司匹林、氯吡格雷合用时可能增加出血风险。"))).unsupported()).hasSize(1);
    }

    private static DocumentChunk source(String title, String content) {
        return DocumentChunk.builder().docId("KB-V6").chunkId("KB-V6_0").title(title)
                .position("《" + title + "》").content(content).score(0.9).build();
    }

    private static DocumentChunk doc(String content) {
        return DocumentChunk.builder().docId("KB-T7").chunkId("KB-T7_0").title("药物相互作用说明")
                .content(content).score(0.9).build();
    }
}
