package yumefusaka.envoymart.aiservice.tool;

import org.junit.jupiter.api.Test;
import yumefusaka.envoymart.agent.rag.Document;
import yumefusaka.envoymart.agent.rag.DocumentChunk;
import yumefusaka.envoymart.agent.rag.RAGEngine;
import yumefusaka.envoymart.agent.tool.ToolCall;
import yumefusaka.envoymart.agent.tool.ToolResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 知识库再检索工具的契约：<b>三种结局（命中 / 没查到 / 查不成）必须是三句不同的话。</b>
 * <p>
 * 与 {@code interaction_check} 同一个理由：把「没查到」说成「查不成」会让用户以为系统坏了，
 * 把「查不成」说成「没有相关内容」会让模型以为知识库表了态——这个场景里两者意思相反。
 */
class KnowledgeSearchToolTest {

    /** 记录入参、按脚本返回的桩 */
    private static class StubEngine implements RAGEngine {
        List<DocumentChunk> chunks = List.of();
        RuntimeException boom;
        String lastQuery;
        int lastTopK;

        @Override
        public void ingest(Document document) {
        }

        @Override
        public void ingestBatch(List<Document> documents) {
        }

        @Override
        public List<DocumentChunk> retrieve(String query, int topK) {
            lastQuery = query;
            lastTopK = topK;
            if (boom != null) {
                throw boom;
            }
            return chunks;
        }
    }

    private static ToolCall call(Map<String, Object> args) {
        return ToolCall.builder().toolName("knowledge_search").arguments(args).build();
    }

    private static DocumentChunk chunk() {
        return DocumentChunk.builder()
                .chunkId("c1").docId("vitamin-d")
                .title("维生素 D3 说明书")
                .position("《维生素 D3 说明书》 > 第二章 > 3.2")
                .content("成人每日推荐摄入量为 400IU，可耐受最高摄入量为 4000IU。")
                .source("manual").version("v2026.03")
                .build();
    }

    @Test
    void 命中时渲染出处行与引用要求() {
        StubEngine engine = new StubEngine();
        engine.chunks = List.of(chunk());

        ToolResult result = new KnowledgeSearchTool(engine)
                .execute(call(Map.of("query", "维生素D 每日上限")));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.isNoData()).isFalse();
        assertThat(result.getOutput())
                .as("出处行是引用校验采信标题的凭据（含「出处：」与《文档名》），缺了模型就没法给它标出处")
                .contains("出处：《维生素 D3 说明书》")
                .contains("4000IU")
                .contains("《文档名》")
                .contains("不要给这些片段标注 [编号] 角标");
    }

    @Test
    void 没查到是第三种结局而不是失败() {
        StubEngine engine = new StubEngine();

        ToolResult result = new KnowledgeSearchTool(engine)
                .execute(call(Map.of("query", "量子速读机 说明书")));

        assertThat(result.isSuccess())
                .as("查询没匹配上是「这件事问到了、答案是没收录」，不是系统故障")
                .isTrue();
        assertThat(result.isNoData()).isTrue();
        assertThat(result.getOutput()).contains("没有检索到").contains("不要用你自己的知识补全");
    }

    @Test
    void 检索抛异常时明确告诉模型这次没查成() {
        StubEngine engine = new StubEngine();
        engine.boom = new IllegalStateException("milvus timeout");

        ToolResult result = new KnowledgeSearchTool(engine)
                .execute(call(Map.of("query", "维生素D 每日上限")));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage())
                .as("失败文案必须堵住「顺着上文编一个结论」这条路")
                .contains("未能完成")
                .contains("不要据此说「知识库没有相关内容」");
    }

    @Test
    void 缺参或超长查询被拒() {
        KnowledgeSearchTool tool = new KnowledgeSearchTool(new StubEngine());

        assertThat(tool.execute(call(Map.of())).isSuccess()).isFalse();
        assertThat(tool.execute(call(Map.of("query", "  "))).getErrorMessage()).contains("缺少参数 query");
        assertThat(tool.execute(call(Map.of("query", "长".repeat(121)))).getErrorMessage())
                .contains("query 过长");
    }

    @Test
    void limit被钳制在合法区间并传给检索引擎() {
        assertThat(KnowledgeSearchTool.clampTopK(null)).isEqualTo(3);
        assertThat(KnowledgeSearchTool.clampTopK(10)).isEqualTo(5);
        assertThat(KnowledgeSearchTool.clampTopK(0)).isEqualTo(1);
        assertThat(KnowledgeSearchTool.clampTopK("2")).isEqualTo(2);
        assertThat(KnowledgeSearchTool.clampTopK("随便")).as("模型把 limit 写成一句话时退回默认值").isEqualTo(3);

        StubEngine engine = new StubEngine();
        engine.chunks = new ArrayList<>(List.of(chunk()));
        new KnowledgeSearchTool(engine).execute(call(Map.of("query", "维生素D", "limit", 99)));
        assertThat(engine.lastTopK).isEqualTo(5);
        assertThat(engine.lastQuery).isEqualTo("维生素D");
    }
}
