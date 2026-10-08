package yumefusaka.envoymart.aiservice.tool;

import org.junit.jupiter.api.Test;
import yumefusaka.envoymart.agent.tool.ToolCall;
import yumefusaka.envoymart.agent.tool.ToolResult;
import yumefusaka.envoymart.aiservice.client.KnowledgeClient;
import yumefusaka.envoymart.common.result.Result;
import yumefusaka.envoymart.contract.GraphEdge;
import yumefusaka.envoymart.contract.GraphNode;
import yumefusaka.envoymart.contract.InteractionReport;
import yumefusaka.envoymart.contract.Substance;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 相互作用工具的输出契约。
 * <p>
 * 断言全部落在<b>给模型看的文本</b>上，而不是「有没有拿到报告」这种中间态。
 * 理由是这个工具最危险的失败方式不抛异常、不打日志：{@code 图谱不可用}、
 * {@code 图谱里没收录}、{@code 已收录但无风险}三种状态在数据结构上都是
 * 「一堆空列表」，而它们对用户是<b>相反的三句话</b>——
 * 「系统坏了」「我没查过这东西」「查过了，没事」。模型只看得见拼出来的文本，
 * 所以那三种状态必须在文本里长得不一样。
 */
class InteractionCheckToolTest {

    @Test
    void 图谱工具将完整出处带入结构化证据() {
        GraphEdge edge = new GraphEdge(PRODUCT, "INTERACTS_WITH", "可能增加出血风险", WARFARIN,
                "KB-0005", "深海鱼油说明书", "c12", 100, 130,
                "深海鱼油与华法林合用可能增加出血风险", WARFARIN, List.of("鱼油软胶囊", "华法林"));
        var report = new InteractionReport(true, null, List.of(new InteractionReport.Item(
                "SPU5", "鱼油软胶囊", true, List.of(), List.of(edge), null)));
        assertThat(InteractionCheckTool.evidenceOf(report)).singleElement().satisfies(chunk -> {
            assertThat(chunk.getDocId()).isEqualTo("KB-0005");
            assertThat(chunk.getChunkId()).isEqualTo("c12");
            assertThat(chunk.getCharOffset()).isEqualTo(100);
            assertThat(chunk.getContent()).isEqualTo(edge.quote());
            assertThat(chunk.getGraphBacked()).isTrue();
        });
    }

    private static final GraphNode PRODUCT = new GraphNode("spu5", "鱼油软胶囊", "PRODUCT");
    private static final GraphNode FISH_OIL = new GraphNode("深海鱼油", "深海鱼油", "INGREDIENT");
    private static final GraphNode WARFARIN = new GraphNode("华法林", "华法林", "DRUG");

    private ToolCall call(String items) {
        return new ToolCall("t1", "interaction_check", Map.of("items", items), false, "u1001");
    }

    private ToolResult run(InteractionReport report, String items) {
        KnowledgeClient client = mock(KnowledgeClient.class);
        when(client.interactions(anyString())).thenReturn(Result.success(report));
        return new InteractionCheckTool(client).execute(call(items));
    }

    // ==================== 三种「空」必须分得开 ====================

    @Test
    void 图谱不可用时必须说没查成而不是没有冲突() {
        ToolResult result = run(new InteractionReport(false, "Neo4j 连不上", List.of()), "SPU5,华法林");

        assertThat(result.isSuccess())
                .as("图谱挂了是环境问题，不是模型调错了参数——报成失败会让模型重试，而重试不会有结果")
                .isTrue();
        assertThat(result.getOutput())
                .as("必须明说没查成")
                .contains("没有查成")
                .as("并且必须堵死「没有冲突」这个读法，否则模型会顺手说一句没事")
                .contains("不要说「没有冲突」")
                .doesNotContain("未发现已知的相互作用");
    }

    @Test
    void 未收录的项不得被说成没有风险() {
        ToolResult result = run(new InteractionReport(true, null, List.of(
                new InteractionReport.Item("维生素K2", "维生素K2", false, List.of(), List.of(), null)
        )), "维生素K2");

        assertThat(result.getOutput())
                .as("「没收录」与「没风险」在界面上长得一样，但在用药场景里意思相反")
                .contains("图谱中没有收录")
                .contains("没有收录不等于安全")
                .doesNotContain("未发现已知的相互作用");
    }

    @Test
    void 已收录且无风险才说未发现已知风险() {
        ToolResult result = run(new InteractionReport(true, null, List.of(
                new InteractionReport.Item("维生素D3", "维生素D3", true, List.of(), List.of(), null)
        )), "维生素D3");

        assertThat(result.getOutput())
                .contains("已收录")
                .contains("未发现已知的相互作用或人群禁忌");
    }

    // ==================== 有风险时溯源要能给出来 ====================

