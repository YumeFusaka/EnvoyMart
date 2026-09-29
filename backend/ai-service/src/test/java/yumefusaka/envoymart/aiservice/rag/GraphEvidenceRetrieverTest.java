package yumefusaka.envoymart.aiservice.rag;

import org.junit.jupiter.api.Test;
import yumefusaka.envoymart.agent.rag.DocumentChunk;
import yumefusaka.envoymart.aiservice.client.KnowledgeClient;
import yumefusaka.envoymart.common.result.Result;
import yumefusaka.envoymart.contract.GraphEdge;
import yumefusaka.envoymart.contract.GraphNode;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 图谱依据适配器的输出契约。
 * <p>
 * 这一层唯一的职责是<b>把一条图谱边翻译成一份与知识库切片同构的依据</b>。
 * 翻译错了不会报错——它会安静地产生一份「看起来像依据、但点不回原文」的东西，
 * 而那正好是这套溯源机制最不该有的产物。
 */
class GraphEvidenceRetrieverTest {

    private static final GraphNode FISH_OIL = new GraphNode("深海鱼油", "深海鱼油", "INGREDIENT");
    private static final GraphNode WARFARIN = new GraphNode("华法林", "华法林", "DRUG");

    private static GraphEdge riskEdge(String chunkId) {
        return new GraphEdge(FISH_OIL, "INTERACTS_WITH", "可能增加出血风险", WARFARIN,
                "KB-0005", "深海鱼油说明书", chunkId, 100, 130,
                "深海鱼油与华法林合用可能增加出血风险", null, List.of());
    }

    private static GraphEvidenceRetriever over(Result<List<GraphEdge>> response) {
        KnowledgeClient client = mock(KnowledgeClient.class);
        when(client.recallGraph(anyString(), anyInt())).thenReturn(response);
        return new GraphEvidenceRetriever(client);
    }

    @Test
    void 一条边要翻译成带出处的依据而不是一句渲染好的结论() {
        List<DocumentChunk> chunks = over(Result.success(List.of(riskEdge("c12"))))
                .retrieve("SPU5 和华法林冲突吗", 5);

        assertThat(chunks).hasSize(1);
        DocumentChunk chunk = chunks.get(0);
        assertThat(chunk.getChunkId()).isEqualTo("c12");
        assertThat(chunk.getDocId()).isEqualTo("KB-0005");
        assertThat(chunk.getTitle()).isEqualTo("深海鱼油说明书");
        assertThat(chunk.getCharOffset()).isEqualTo(100);
        assertThat(chunk.getContent())
                .as("「深海鱼油」这个中间项必须在正文里——引文往往只写「本品与华法林合用」，"
                        + "而「本品」指的是谁只在图上。少了这一行，重排器与模型都只看到一句"
                        + "不知道自己为什么被召回的话")
                .contains("深海鱼油").contains("华法林")
                .contains("可能增加出血风险")
                .contains("深海鱼油与华法林合用可能增加出血风险");
        assertThat(chunk.getContent())
                .as("枚举名不能漏给模型，它会照着念给用户")
                .doesNotContain("INTERACTS_WITH")
                .contains("相互作用");
    }

    @Test
    void 没有切片号的边也要各拿一个唯一键() {
        List<DocumentChunk> chunks = over(Result.success(List.of(
                riskEdge(null),
                new GraphEdge(FISH_OIL, "CAUTION_FOR", "孕妇慎用",
                        new GraphNode("孕妇", "孕妇", "POPULATION"),
                        "KB-0005", "深海鱼油说明书", null, 200, 230,
                        "孕妇及哺乳期妇女慎用", null, List.of()))))
                .retrieve("SPU5 有什么禁忌", 5);

        assertThat(chunks)
                .as("RRF 按 chunkId 归一，键为空时两条边会塌成同一个候选——"
                        + "表现是图谱明明召回了两条，提示词里只多出一条依据")
                .hasSize(2)
                .extracting(DocumentChunk::getChunkId)
                .doesNotContainNull()
                .doesNotHaveDuplicates();
    }

    @Test
    void 图谱查不成时返回空而不是抛异常() {
        KnowledgeClient boom = mock(KnowledgeClient.class);
        when(boom.recallGraph(anyString(), anyInt())).thenThrow(new RuntimeException("Neo4j 连不上"));

        assertThat(new GraphEvidenceRetriever(boom).retrieve("SPU5 有禁忌吗", 5))
                .as("这是增强路，图谱挂了该退化成纯文本检索，而不是让整个回答失败")
                .isEmpty();
    }

    @Test
    void 业务错误码与空数据都不产生依据() {
        assertThat(over(Result.error(503, "图谱暂时不可用")).retrieve("SPU5", 5)).isEmpty();
        assertThat(over(Result.success(List.of())).retrieve("SPU5", 5)).isEmpty();
    }

    @Test
    void 空问题不去查图谱() {
        KnowledgeClient client = mock(KnowledgeClient.class);
        when(client.recallGraph(anyString(), anyInt()))
                .thenThrow(new AssertionError("空问题不该发出请求"));

        assertThat(new GraphEvidenceRetriever(client).retrieve("   ", 5)).isEmpty();
    }

    /**
     * 兜底键必须是<b>由内容推出</b>的，不能是随机数。
     * <p>
     * 随机值的症状要等到两路同时召回同一片依据时才出现：RRF 认不出它们是同一片，
     * 于是提示词里出现两份看起来独立的出处——而它们其实是同一句话。
     */
    @Test
    void 兜底键由文档号与偏移推出而不是随机() {
        assertThat(over(Result.success(List.of(riskEdge(null)))).retrieve("SPU5", 5))
                .extracting(DocumentChunk::getChunkId)
                .containsExactly("KB-0005#100");
    }
}
