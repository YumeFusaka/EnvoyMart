package yumefusaka.envoymart.agent.rag;

import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 混合检索器 —— BM25 关键词 + ANN 向量语义的 fusion。
 * <p>
 * 使用互惠排名融合（RRF）合并两路结果，k = 60。
 */
@Slf4j
public class HybridRetriever implements Retriever {

    private final VectorStore vectorStore;
    private final EmbeddingService embeddingService;
    private final List<Document> localDocs;

    /** BM25 参数 */
    private static final double K1 = 1.5;
    private static final double B = 0.75;
    private static final int RRF_CONST = 60;

    public HybridRetriever(VectorStore vectorStore, EmbeddingService embeddingService, List<Document> localDocs) {
        this.vectorStore = vectorStore;
        this.embeddingService = embeddingService;
        this.localDocs = localDocs;
    }

    @Override
    public List<DocumentChunk> retrieve(String query, int topK) {
        // 1. ANN 向量检索
        float[] queryVec = embeddingService.embed(query);
        List<DocumentChunk> vectorResults = vectorStore.search(queryVec, topK * 2);

        // 2. BM25 关键词检索
        List<DocumentChunk> keywordResults = bm25Search(query);

        // 3. RRF 融合
        return rrfMerge(vectorResults, keywordResults, topK);
    }

    /**
     * BM25 关键词检索：将 query 分词后对每个文档计算 BM25 得分。
     */
    private List<DocumentChunk> bm25Search(String query) {
        String[] terms = query.toLowerCase().split("\\s+");
        if (terms.length == 0) return List.of();

        // 统计每个 term 的文档频率
        Map<String, Integer> docFreq = new HashMap<>();
        Map<String, Double> termFreqInDoc = new LinkedHashMap<>();
        double avgDocLen = localDocs.stream()
                .mapToInt(d -> d.getContent().length())
                .average().orElse(1.0);

        for (String term : terms) {
            long count = localDocs.stream()
                    .filter(d -> d.getContent().toLowerCase().contains(term)
                            || d.getTitle().toLowerCase().contains(term)
                            || d.getTags().stream().anyMatch(t -> t.toLowerCase().contains(term)))
                    .count();
            docFreq.put(term, (int) count);
        }

        // 计算每个文档的 BM25 得分
        List<ScoredDoc> scored = new ArrayList<>();
        for (Document doc : localDocs) {
            double score = 0;
            for (String term : terms) {
                if (!docFreq.containsKey(term) || docFreq.get(term) == 0) continue;
                double tf = countTerm(term, doc);
                int df = docFreq.get(term);
                double idf = Math.log((localDocs.size() - df + 0.5) / (df + 0.5) + 1.0);
                double docLen = doc.getContent().length();
                score += idf * (tf * (K1 + 1)) / (tf + K1 * (1 - B + B * docLen / avgDocLen));
            }
            if (score > 0) {
                scored.add(new ScoredDoc(doc, score));
            }
        }

        scored.sort((a, b) -> Double.compare(b.score, a.score));
        return scored.stream()
                .map(sd -> DocumentChunk.builder()
                        .chunkId("bm25_" + sd.doc.getId())
                        .docId(sd.doc.getId())
                        .content(sd.doc.getContent())
                        .build())
                .collect(Collectors.toList());
    }

    private double countTerm(String term, Document doc) {
        double count = 0;
        String content = doc.getContent().toLowerCase();
        int idx = 0;
        while ((idx = content.indexOf(term, idx)) != -1) {
            count++;
            idx += term.length();
        }
        return count;
    }

    /** 互惠排名融合。 */
    private List<DocumentChunk> rrfMerge(List<DocumentChunk> vector, List<DocumentChunk> keyword, int topK) {
        Map<String, Double> scores = new HashMap<>();
        Map<String, DocumentChunk> map = new HashMap<>();

        for (int i = 0; i < vector.size(); i++) {
            String id = vector.get(i).getChunkId();
            scores.merge(id, 1.0 / (RRF_CONST + i), Double::sum);
            map.put(id, vector.get(i));
        }
        for (int i = 0; i < keyword.size(); i++) {
            String id = keyword.get(i).getChunkId();
            scores.merge(id, 1.0 / (RRF_CONST + i), Double::sum);
            map.put(id, keyword.get(i));
        }

        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(e -> map.get(e.getKey()))
                .collect(Collectors.toList());
    }

    private record ScoredDoc(Document doc, double score) {}
}

