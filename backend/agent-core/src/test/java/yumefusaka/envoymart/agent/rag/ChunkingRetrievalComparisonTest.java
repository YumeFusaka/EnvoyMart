package yumefusaka.envoymart.agent.rag;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 切分策略 → 向量召回完整性 —— 用真实 embedding 度量"切分改动是否能被用户感知"。
 * <p>
 * <b>为什么只测向量路</b>：切分只作用于向量库，BM25 路目前是文档级的
 * （{@code HybridRetriever} 遍历 {@code List<Document>}），而且 RRF 按 docId 归一后
 * 同一份文档的多个切片只能争一个名额、保留的还是任意一片——经它一过，
 * 切分质量就被抹平了。要度量切分本身，必须直接看向量库召回了什么。
 * <p>
 * <b>判据</b>：召回的 top-K 切片里，是否存在一个<b>完整包含目标节文本</b>的切片。
 * 这个口径与"检索准不准"无关，只回答一个问题：<b>答案所在的那段内容，
 * 有没有以完整形态出现在候选里</b>。被切散的片段即使被召回，也答不出完整答案。
 * <p>
 * 需要 {@code RUN_RETRIEVAL_COMPARISON=true} 与 {@code DASHSCOPE_API_KEY}。
 */
@EnabledIfEnvironmentVariable(named = "RUN_RETRIEVAL_COMPARISON", matches = "true")
class ChunkingRetrievalComparisonTest {

    private static final String EMBEDDING_MODEL = "text-embedding-v4";

    /** 与线上 {@code Agent.Config.ragTopK(3)} 保持一致。 */
    private static final int TOP_K = 3;

    @Test
    void 切分策略对向量召回完整性的影响() {
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        List<Document> docs = LongDocFixtures.assemble();
        Map<String, String> sectionTexts = LongDocFixtures.sectionTexts();

        System.out.printf("%n========== 切分策略 → 向量召回完整性 ==========%n");
        System.out.printf("语料：%d 份长文档（由 90 篇短文聚合），查询：%d 条，topK=%d%n%n",
                docs.size(), RetrievalFixtures.allCases().size(), TOP_K);
        System.out.printf("%-24s %8s %10s %14s%n", "切分策略", "切片数", "平均片长", "完整召回率");
        System.out.println("-".repeat(62));

        Map<String, TextSplitter> strategies = new LinkedHashMap<>();
        strategies.put("结构分层 512/40 · 第1次", new StructuralSplitter(512, 40));
        strategies.put("结构分层 512/40 · 第2次", new StructuralSplitter(512, 40));
        strategies.put("结构分层 512/0 · 第1次", new StructuralSplitter(512, 0));
        strategies.put("结构分层 512/0 · 第2次", new StructuralSplitter(512, 0));

        for (Map.Entry<String, TextSplitter> entry : strategies.entrySet()) {
            Result result = measure(entry.getValue(), docs, sectionTexts, apiKey);
            System.out.printf("%-24s %8d %10.0f %13.1f%%   (%d/%d)%n",
                    entry.getKey(), result.chunkCount, result.avgLength,
                    100.0 * result.complete / Math.max(1, result.total),
                    result.complete, result.total);
        }
    }

