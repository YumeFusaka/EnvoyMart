package yumefusaka.envoymart.agent.rag;

import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * 混合检索器 —— BM25 关键词 + ANN 向量语义 + 图谱依据的 fusion。
 * <p>
 * 使用互惠排名融合（RRF）合并各路结果，k = 60。
 * <p>
 * <b>三路各自补的是别人的盲区</b>：BM25 认词面，向量认语义，图谱认<b>关系</b>。
 * 前两路的前提都是「问题里出现了与文档相同的、或语义相近的表述」；
 * 只要用户问的是「SPU5 和华法林冲突吗」这种<b>实体编号</b>，
 * 而文档里从头到尾只有「深海鱼油」，两条路就都没有可匹配的东西——
 * 那一句风险提示在索引里躺着，但谁也够不着它。图谱知道这两个名字指的是什么，
 * 于是这条路能把那片依据拖进候选池。第三路是可选的
 * （见 {@link #overChunks(VectorStore, List, Reranker, Retriever)}）。
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
    /**
     * 第三路：图谱依据。可为 null（没接图谱时就是两路，行为与历史完全一致）。
     * <p>
     * 它是一个 {@link Retriever} 而不是一个「图谱客户端」：这一层是检索算法的所在地，
     * 不该知道图谱长什么样、走 HTTP 还是走本地。转换（边 → 切片）在 ai-service 侧的
     * 适配器里做，这一层拿到的就是切片。
     */
    private final Retriever graphRetriever;

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
        this(vectorStore, asChunks(localDocs), reranker, true, null);
    }

    private HybridRetriever(VectorStore vectorStore, List<DocumentChunk> chunks,
                            Reranker reranker, boolean groupByDocId, Retriever graphRetriever) {
        this.vectorStore = vectorStore;
        this.localChunks = chunks;
        this.reranker = reranker;
        this.groupByDocId = groupByDocId;
        this.graphRetriever = graphRetriever;
    }

    /**
     * 切片级检索 —— BM25 与向量路在同一粒度上融合。
     * <p>
     * 用命名工厂而非重载构造：{@code List<Document>} 与 {@code List<DocumentChunk>}
     * 擦除后同型，两个构造函数无法共存。
     */
    public static HybridRetriever overChunks(VectorStore vectorStore, List<DocumentChunk> chunks,
                                             Reranker reranker) {
        return new HybridRetriever(vectorStore, chunks, reranker, false, null);
    }

    /**
     * 切片级检索 + 图谱第三路。
     * <p>
     * <b>它补的是词面与语义都够不着的那些问题。</b>语料里写着「深海鱼油与华法林合用可能增加
     * 出血风险」，用户问「SPU5 和华法林冲突吗」——{@code SPU5} 在任何一篇文档里都不出现，
     * BM25 没有词可匹配，向量空间里也没有任何文本与这三个字符相近。图谱知道
     * {@code spu5 → 深海鱼油}，于是那条风险边还能被捞回来。
     * <p>
     * 三路结果<b>平等地</b>进 RRF：图谱路的第一名得 {@code 1/60}，与向量路、关键词路的
     * 第一名同分。不给图谱路加权重，是因为「名次」在三路之间已经是同一种量纲，
     * 再乘一个手调的系数就成了没有依据的调参；图谱路的精度体现在<b>它召回的条目少而准</b>，
     * 不体现在它该拿更高的名次分。
     */
    public static HybridRetriever overChunks(VectorStore vectorStore, List<DocumentChunk> chunks,
                                             Reranker reranker, Retriever graphRetriever) {
        return new HybridRetriever(vectorStore, chunks, reranker, false, graphRetriever);
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

        // 3. 图谱依据（没接图谱时为空的第三路）
        List<DocumentChunk> graphResults = graphRetrieve(query, topK);

        // 4. RRF 融合后多留候选，交给重排精排
        List<DocumentChunk> fused = rrfMerge(vectorResults, keywordResults, graphResults,
                Math.max(topK * RERANK_CANDIDATES, topK));

        // 5. 重排（未配置时是直接截断）
        return reranker.rerank(query, fused, topK);
    }

    /**
     * 图谱路召回。<b>失败一律降级为空列表</b>——这是增强路，图谱挂了就该退化成
     * 纯文本检索的两路结果，而不是整个回答失败。
     * <p>
     * <b>为什么要在这里兜底而不是只靠适配器内部</b>：这一层是检索主流程，
     * 任何一路抛出的异常都会顺着 {@code retrieve} 冒到回答侧，表现成用户看不到回答。
     * 一个「可选增强」的失败代价不该是主功能不可用。适配器里也有一层兜底（它要
     * 记更具体的日志），两层不冲突：外面这层防的是「适配器没料到的异常类型」。
     * <p>
     * 降级记 warn 而不是静默：图谱长期不可用而没人发现的话，表现就是
     * 「这个功能好像没什么用」，与「它本来就没用」在指标上完全一样。
     */
    private List<DocumentChunk> graphRetrieve(String query, int topK) {
        if (graphRetriever == null) {
            return List.of();
        }
        try {
            List<DocumentChunk> chunks = graphRetriever.retrieve(query, topK);
            return chunks == null ? List.of() : chunks;
        } catch (RuntimeException e) {
            log.warn("[Retriever] 图谱路召回失败，本次退化为文本两路：{}", e.toString());
            return List.of();
        }
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
     * 互惠排名融合。各路结果的归一 key 由 {@link #groupByDocId} 决定——
     * 文档级各路都能归一到同一把尺子上，切片级则按切片各自计分。
     * <p>
     * <b>图谱路与文本两路走同一把尺子，靠的是「切片身份」这件事被对齐过</b>：
     * 图谱边自带 docId 与 chunkId，适配器据此拼出的切片，与知识库切出来的那一片
     * 是<b>同一个 key</b>。于是同一片依据被两路同时召回时，RRF 会把它累加
     * （{@code 1/60 + 1/62}）——这正是 RRF 想要的：两路独立认为它相关，
     * 比只有一路认为它相关更可信。如果适配器给图谱切片另起一套 id，
     * 这一路就变成了「往候选池里塞重复项」，融合的语义就没了。
     */
    private List<DocumentChunk> rrfMerge(List<DocumentChunk> vector, List<DocumentChunk> keyword,
                                         List<DocumentChunk> graph, int topK) {
        Map<String, Double> scores = new HashMap<>();
        Map<String, DocumentChunk> byKey = new LinkedHashMap<>();

        mergeOnce(scores, byKey, vector);
        mergeOnce(scores, byKey, keyword);
        mergeOnce(scores, byKey, graph);

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
     * <p>
     * {@code byKey.putIfAbsent} 保持<b>先到者胜</b>，而调用顺序是 向量 → 关键词 → 图谱。
     * 于是图谱切片只在文本两路都没捞到这一片时才会被采用：这是图谱路唯一有价值的场景，
     * 也正是它该赢的场景。反过来，文本路捞到了就用原文切片——图谱那版正文是
     * 「图谱推导：… + 原文」的转述，作为给模型的证据不如原文本身。
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
