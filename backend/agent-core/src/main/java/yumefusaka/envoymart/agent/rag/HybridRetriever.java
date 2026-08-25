package yumefusaka.envoymart.agent.rag;

import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * 混合检索器 —— BM25 关键词 + ANN 向量语义的 fusion。
 * <p>
 * 使用互惠排名融合（RRF）合并两路结果，k = 60。
 * 两路结果按 docId 对齐，保证同一篇文档的两路排名能真正相加。
 */
@Slf4j
public class HybridRetriever implements Retriever {

    private final VectorStore vectorStore;
    private final List<Document> localDocs;

    /** BM25 参数 */
    private static final double K1 = 1.5;
    private static final double B = 0.75;
    private static final int RRF_CONST = 60;

    public HybridRetriever(VectorStore vectorStore, List<Document> localDocs) {
        this.vectorStore = vectorStore;
        this.localDocs = localDocs;
    }

    @Override
    public List<DocumentChunk> retrieve(String query, int topK) {
        // 1. 向量检索（由 VectorStore 负责向量化）
        List<DocumentChunk> vectorResults = vectorStore.search(query, topK * 2);

        // 2. BM25 关键词检索
        List<DocumentChunk> keywordResults = bm25Search(query);

        // 3. RRF 融合
        return rrfMerge(vectorResults, keywordResults, topK);
    }

    /**
     * BM25 关键词检索：对 query 分词后逐篇文档计算 BM25 得分。
     * 文档长度按词元数计，因此中文（bigram）与英文（按词）可以混用同一套归一化。
     */
    private List<DocumentChunk> bm25Search(String query) {
        List<String> queryTerms = TextTokenizer.tokenize(query);
        if (queryTerms.isEmpty() || localDocs.isEmpty()) {
            return List.of();
        }

        // 预计算每篇文档的词频与词元长度
        List<Map<String, Integer>> docTermFreqs = new ArrayList<>(localDocs.size());
        double[] docLens = new double[localDocs.size()];
        double totalLen = 0;
        for (int i = 0; i < localDocs.size(); i++) {
            Map<String, Integer> termFreq = new HashMap<>();
            for (String token : TextTokenizer.tokenize(indexText(localDocs.get(i)))) {
                termFreq.merge(token, 1, Integer::sum);
            }
            docTermFreqs.add(termFreq);
            docLens[i] = termFreq.values().stream().mapToInt(Integer::intValue).sum();
            totalLen += docLens[i];
        }
        double avgDocLen = totalLen > 0 ? totalLen / localDocs.size() : 1.0;

        // 文档频率：包含该词元的文档数
        Map<String, Integer> docFreq = new HashMap<>();
        for (String term : queryTerms) {
            int df = 0;
            for (Map<String, Integer> termFreq : docTermFreqs) {
                if (termFreq.containsKey(term)) {
                    df++;
                }
            }
            docFreq.put(term, df);
        }

        List<ScoredDoc> scored = new ArrayList<>();
        for (int i = 0; i < localDocs.size(); i++) {
            Map<String, Integer> termFreq = docTermFreqs.get(i);
            double score = 0;
            for (String term : queryTerms) {
                int tf = termFreq.getOrDefault(term, 0);
                if (tf == 0) {
                    continue;
                }
                int df = docFreq.get(term);
                double idf = Math.log((localDocs.size() - df + 0.5) / (df + 0.5) + 1.0);
                score += idf * (tf * (K1 + 1)) / (tf + K1 * (1 - B + B * docLens[i] / avgDocLen));
            }
            if (score > 0) {
                scored.add(new ScoredDoc(localDocs.get(i), score));
            }
        }

        scored.sort((a, b) -> Double.compare(b.score(), a.score()));
        return scored.stream()
                .map(sd -> DocumentChunk.builder()
                        .chunkId(sd.doc().getId())
                        .docId(sd.doc().getId())
                        .content(sd.doc().getContent())
                        .build())
                .toList();
    }

    /** 索引文本 = 标题 + 正文 + 标签，让标题和标签也能参与关键词匹配。 */
    private String indexText(Document doc) {
        StringBuilder sb = new StringBuilder();
        if (doc.getTitle() != null) {
            sb.append(doc.getTitle()).append(' ');
        }
        if (doc.getContent() != null) {
            sb.append(doc.getContent()).append(' ');
        }
        if (doc.getTags() != null) {
            sb.append(String.join(" ", doc.getTags()));
        }
        return sb.toString();
    }

    /**
     * 互惠排名融合。两路结果按 docId 对齐——向量路返回的是切片，
     * 关键词路返回的是文档，只有归一到 docId 才能让同一篇文档的排名真正累加。
     */
    private List<DocumentChunk> rrfMerge(List<DocumentChunk> vector, List<DocumentChunk> keyword, int topK) {
        Map<String, Double> scores = new HashMap<>();
        Map<String, DocumentChunk> byDocId = new LinkedHashMap<>();

        for (int i = 0; i < vector.size(); i++) {
            DocumentChunk chunk = vector.get(i);
            scores.merge(chunk.getDocId(), 1.0 / (RRF_CONST + i), Double::sum);
            byDocId.putIfAbsent(chunk.getDocId(), chunk);
        }
        for (int i = 0; i < keyword.size(); i++) {
            DocumentChunk chunk = keyword.get(i);
            scores.merge(chunk.getDocId(), 1.0 / (RRF_CONST + i), Double::sum);
            byDocId.putIfAbsent(chunk.getDocId(), chunk);
        }

        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(e -> byDocId.get(e.getKey()))
                .toList();
    }

    private record ScoredDoc(Document doc, double score) {
    }
}