    /**
     * 检索粒度对比 —— 索引固定为切片级，只改检索端。
     * <p>
     * 三种粒度共用同一份索引，差异全部来自"谁来当检索单元"：
     * <ul>
     *   <li>仅向量路：向量库直接返回切片，不走 BM25 也不融合；</li>
     *   <li>文档级混合（现状）：BM25 以整篇文档为单位，RRF 按 docId 归一——</li>
     *   <li>切片级混合（新）：BM25 以切片为单位，RRF 按 chunkId 归一。</li>
     * </ul>
     * 文档级在长文档上的代价是显性的：一份含十几条规则的文档，无论命中几条，
     * 在融合后都只占一个候选位。
     */
    @Test
    void 检索粒度对完整召回的影响() {
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        List<Document> docs = LongDocFixtures.assemble();
        Map<String, String> sectionTexts = LongDocFixtures.sectionTexts();
        TextSplitter splitter = new StructuralSplitter(512, 40);

        InMemoryVectorStore store = new InMemoryVectorStore(
                new DashScopeEmbeddingService(apiKey, EMBEDDING_MODEL));
        Retriever unused = new Retriever() {
            @Override
            public List<DocumentChunk> retrieve(String query, int topK) {
                return List.of();
            }
        };
        new SimpleRAGEngine(store, unused, splitter).ingestBatch(docs);
        List<DocumentChunk> chunks = docs.stream()
                .flatMap(d -> splitter.split(d).stream()).toList();

        System.out.printf("%n========== 检索粒度 → 完整召回 ==========%n");
        System.out.printf("切分固定为「结构分层 512/40」：%d 份长文档 → %d 个切片；查询 %d 条，topK=%d%n%n",
                docs.size(), chunks.size(), RetrievalFixtures.allCases().size(), TOP_K);

        report("① 仅向量路", (query, topK) -> store.search(query, topK), sectionTexts);
        report("② 文档级混合（现状）", new HybridRetriever(store, docs), sectionTexts);
        report("③ 切片级混合（新）", HybridRetriever.overChunks(store, chunks, Reranker.NOOP), sectionTexts);
    }

    private void report(String label, Retriever retriever, Map<String, String> sectionTexts) {
        int complete = 0;
        for (RetrievalEvaluator.EvalCase evalCase : RetrievalFixtures.allCases()) {
            List<DocumentChunk> hits = retriever.retrieve(evalCase.query(), TOP_K);
            boolean intact = hits.stream().anyMatch(chunk -> {
                String content = LongDocFixtures.normalize(chunk.getContent());
                return evalCase.relevantDocIds().stream().anyMatch(sectionId -> {
                    String sectionText = sectionTexts.get(sectionId);
                    return sectionText != null && content.contains(sectionText);
                });
            });
            if (intact) {
                complete++;
            }
        }
        int total = RetrievalFixtures.allCases().size();
        System.out.printf("%-26s %6.1f%%   (%d/%d)%n", label, 100.0 * complete / total, complete, total);
    }

    private record Result(int chunkCount, double avgLength, int complete, int total) {
    }

    private Result measure(TextSplitter splitter, List<Document> docs,
                           Map<String, String> sectionTexts, String apiKey) {
        InMemoryVectorStore store = new InMemoryVectorStore(
                new DashScopeEmbeddingService(apiKey, EMBEDDING_MODEL));
        Retriever unused = new Retriever() {
            @Override
            public List<DocumentChunk> retrieve(String query, int topK) {
                return List.of();
            }
        };
        new SimpleRAGEngine(store, unused, splitter).ingestBatch(docs);

        List<DocumentChunk> allChunks = docs.stream().flatMap(d -> splitter.split(d).stream()).toList();
        int chunkCount = allChunks.size();
        double avgLength = allChunks.stream()
                .mapToInt(c -> c.getContent().length()).average().orElse(0);
        int complete = 0;

        for (RetrievalEvaluator.EvalCase evalCase : RetrievalFixtures.allCases()) {
            List<DocumentChunk> hits = store.search(evalCase.query(), TOP_K);
            boolean intact = hits.stream().anyMatch(chunk -> {
                String content = LongDocFixtures.normalize(chunk.getContent());
                return evalCase.relevantDocIds().stream().anyMatch(sectionId -> {
                    String sectionText = sectionTexts.get(sectionId);
                    return sectionText != null && content.contains(sectionText);
                });
            });
            if (intact) {
                complete++;
            }
        }
        return new Result(chunkCount, avgLength, complete, RetrievalFixtures.allCases().size());
    }
}
