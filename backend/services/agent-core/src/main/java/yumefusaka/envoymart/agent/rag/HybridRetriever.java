package yumefusaka.envoymart.agent.rag;

import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 混合检索器 —— 关键词 + 向量语义的 fusion。
 * <p>
 * 使用简单的互惠排名融合（RRF）合并两路结果。
 * ponytail: 暴力关键词匹配 + 内存余弦暴力扫描，小规模演示足够。
 * 生产环境换成 Elasticsearch 的 bool/multi_match + dense_vector 查询。
 */
@Slf4j
public class HybridRetriever implements Retriever {

    private final VectorStore vectorStore;
    private final EmbeddingService embeddingService;
    private final List<Document> localDocs;   // 用于关键词匹配

    public HybridRetriever(VectorStore vectorStore, EmbeddingService embeddingService, List<Document> localDocs) {
        this.vectorStore = vectorStore;
        this.embeddingService = embeddingService;
        this.localDocs = localDocs;
    }

    @Override
    public List<DocumentChunk> retrieve(String query, int topK) {
        // 1. 向量检索
        float[] queryVec = embeddingService.embed(query);
        List<DocumentChunk> vectorResults = vectorStore.search(queryVec, topK);

        // 2. 关键词检索（本地文档）
        List<DocumentChunk> keywordResults = keywordSearch(query);

        // 3. RRF 融合
        return rrfMerge(vectorResults, keywordResults, topK);
    }

    private List<DocumentChunk> keywordSearch(String query) {
        String lower = query.toLowerCase();
        return localDocs.stream()
                .filter(d -> d.getTitle().toLowerCase().contains(lower)
                        || d.getContent().toLowerCase().contains(lower)
                        || d.getTags().stream().anyMatch(t -> t.toLowerCase().contains(lower)))
                .map(d -> DocumentChunk.builder()
                        .chunkId("kw_" + d.getId())
                        .docId(d.getId())
                        .content(d.getContent())
                        .chunkIndex(0)
                        .build())
                .collect(Collectors.toList());
    }

    /** 互惠排名融合。 */
    private List<DocumentChunk> rrfMerge(List<DocumentChunk> vector, List<DocumentChunk> keyword, int topK) {
        Map<String, Double> scores = new HashMap<>();
        Map<String, DocumentChunk> map = new HashMap<>();

        int k = 60; // RRF 常数
        for (int i = 0; i < vector.size(); i++) {
            String id = vector.get(i).getChunkId();
            scores.merge(id, 1.0 / (k + i), Double::sum);
            map.put(id, vector.get(i));
        }
        for (int i = 0; i < keyword.size(); i++) {
            String id = keyword.get(i).getChunkId();
            scores.merge(id, 1.0 / (k + i), Double::sum);
            map.put(id, keyword.get(i));
        }

        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(e -> map.get(e.getKey()))
                .collect(Collectors.toList());
    }
}
