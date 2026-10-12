package yumefusaka.envoymart.agent.core;

import org.junit.jupiter.api.Test;
import yumefusaka.envoymart.agent.rag.DocumentChunk;
import yumefusaka.envoymart.agent.rag.EvidenceGate;
import yumefusaka.envoymart.agent.rag.MedicalEvidenceVerifier;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentMedicalPublicationTest {
    private static final String DECLARATION = "以下资料来自碳酸钙 D3 咀嚼片产品说明书。\n";
    private static final String PRONOUN = "本品所含钙可影响左甲状腺素、四环素类抗生素、喹诺酮类抗生素的吸收，与上述药物合用时应间隔至少 2 小时 [1]。";
    private static final String INDEPENDENT = "钙剂与四环素类、喹诺酮类抗生素合用会形成难吸收的络合物，两者服用时间应间隔 2 小时以上 [2]。";

    @Test
    void KG02最终正文失去来源商品声明后不能继续发布本品断言() {
        var response = response();
        assertThat(MedicalEvidenceVerifier.verify(true, PRONOUN + "\n" + INDEPENDENT,
                response.getKnowledge()).unsupported()).contains(PRONOUN);

        var published = Agent.finalizeMedicalReply(response, PRONOUN + "\n" + INDEPENDENT, true,
                EvidenceGate.Thresholds.defaults(), false);

        assertThat(published.reply()).contains(INDEPENDENT).doesNotContain(PRONOUN);
        assertThat(published.reasons()).containsKey(PRONOUN);
        assertThat(MedicalEvidenceVerifier.verify(true, published.reply(), response.getKnowledge()).unsupported()).isEmpty();
    }

    @Test
    void 被拒绝的商品声明不能授权后续本品临床断言() {
        String rejected = "碳酸钙 D3 咀嚼片与喹诺酮类抗生素没有相互作用 [1]。";
        var response = response();
        var published = Agent.finalizeMedicalReply(response, rejected + "\n" + PRONOUN + "\n" + INDEPENDENT,
                true, EvidenceGate.Thresholds.defaults(), false);

        assertThat(published.reply()).contains(INDEPENDENT).doesNotContain(rejected, PRONOUN);
        assertThat(published.reasons()).containsKeys(rejected, PRONOUN);
        assertThat(MedicalEvidenceVerifier.verify(true, published.reply(), response.getKnowledge()).unsupported()).isEmpty();
    }

    @Test
    void 保留的明确商品声明和独立强来源事实不被末审误删() {
        String answer = DECLARATION + PRONOUN + "\n" + INDEPENDENT;
        var published = Agent.finalizeMedicalReply(response(), answer, true, EvidenceGate.Thresholds.defaults(), false);
        assertThat(published.reply()).isEqualTo(answer);
        assertThat(published.reasons()).isEmpty();
    }

    @Test
    void 非医学内容不增加医学发布审核() {
        String answer = "本品现货，下单后配送。";
        assertThat(Agent.finalizeMedicalReply(response(), answer, false, EvidenceGate.Thresholds.defaults(), false).reply())
                .isEqualTo(answer);
    }

    @Test
    void 前置审核已剔光事实时标题与提醒必须成为明确拒答() {
        var published = Agent.finalizeMedicalReply(response(), "**参考资料**\n\n请遵医嘱。", true,
                EvidenceGate.Thresholds.defaults(), true);
        assertThat(published.reply()).contains("证据不足", "咨询医师或药师").doesNotContain("参考资料");
    }

    @Test
    void 仅余本品句被末审删除后确定拒答且保留拒绝理由() {
        var published = Agent.finalizeMedicalReply(response(), PRONOUN, true,
                EvidenceGate.Thresholds.defaults(), false);
        assertThat(published.reply()).contains("证据不足", "咨询医师或药师").doesNotContain(PRONOUN);
        assertThat(published.reasons()).containsKey(PRONOUN);
    }

    @Test
    void 有结构化支持的确定性未知边界不作为空壳抹去() {
        var response = response();
        response.setEvidenceLevel(EvidenceGate.Level.SUFFICIENT);
        response.setToolExecutions(List.of(yumefusaka.envoymart.agent.llm.ToolExecution.builder()
                .tool("interaction_check").success(true).rawData(java.util.Map.of("available", true, "items", List.of(
                        java.util.Map.of("input", "磷虾油", "found", false),
                        java.util.Map.of("input", "阿司匹林", "found", true)))).build()));
        String boundary = Agent.enforceUnknownInteractionRefusal(response, "", true);
        var published = Agent.finalizeMedicalReply(response, boundary, true, EvidenceGate.Thresholds.defaults(), true);
        assertThat(published.reply()).isEqualTo(boundary);
        assertThat(published.reasons()).isEmpty();
    }

    @Test
    void 普通对话与未知评测标识不记录医学草稿正文() {
        assertThat(Agent.evalMedicalAuditBody(null, "私人医学正文")).isNull();
        assertThat(Agent.evalMedicalAuditBody("用户问题", "私人医学正文")).isNull();
        assertThat(Agent.evalMedicalAuditBody("G-99", "私人医学正文")).isNull();
        assertThat(Agent.evalMedicalAuditBody("G-14", "维生素D每日上限2000IU。"))
                .contains("维生素D每日上限2000IU。", "\"truncated\":false");
    }

    @Test
    void 固定评测草稿取证有界且显式标注截断() {
        String oversized = "医学正文".repeat(1000);
        assertThat(Agent.evalMedicalAuditBody("G-14", oversized)).contains("\"truncated\":true");
        assertThat(Agent.evalMedicalAuditBody("G-14", oversized)).hasSizeLessThan(2200);
    }

    @Test
    void KG04删除华法林父项后关系子项不能迁移给前面的维生素K2() {
        String preceding = "- **维生素 K2**：图谱中已收录，但未发现已知的相互作用或人群禁忌。\n";
        String parent = "- **华法林**：图谱中已收录，与以下成分存在相互作用记录：";
        String child = "  - 与 **EPA**、**DHA**：可能增加出血风险 [1]";
        var published = Agent.finalizeMedicalReply(listResponse(), preceding + parent + "\n" + child,
                true, EvidenceGate.Thresholds.defaults(), false);
        assertThat(published.reply()).doesNotContain(parent, child, "EPA", "DHA");
        assertThat(published.reasons()).containsKeys(parent, child.trim());
        assertThat(published.reasons().get(child.trim())).contains("父项");
    }

    @Test
    void KG04父项联合剔除覆盖多层子树且独立完整子句升为同级() {
        String parent = "- **华法林**：图谱中已收录，与以下成分存在相互作用记录：";
        String child = "  - 与 **EPA**、**DHA**：可能增加出血风险 [1]";
        String grandchild = "    - 合用前须咨询医师 [1]。";
        String independent = "  - 铁剂与左旋多巴至少间隔2小时 [2]。";
        var published = Agent.finalizeMedicalReply(listResponse(), parent + "\n" + child + "\n" + grandchild + "\n" + independent,
                true, EvidenceGate.Thresholds.defaults(), false);
        assertThat(published.reply()).contains("- 铁剂与左旋多巴至少间隔2小时 [2]。").doesNotContain(parent, child, grandchild);
        assertThat(published.reply()).doesNotContain("  - 铁剂");
        assertThat(published.reasons()).containsKeys(parent, child.trim(), grandchild.trim());
    }

    @Test
    void 同项明确父主体支持关系子句且错误父主体不得借来源() {
        String child = "  - 与 **EPA**、**DHA**：可能增加出血风险 [1]";
        var response = listResponse();
        String valid = "- **华法林**\n" + child;
        assertThat(Agent.finalizeMedicalReply(response, valid, true, EvidenceGate.Thresholds.defaults(), false).reply()).isEqualTo(valid);
        String wrong = "- **维生素 K2**\n" + child;
        var rejected = Agent.finalizeMedicalReply(response, wrong, true, EvidenceGate.Thresholds.defaults(), false);
        assertThat(rejected.reply()).doesNotContain(child);
        assertThat(rejected.reasons()).containsKey(child.trim());
    }

    @Test
    void 孤立或跨空段和不同列表的省略主体子句不能合格() {
        String child = "  - 与 **EPA**、**DHA**：可能增加出血风险 [1]";
        for (String answer : List.of(child, "- **华法林**\n\n" + child,
                "- **华法林**\n**另一组**\n" + child, "- **华法林**\n- **维生素 K2**\n" + child)) {
            var published = Agent.finalizeMedicalReply(listResponse(), answer, true, EvidenceGate.Thresholds.defaults(), false);
            assertThat(published.reply()).as(answer).doesNotContain(child);
            assertThat(published.reasons()).as(answer).containsKey(child.trim());
        }
    }

    @Test
    void 双对象均收录但最终医学事实不足时拒答保当前关系范围() {
        var response = listResponse();
        response.setToolExecutions(List.of(check(true, true, "磷虾油软胶囊", "阿司匹林")));
        var published = Agent.finalizeMedicalReply(response, "请遵医嘱。", true, EvidenceGate.Thresholds.defaults(), true);
        assertThat(published.reply()).contains("磷虾油软胶囊", "阿司匹林", "无法", "本次检查", "具体关系", "医师或药师")
                .doesNotContain("安全", "存在相互作用", "出血风险");
    }

    @Test
    void 范围拒答不从早先成功或无效最近检查借对象() {
        for (var latest : List.of(check(false, true, "对象甲", "对象乙"), check(true, false, "对象甲", "对象乙"),
                yumefusaka.envoymart.agent.llm.ToolExecution.builder().tool("interaction_check").success(true).rawData(java.util.Map.of("available", true)).build())) {
            var response = listResponse();
            response.setToolExecutions(List.of(check(true, true, "磷虾油软胶囊", "阿司匹林"), latest));
            assertThat(Agent.finalizeMedicalReply(response, "请遵医嘱。", true, EvidenceGate.Thresholds.defaults(), true).reply())
                    .doesNotContain("磷虾油软胶囊", "阿司匹林", "对象甲", "对象乙");
        }
    }

    @Test
    void 已有完整强医学回答不附加双对象范围拒答() {
        var response = listResponse();
        response.setToolExecutions(List.of(check(true, true, "铁剂", "左旋多巴")));
        String answer = "铁剂与左旋多巴至少间隔2小时 [2]。";
        assertThat(Agent.finalizeMedicalReply(response, answer, true, EvidenceGate.Thresholds.defaults(), false).reply()).isEqualTo(answer);
    }

    private static yumefusaka.envoymart.agent.llm.ToolExecution check(boolean success, boolean available, String first, String second) {
        return yumefusaka.envoymart.agent.llm.ToolExecution.builder().tool("interaction_check").success(success)
                .rawData(java.util.Map.of("available", available, "items", List.of(
                        java.util.Map.of("input", first, "label", first, "found", true, "risks", List.of()),
                        java.util.Map.of("input", second, "label", second, "found", true, "risks", List.of())))).build();
    }

    @Test
    void 拒绝父项后的无列表符缩进续行仍是依赖子树() {
        String parent = "- **华法林**：图谱中已收录，与以下成分存在相互作用记录：";
        String continuation = "    与EPA、DHA：可能增加出血风险 [1]。";
        String answer = "- **维生素 K2**：图谱中已收录。\n" + parent + "\n" + continuation;
        var published = Agent.finalizeMedicalReply(listResponse(), answer, true, EvidenceGate.Thresholds.defaults(), false);
        assertThat(published.reply()).doesNotContain(parent, continuation, "EPA", "DHA");
        assertThat(published.reasons()).containsKeys(parent, continuation.trim());
        assertThat(published.reasons().get(continuation.trim())).contains("父项");
    }

    @Test
    void 续行继承明确父主体但空行标题新正文不能跨边界继承() {
        String continuation = "    与EPA、DHA：可能增加出血风险 [1]。";
        String valid = "- **华法林**\n" + continuation;
        assertThat(Agent.finalizeMedicalReply(listResponse(), valid, true, EvidenceGate.Thresholds.defaults(), false).reply()).isEqualTo(valid);
        for (String answer : List.of(continuation, "- **华法林**\n\n" + continuation,
                "- **华法林**\n**另一组**\n" + continuation, "- **华法林**\n新的正文\n" + continuation,
                "- **维生素 K2**\n" + continuation)) {
            assertThat(Agent.finalizeMedicalReply(listResponse(), answer, true, EvidenceGate.Thresholds.defaults(), false).reply())
                    .as(answer).doesNotContain(continuation);
        }
    }


    @Test
    void G05同一列表行首句被拒仍保留独立强源摄入量句() {
        String rejected = "- 该产品规格为每粒400IU，每瓶90粒 [1]。";
        String supported = "健康成年人每日维生素D推荐摄入量为400IU，可耐受最高摄入量为2000IU [1]。";
        var chunk = DocumentChunk.builder().docId("KB-0005").chunkId("KB-0005_3").score(0.9)
                .title("维生素D3软胶囊产品说明书")
                .content("健康成年人每日维生素D推荐摄入量为400IU，可耐受最高摄入量为2000IU。").build();
        var response = Agent.AgentResponse.builder().knowledge(List.of(chunk)).toolExecutions(List.of()).build();
        var published = Agent.finalizeMedicalReply(response, rejected + supported, true, EvidenceGate.Thresholds.defaults(), false);
        assertThat(published.reply()).contains(supported).doesNotContain(rejected);
    }

    @Test
    void G14同一列表行被拒用法不连带删除独立强源上限句() {
        String rejected = "- 说明书上的用法用量是成人每日1粒，随餐服用，餐后吸收更佳 [1]；";
        String supported = "健康成年人每日维生素D推荐400IU，可耐受最高2000IU [1]。";
        var chunk = DocumentChunk.builder().docId("KB-0005").chunkId("KB-0005_3").score(0.9)
                .title("维生素D3软胶囊产品说明书")
                .content("健康成年人每日维生素D推荐400IU，可耐受最高2000IU。").build();
        var response = Agent.AgentResponse.builder().knowledge(List.of(chunk)).toolExecutions(List.of()).build();
        var published = Agent.finalizeMedicalReply(response, rejected + supported, true, EvidenceGate.Thresholds.defaults(), false);
        assertThat(published.reply()).contains(supported).doesNotContain(rejected);
    }

    @Test
    void KG06与药物标签后的完整主体代谢关系独立核验() {
        var chunk = DocumentChunk.builder().docId("KB-0013").chunkId("KB-0013_6").score(0.9)
                .title("褪黑素片产品说明书")
                .content("4.3 褪黑素可能影响华法林、部分降压药与免疫抑制剂的代谢，正在服用处方药者使用前须咨询医师。").build();
        var response = Agent.AgentResponse.builder().knowledge(List.of(chunk)).toolExecutions(List.of()).build();
        String valid = "- **与华法林：** 褪黑素可能影响华法林、部分降压药与免疫抑制剂的代谢，正在服用处方药者使用前须咨询医师 [1]。";
        assertThat(Agent.finalizeMedicalReply(response, valid, true, EvidenceGate.Thresholds.defaults(), false).reply()).isEqualTo(valid);
        String wrong = valid.replace("褪黑素可能", "维生素K2可能");
        assertThat(Agent.finalizeMedicalReply(response, wrong, true, EvidenceGate.Thresholds.defaults(), false).reply()).doesNotContain(wrong);
    }


    @Test
    void 拒父项同行的完整独立句不授权原依赖子树() {
        String parent = "- 华法林不存在任何相互作用 [1]；";
        String independent = "铁剂与左旋多巴至少间隔2小时 [2]。";
        String child = "  - 与EPA、DHA：可能增加出血风险 [1]。";
        var published = Agent.finalizeMedicalReply(listResponse(), parent + independent + "\n" + child,
                true, EvidenceGate.Thresholds.defaults(), false);
        assertThat(published.reply()).contains("- " + independent).doesNotContain(parent, child);
        assertThat(published.reasons().get(child.trim())).contains("父项");
    }

    private static Agent.AgentResponse listResponse() {
        var risk = DocumentChunk.builder().docId("KB-0010").chunkId("KB-0010_4").source("graph")
                .title("膳食补充剂摄入上限与常见药物相互作用").graphBacked(true)
                .content("2.2 鱼油中的EPA与DHA具有轻度抗血小板作用，与华法林、阿司匹林等抗凝或抗血小板药物合用可能增加出血风险。").build();
        var interval = DocumentChunk.builder().docId("KB-0010").chunkId("KB-0010_5").score(0.9)
                .title("药物相互作用资料").content("铁剂与左旋多巴至少间隔2小时。").build();
        return Agent.AgentResponse.builder().knowledge(List.of(risk, interval)).toolExecutions(List.of()).build();
    }

    private static Agent.AgentResponse response() {
        var product = DocumentChunk.builder().docId("KB-0027").chunkId("KB-0027_5")
                .title("碳酸钙 D3 咀嚼片产品说明书").score(0.8499185509230057).graphBacked(true)
                .content("《碳酸钙 D3 咀嚼片产品说明书》 > 第四章 相互作用与注意事项\n4.1 本品所含钙可影响左甲状腺素、四环素类抗生素、喹诺酮类抗生素的吸收，与上述药物合用时应间隔至少 2 小时。").build();
        var guideline = DocumentChunk.builder().docId("KB-0010").chunkId("KB-0010_5")
                .title("膳食补充剂摄入上限与常见药物相互作用").source(DocumentChunk.SOURCE_GRAPH).graphBacked(true)
                .content("2.3 钙剂与四环素类、喹诺酮类抗生素合用会形成难吸收的络合物，两者服用时间应间隔 2 小时以上。").build();
        return Agent.AgentResponse.builder().knowledge(List.of(product, guideline)).toolExecutions(List.of()).build();
    }
}
