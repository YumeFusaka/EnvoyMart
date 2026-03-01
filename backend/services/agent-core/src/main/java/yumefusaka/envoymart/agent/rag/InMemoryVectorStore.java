package yumefusaka.envoymart.agent.rag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存向量存储 —— 暴力余弦相似度扫描。
 * ponytail: O(n) 每次检索，仅演示。生产环境用 Elasticsearch / pgvector。
 */
public class InMemoryVectorStore implements VectorStore {

    private final Map<String, DocumentChunk> store = new ConcurrentHashMap<>();

    @Override
    public void index(DocumentChunk chunk) {
        store.put(chunk.getChunkId(), chunk);
    }

    @Override
    public void indexBatch(List<DocumentChunk> chunks) {
        chunks.forEach(this::index);
    }

    @Override
    public List<DocumentChunk> search(float[] queryVector, int topK) {
        List<DocumentChunk> results = new ArrayList<>(store.values());
        results.sort(Comparator.comparingDouble(
                c -> -cosineSimilarity(queryVector, c.getEmbedding())));
        return results.subList(0, Math.min(topK, results.size()));
    }

    @Override
    public void delete(String docId) {
        store.entrySet().removeIf(e -> e.getValue().getDocId().equals(docId));
    }

    private double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return 0;
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB) + 1e-10);
    }
}