    @Test
    void 风险条目要带出处与中文关系名() {
        GraphEdge risk = new GraphEdge(PRODUCT, "INTERACTS_WITH", "可能增加出血风险", WARFARIN,
                "KB-0005", "深海鱼油说明书", "c12", 100, 130,
                "深海鱼油与华法林合用可能增加出血风险", WARFARIN,
                List.of("鱼油软胶囊", "深海鱼油", "华法林"));
        InteractionReport report = new InteractionReport(true, null, List.of(
                new InteractionReport.Item("SPU5", "鱼油软胶囊", true,
                        List.of(new Substance("spu5", "鱼油软胶囊", "spu5", "鱼油软胶囊", "PRODUCT",
                                        List.of("鱼油软胶囊")),
                                new Substance("spu5", "鱼油软胶囊", "深海鱼油", "深海鱼油", "INGREDIENT",
                                        List.of("鱼油软胶囊", "深海鱼油"))),
                        List.of(risk), null)));

        String output = run(report, "SPU5").getOutput();

        assertThat(output)
                .as("出处是「图谱结论」与「模型自己说的」之间唯一的区别")
                .contains("《深海鱼油说明书》")
                .contains("深海鱼油与华法林合用可能增加出血风险")
                .as("关联路径让用户看懂为什么商品会和处方药扯上关系")
                .contains("鱼油软胶囊 → 深海鱼油 → 华法林")
                .as("枚举名不能漏给模型，它会照着念给用户")
                .contains("相互作用")
                .doesNotContain("INTERACTS_WITH");
        assertThat(output)
                .as("查的是商品、风险在药物上，渲染的必须是「对方」那一端")
                .contains("与「华法林」");
    }

    @Test
    void 查药物时不得渲染成自己和自己冲突() {
        // CAUTION_FOR 的方向在图上不能翻，于是查「华法林」这种两端都可能是它自己的边时，
        // 直接渲染 head/tail 会输出「华法林 与 华法林 有相互作用」
        GraphEdge risk = new GraphEdge(WARFARIN, "CAUTION_FOR", "需先咨询医师", WARFARIN,
                "KB-0009", "抗凝药说明", "c3", 0, 10, "用药期间应咨询医师", WARFARIN,
                List.of("华法林"));
        InteractionReport report = new InteractionReport(true, null, List.of(
                new InteractionReport.Item("华法林", "华法林", true,
                        List.of(new Substance("华法林", "华法林", "华法林", "华法林", "DRUG",
                                List.of("华法林"))),
                        List.of(risk), null)));

        assertThat(run(report, "华法林").getOutput())
                .contains("人群禁忌")
                .doesNotContain("INTERACTS_WITH");
    }

    // ==================== 入参 ====================

    @Test
    void 中文标点也要能分隔() {
        assertThat(InteractionCheckTool.parseItems("鱼油、华法林"))
                .as("只按英文逗号切的话，整串会被当成一个实体，查出来是「没有收录」——"
                        + "一个由分隔符导致的假阴性，日志上完全看不出来")
                .containsExactly("鱼油", "华法林");

        assertThat(InteractionCheckTool.parseItems("鱼油，华法林；阿司匹林"))
                .containsExactly("鱼油", "华法林", "阿司匹林");
    }

    @Test
    void 入参在上限处截断且丢弃超长项() {
        assertThat(InteractionCheckTool.parseItems("a,b,c,d,e,f,g,h"))
                .as("模型可能把用户整段输入塞进来，图上的物质展开是变长路径")
                .hasSize(6);

        assertThat(InteractionCheckTool.parseItems("鱼油," + "很长".repeat(30)))
                .as("超过长度上限的不可能是实体名，只会是模型塞进来的一句话")
                .containsExactly("鱼油");

        assertThat(InteractionCheckTool.parseItems(null)).isEmpty();
        assertThat(InteractionCheckTool.parseItems(List.of("鱼油", "  ", "华法林")))
                .containsExactly("鱼油", "华法林");
    }

    @Test
    void 没有可用入参时如实报错() {
        KnowledgeClient client = mock(KnowledgeClient.class);
        ToolResult result = new InteractionCheckTool(client).execute(call("  "));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("items");
    }

    // ==================== 契约反序列化失败 ====================

    @Test
    void 报告为空时不得顺着往下渲染() {
        KnowledgeClient client = mock(KnowledgeClient.class);
        when(client.interactions(anyString())).thenReturn(Result.error(500, "boom"));

        ToolResult result = new InteractionCheckTool(client).execute(call("SPU5"));

        assertThat(result.isSuccess())
                .as("拿到不了一份完整报告就什么都判断不了，成功返回等于给模型留了编造的口子")
                .isFalse();
    }
}
