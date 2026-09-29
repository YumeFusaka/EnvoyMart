package yumefusaka.envoymart.agent.rag;

import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * 混合检索器 —— BM25 关键词 + ANN 向量语义的 fusion。
 * <p>
 * 使用互惠排名融合（RRF）合并两路结果，k = 60。
 * <p>
 * <b>两种粒度，两个入口</b>：
 * <ul>
 *   <li><b>文档级</b>（{@link #HybridRetriever(VectorStore, List, Reranker)}）——
 *       每篇文档当作一个检索单元，融合时按 <b>docId</b> 归一：同一篇文档在每个列表里
 *       只按最佳排名计入一次，避免长文档仅因为切片多而被系统性抬权
 *       （回归防线见 {@code RrfMultiChunkTest}）；</li>
 *   <li><b>切片级</b>（{@link #overChunks}）—— 直接在切片粒度上融合，按 <b>chunkId</b> 归一。
 *       长文档的多个切片可以各自占据候选位——一份含十几条规则的文档，
 *       不该只争到一个名额。</li>
 * </ul>
 * 两者的差别只在归一 key。文档级入口把每篇文档包成一个「切片」（chunkId = docId），
 * 因此行为与历史完全一致。
 */
@Slf4j
public class HybridRetriever implements Retriever {

    private final VectorStore vectorStore;
    /**
     * 参与 BM25 的检索单元；文档级入口会把每篇文档包成一个切片。
     * <p>
     * {@code volatile} 且非 final：语料来自知识库，可以在运行时被整份替换
     * （见 {@link #rebuild}）。读它的是每个检索请求，写它的是一次管理动作，
     * 不保证可见性的话会有一部分线程继续拿着旧语料打分——表现为「重建了，
     * 但一部分查询搜到的还是旧内容」，且不可复现。
     */
    private volatile List<DocumentChunk> localChunks;
    private final Reranker reranker;
    /** true：按 docId 归一（防抬权）；false：按 chunkId 归一（切片级召回）。 */
    private final boolean groupByDocId;

    /** BM25 参数 */
    private static final double K1 = 1.5;
    private static final double B = 0.75;
    private static final int RRF_CONST = 60;
    /** 重排前多召回一些候选，给精排留出腾挪空间 */
    private static final int RERANK_CANDIDATES = 3;

    public HybridRetriever(VectorStore vectorStore, List<Document> localDocs) {
        this(vectorStore, localDocs, Reranker.NOOP);
    }

    public HybridRetriever(VectorStore vectorStore, List<Document> localDocs, Reranker reranker) {
        this(vectorStore, asChunks(localDocs), reranker, true);
    }

    private HybridRetriever(VectorStore vectorStore, List<DocumentChunk> chunks,
                            Reranker reranker, boolean groupByDocId) {
        this.vectorStore = vectorStore;
        this.localChunks = chunks;
        this.reranker = reranker;
        this.groupByDocId = groupByDocId;
    }

    /**
     * 切片级检索 —— BM25 与向量路在同一粒度上融合。
     * <p>
     * 用命名工厂而非重载构造：{@code List<Document>} 与 {@code List<DocumentChunk>}
     * 擦除后同型，两个构造函数无法共存。
     */
    public static HybridRetriever overChunks(VectorStore vectorStore, List<DocumentChunk> chunks,
                                             Reranker reranker) {
        return new HybridRetriever(vectorStore, chunks, reranker, false);
    }

    /**
     * 整份替换 BM25 侧的语料。
     * <p>
     * <b>为什么是「整份替换」而不是增删单篇</b>：知识库改一篇文档时，切分结果会整组变
     * （片数、边界、编号都跟着变），逐篇对齐要处理这三种变化，而它们本来就没有对应关系。
     * 十几篇文档重建一次 BM25 索引是毫秒级的事，换来的是一个不可能出现"半新半旧"的状态。
     * <p>
     * 索引是<b>派生数据</b>，随时可以从知识库重建——这正是把它做成一个动作而不是
     * 一份持久状态的理由。
     */
    public void rebuild(List<DocumentChunk> chunks) {
        this.localChunks = List.copyOf(chunks);
        log.info("[Retriever] BM25 语料已重建，切片 {} 片", this.localChunks.size());
    }

    /**
     * 把文档包成「每篇一个切片」。标题与标签并入 BM25 索引文本——
     * 它们是用户会说的词，但不应出现在返回给模型的正文里。
     */
    private static List<DocumentChunk> asChunks(List<Document> docs) {
        return docs.stream().map(doc -> {
            StringBuilder indexText = new StringBuilder();
            if (doc.getTitle() != null) {
                indexText.append(doc.getTitle()).append(' ');
            }
            if (doc.getContent() != null) {
                indexText.append(doc.getContent()).append(' ');
            }
            if (doc.getTags() != null) {
                indexText.append(String.join(" ", doc.getTags()));
            }
            return DocumentChunk.builder()
                    .chunkId(doc.getId())
                    .docId(doc.getId())
                    .content(doc.getContent())
                    .indexText(indexText.toString())
                    .build();
        }).toList();
    }

    @Override
    public List<DocumentChunk> retrieve(String query, int topK) {
        // 1. 向量检索（由 VectorStore 负责向量化）
        List<DocumentChunk> vectorResults = vectorStore.search(query, topK * 2);

        // 2. BM25 关键词检索
        List<DocumentChunk> keywordResults = bm25Search(query);

        // 3. RRF 融合后多留候选，交给重排精排
        List<DocumentChunk> fused = rrfMerge(vectorResults, keywordResults,
                Math.max(topK * RERANK_CANDIDATES, topK));

        // 4. 重排（未配置时是直接截断）
        return reranker.rerank(query, fused, topK);
    }

    /**
     * BM25 关键词检索：对 query 分词后逐个检索单元计算 BM25 得分。
     * 长度按词元数计，因此中文（bigram）与英文（按词）可以混用同一套归一化。
     */
    private List<DocumentChunk> bm25Search(String query) {
        List<String> queryTerms = TextTokenizer.tokenize(query);
        if (queryTerms.isEmpty() || localChunks.isEmpty()) {
            return List.of();
        }

        // 预计算每个检索单元的词频与词元长度
        int n = localChunks.size();
        List<Map<String, Integer>> unitTermFreqs = new ArrayList<>(n);
        double[] unitLens = new double[n];
        double totalLen = 0;
        for (int i = 0; i < n; i++) {
            Map<String, Integer> termFreq = new HashMap<>();
            for (String token : TextTokenizer.tokenize(indexTextOf(localChunks.get(i)))) {
                termFreq.merge(token, 1, Integer::sum);
            }
            unitTermFreqs.add(termFreq);
            unitLens[i] = termFreq.values().stream().mapToInt(Integer::intValue).sum();
            totalLen += unitLens[i];
        }
        double avgLen = totalLen > 0 ? totalLen / n : 1.0;

        // 文档频率：包含该词元的检索单元数
        Map<String, Integer> unitFreq = new HashMap<>();
        for (String term : queryTerms) {
            int df = 0;
            for (Map<String, Integer> termFreq : unitTermFreqs) {
                if (termFreq.containsKey(term)) {
                    df++;
                }
            }
            unitFreq.put(term, df);
        }

        List<ScoredChunk> scored = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Map<String, Integer> termFreq = unitTermFreqs.get(i);
            double score = 0;
            for (String term : queryTerms) {
                int tf = termFreq.getOrDefault(term, 0);
                if (tf == 0) {
                    continue;
                }
                int df = unitFreq.get(term);
                double idf = Math.log((n - df + 0.5) / (df + 0.5) + 1.0);
                score += idf * (tf * (K1 + 1)) / (tf + K1 * (1 - B + B * unitLens[i] / avgLen));
            }
            if (score > 0) {
                scored.add(new ScoredChunk(localChunks.get(i), score));
            }
        }

        scored.sort((a, b) -> Double.compare(b.score(), a.score()));
        return scored.stream().map(ScoredChunk::chunk).toList();
    }

    /** BM25 索引文本：优先用显式指定的（可含标题与标签），否则退化为切片内容。 */
    private String indexTextOf(DocumentChunk chunk) {
        return chunk.getIndexText() != null && !chunk.getIndexText().isBlank()
                ? chunk.getIndexText()
                : chunk.getContent();
    }

    /**
     * 互惠排名融合。两路的归一 key 由 {@link #groupByDocId} 决定——
     * 文档级两路都能归一到同一把尺子上，切片级则按切片各自计分。
     */
    private List<DocumentChunk> rrfMerge(List<DocumentChunk> vector, List<DocumentChunk> keyword, int topK) {
        Map<String, Double> scores = new HashMap<>();
        Map<String, DocumentChunk> byKey = new LinkedHashMap<>();

        mergeOnce(scores, byKey, vector);
        mergeOnce(scores, byKey, keyword);

        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                // RRF 分<b>只用来定序，不透传</b>。它的量纲是 1/(60+rank)：第 1 名 1/60、
                // 第 5 名 1/64，绝对大小不表达「有多相关」，拿它当阈值等于拿名次当置信度。
                // 下游需要的相关性信号在别处——向量路由 VectorStore 写在 chunk.score 上，
                // 重排器会再覆盖一次。这里原样带出去。
                .map(e -> copyOf(byKey.get(e.getKey())))
                .toList();
    }

    /**
     * 复制一份切片。
     * <p>
     * 不就地改、也不直接返回原实例：{@code InMemoryVectorStore} 的 {@code search}
     * 返回的是<b>存储里的那个对象</b>，下游（重排器、拒答门）一旦往 slice 上写分数，
     * 写的就是索引本身——下一次查询会读到上一次的分数。
     */
    private DocumentChunk copyOf(DocumentChunk chunk) {
        return chunk == null ? null : chunk.toBuilder().build();
    }

    /**
     * 单个列表内的合并。
     * <p>
     * <b>文档级（groupByDocId=true）</b>：每篇文档在每个列表里只按最佳排名计入一次。
     * 直接对每次出现都累加 {@code 1/(k+rank)} 会让长文档被系统性抬权：一篇被切成 5 片、
     * 占满向量路前 5 名的文档，得分是任何单次出现的 5 倍左右，足以压过真正更相关但只有
     * 一片的短文档。<b>文档长度不该是相关性的代理。</b>
     * <p>
     * <b>切片级（groupByDocId=false）</b>：按 chunkId 归一，长文档的多个相关切片各自
     * 占据候选位。这里不再限制同文档的出现次数——切片级检索的目的正是让"文档里
     * 那一条真正相关的规则"能独立地被选中，而不是被整篇文档的代表挤掉。
     */
    private void mergeOnce(Map<String, Double> scores, Map<String, DocumentChunk> byKey,
                           List<DocumentChunk> list) {
        Set<String> counted = new HashSet<>();
        for (int i = 0; i < list.size(); i++) {
            DocumentChunk chunk = list.get(i);
            String key = groupByDocId ? chunk.getDocId() : chunk.getChunkId();
            byKey.putIfAbsent(key, chunk);
            if (counted.add(key)) {
                // rank 从 0 计，故第 1 名得 1/RRF_CONST（标准 RRF 的下标约定）
                scores.merge(key, 1.0 / (RRF_CONST + i), Double::sum);
            }
        }
    }

    private record ScoredChunk(DocumentChunk chunk, double score) {
    }
}
