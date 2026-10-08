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
     * 参与 BM25 的检索单元与它们的统计量；文档级入口会把每篇文档包成一个切片。
     * <p>
     * {@code volatile} 且非 final：语料来自知识库，可以在运行时被整份替换
     * （见 {@link #rebuild}）。读它的是每个检索请求，写它的是一次管理动作，
     * 不保证可见性的话会有一部分线程继续拿着旧语料打分——表现为「重建了，
     * 但一部分查询搜到的还是旧内容」，且不可复现。
     */
    private volatile Bm25Index bm25Index;
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
    /**
     * 重排前多召回一些候选，给精排留出腾挪空间。
     * <p>
     * <b>实测过 3 / 5 / 8 三档（2026-10-04，真实向量+重排+扩写）：语义档 0.700 → 0.675 → 0.675，
     * 口语档 0.975 → 1.000 → 0.950，全量 0.900 → 0.883 → 0.883。扩大候选池没有增益，
     * 因此维持 3。</b>候选池不是这批失败样本的瓶颈——瓶颈在「意图→文档」那类
     * 字面与语义都不重合的映射上（见 RetrievalQualityTest 的档位说明），
     * 那种查询无论给重排器多少候选都排不出来。多给候选只是把更多噪声送进 cross-encoder。
     */
    private static final int RERANK_CANDIDATES = 3;
    /**
     * 中文单字通道在融合时的权重。
     * <p>
     * 单字是<b>补充通道</b>，不是主通道：它对「钙片 ↔ 碳酸钙 D3 咀嚼片」这类跨词界查询
     * 是唯一能命中的路，但单字没有词序、噪声远高于二元组——「二」「次」「重」「复」
     * 这类高频字会在无关文档上凑出分。定成 0.35 是让它在「二元组一颗子都没有」时
     * 足够把候选托进池子，又不至于在二元组已经有强命中时改写排序。
     * <p>
     * 这个值影响的是排序权重而不是召回门槛，所以调的余地很小；
     * 真要动它，必须重跑 {@code RetrievalQualityTest} 三档并确认字面档不掉。
     */
    private static final double UNIGRAM_WEIGHT = 0.35;

    public HybridRetriever(VectorStore vectorStore, List<Document> localDocs) {
        this(vectorStore, localDocs, Reranker.NOOP);
    }

    public HybridRetriever(VectorStore vectorStore, List<Document> localDocs, Reranker reranker) {
        this(vectorStore, asChunks(localDocs), reranker, true, null);
    }

    private HybridRetriever(VectorStore vectorStore, List<DocumentChunk> chunks,
                            Reranker reranker, boolean groupByDocId, Retriever graphRetriever) {
        this.vectorStore = vectorStore;
        this.bm25Index = Bm25Index.of(chunks);
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
        // 先算统计再发布：读线程一旦看见新索引，它内部的一致性就已经成立。
        // 反过来（先换语料再算统计）会有一个窗口，查询拿着新语料、对着旧统计打分
        Bm25Index rebuilt = Bm25Index.of(chunks);
        this.bm25Index = rebuilt;
        log.info("[Retriever] BM25 语料已重建，切片 {} 片", rebuilt.size());
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
        return retrieve(query, QueryExpansions.none(), topK);
    }

    /**
     * 带扩写的检索 —— 把一批变体和原句一起放进同一个候选池。
     * <p>
     * <b>每一路只吃它擅长的那种变体，这是这个方法存在的全部理由：</b>
     * 假想答案（HyDE）补的是<b>词汇鸿沟</b>，只有语义路认它；角度改写补的是<b>表述差异</b>，
     * 只有词法路认它。把两者无差别地喂给所有路，是拿一段编出来的长文本去稀释 BM25 的
     * 词元（idf 会把那些词也算成信号），同时拿一句太短的改写去占向量路的位置。
     * <p>
     * <b>重排只做一次，且用原句。</b>每条路各自重排再合并，等于让每个变体各自做一次
     * 「不重要的候选就丢掉」的决策——而那个决策恰恰要靠融合之后才有依据。重排的查询
     * 也必须是用户真正问的那句：变体是检索的手段，不是目的。
     * <p>
     * {@code expansions} 为空时，本方法与改造前的行为<b>逐位相同</b>：路数、顺序、
     * 融合 key、重排输入一个都没变。
     */
    public List<DocumentChunk> retrieve(String query, QueryExpansions expansions, int topK) {
        return retrieveWithOutcome(query, expansions, topK).chunks();
    }

    /**
     * 带请求级事实的检索 —— 与 {@link #retrieve(String, QueryExpansions, int)} 同一条路径，
     * 只是把「本轮图谱路命中过哪几片」一起带出去。
     * <p>
     * <b>为什么这件事不能从返回的切片里推：</b>重排会按 topK 截断，图谱切片字面分低，
     * 排在 topK 之外就被丢了；下游拿着截断后的列表，既看不到它也推不出「它来过」。
     * 而拒答门的图谱豁免恰恰要的就是这个事实——U76 的第三层就死在这里。
     */
    public RetrievalOutcome retrieveWithOutcome(String query, QueryExpansions expansions, int topK) {
        QueryExpansions ex = expansions == null ? QueryExpansions.none() : expansions;

        // 各路结果按「向量 → 关键词 → 图谱」的顺序入池。这个顺序有意义：
        // mergeOnce 的 byKey.putIfAbsent 是先到者胜，同一片被多路召回时留的是先到的那一版
        List<List<DocumentChunk>> ranked = new ArrayList<>(4 + ex.angles().size());
        // ① 语义路：原句 + 假想答案
        ranked.add(vectorStore.search(query, topK * 2));
        if (hasText(ex.hypothetical())) {
            ranked.add(vectorStore.search(ex.hypothetical(), topK * 2));
        }
        // ② 词法路：原句 + 各角度改写
        ranked.add(bm25Search(query));
        for (String angle : ex.angles()) {
            if (hasText(angle)) {
                ranked.add(bm25Search(angle));
            }
        }
        // ③ 图谱路：只认原句。它靠实体编号与实体名定位（SPU5 与华法林），
        //    而变体恰恰是把原句的说法换掉——改写过的句子在这条路上只会削弱它
        // 图谱路的 limit 不能等于最终展示 topK：图谱会先返回通用风险边，
        // 用户真正点名的关系可能排在后面。候选池扩大后再由 RRF + rerank 截到 topK。
        List<DocumentChunk> graphChunks = graphRetrieve(query, Math.max(topK * 3, 10));
        ranked.add(graphChunks);

        // 全部候选汇入同一个 RRF，多留一些给重排腾挪
        List<DocumentChunk> fused = rrfMerge(ranked, graphChunks,
                Math.max(topK * RERANK_CANDIDATES, topK));

        // 图谱路的 key 集合在这里算一次、随结果带出，而不是让下游从截断后的列表反推。
        // 用与融合同一把尺子取 key（切片级=chunkId，文档级=docId），否则「归一到同一把尺子」
        // 会在打标这一步断掉
        Set<String> graphKeys = new HashSet<>();
        for (DocumentChunk chunk : graphChunks) {
            if (chunk != null) {
                graphKeys.add(keyOf(chunk));
            }
        }

        // 重排（未配置时是直接截断）
        List<DocumentChunk> kept = reranker.rerank(query, fused, topK);
        // 扩写随结果带出：它决定「这回检索到底用了哪几个查询」，
        // 而这正是回答为什么对/为什么没查到时最先要问的一件事。
        // 不带出去的话，扩写是否生效在链路上完全不可观测——
        // 调召回率就成了盲调
        RetrievalOutcome.RetrievalTrace trace = new RetrievalOutcome.RetrievalTrace(
                query,
                ranked.stream().limit(2).mapToInt(List::size).sum(),
                ranked.stream().skip(2).limit(1 + ex.angles().size()).mapToInt(List::size).sum(),
                graphChunks.size(), fused.size(), kept.size(), query,
                reranker != Reranker.NOOP);
        return new RetrievalOutcome(kept, graphKeys, !graphChunks.isEmpty(), expansions, trace);
    }

    @Override
    public RetrievalOutcome retrieveWithOutcome(String query, int topK) {
        return retrieveWithOutcome(query, QueryExpansions.none(), topK);
    }
    /**
     * 重排查询可覆盖的同路径检索 —— <b>只给对照实验用</b>，生产调用方一律走
     * {@link #retrieveWithOutcome(String, QueryExpansions, int)}（重排查询固定为原句）。
     * <p>
     * 存在的理由：重排器是 cross-encoder，它拿到的 query 决定「什么算相关」；
     * 「重排查询该不该换成扩写句」是一个能被测量回答的问题，不该靠信念决定。
     * 它与 {@code RetrievalComparisonTest} 里的 {@code AnglesOnlyExpander} 是同一类归因配置：
     * 两种配置之间只差重排查询这一个变量，其余全同。
     * <p>
     * <b>实测结论（2026-10-04，真实向量+重排+扩写，120 条样本 / topK=3）：</b>
     * <pre>
     *   重排查询      字面   口语   语义   全量 Hit/MRR/NDCG
     *   原句（生产）  1.000  1.000  0.700  0.900 / 0.774 / 0.793   ← 语义 Hit@3 最高
     *   原句+假想答案 1.000  0.925  0.700  0.875 / 0.783 / 0.796
     *   原句+角度     1.000  1.000  0.675  0.892 / 0.796 / 0.806   ← MRR/NDCG 最高，语义 Hit@3 掉 0.025
     *   原句+假想+角度 1.000  0.975  0.675  0.883 / 0.782 / 0.797
     * </pre>
     * <b>没有任何一种严格更优</b>：换成扩写句能改善排序（MRR/NDCG），却在 Hit@3 上要么持平、
     * 要么退步——而 Hit@3 才是「用户能不能看到正确依据」的判据，排序改善排在它后面。
     * 因此生产维持原句。这个方法留在这里，是为了让下一个想改它的人先跑一遍再决定，
     * 而不是重新猜一次。
     */
    public List<DocumentChunk> retrieveWithRerankQuery(String query, QueryExpansions expansions,
                                                       String rerankQuery, int topK) {
        QueryExpansions ex = expansions == null ? QueryExpansions.none() : expansions;

        List<List<DocumentChunk>> ranked = new ArrayList<>(4 + ex.angles().size());
        ranked.add(vectorStore.search(query, topK * 2));
        if (hasText(ex.hypothetical())) {
            ranked.add(vectorStore.search(ex.hypothetical(), topK * 2));
        }
        ranked.add(bm25Search(query));
        for (String angle : ex.angles()) {
            if (hasText(angle)) {
                ranked.add(bm25Search(angle));
            }
        }
        List<DocumentChunk> graphChunks = graphRetrieve(query, topK);
        ranked.add(graphChunks);
        List<DocumentChunk> fused = rrfMerge(ranked, graphChunks,
                Math.max(topK * RERANK_CANDIDATES, topK));
        return reranker.rerank(rerankQuery == null || rerankQuery.isBlank() ? query : rerankQuery,
                fused, topK);
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
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
        return bm25Index.search(query);
    }

    /** BM25 索引文本：优先用显式指定的（可含标题与标签），否则退化为切片内容。 */
    private static String indexTextOf(DocumentChunk chunk) {
        return chunk.getIndexText() != null && !chunk.getIndexText().isBlank()
                ? chunk.getIndexText()
                : chunk.getContent();
    }

    /**
     * BM25 的语料统计 —— <b>只在语料变化时算一次</b>，与查询无关。
     * <p>
     * 原先每次检索都把全语料重新分词一遍（为了算词频、词元长度与文档频率）。
     * 单路检索时这笔开销藏在总耗时里看不出来；扩写把「一次检索跑几遍 BM25」
     * 从 1 变成 1+k，它就跟着 ×k。缓存它不是为了快一点，是因为多路检索的前提
     * 就是「同一份语料、多条查询」——不缓存等于让每条查询都付一遍全语料的分词。
     * <p>
     * 它和语料<b>同生共死</b>：{@link #rebuild} 一次换掉整个对象，不提供局部更新。
     * 分两步（先换语料再算统计）会开一个窗口，让查询拿着新语料对着旧统计打分。
     * <p>
     * {@code lens} 用数组而非 List：打分时按下标随机访问，每次查询都走一遍。
     * （也正因如此它不适合做相等性比较——但没人比它。）
     */
    private record Bm25Index(List<DocumentChunk> chunks,
                             List<Map<String, Integer>> termFreqs,
                             double[] lens,
                             double avgLen,
                             List<Map<String, Integer>> unigramFreqs,
                             double[] unigramLens,
                             double unigramAvgLen) {

        static Bm25Index of(List<DocumentChunk> chunks) {
            List<DocumentChunk> units = List.copyOf(chunks);
            int n = units.size();
            List<Map<String, Integer>> termFreqs = new ArrayList<>(n);
            double[] lens = new double[n];
            double totalLen = 0;
            List<Map<String, Integer>> unigramFreqs = new ArrayList<>(n);
            double[] unigramLens = new double[n];
            double unigramTotalLen = 0;
            for (int i = 0; i < n; i++) {
                String text = indexTextOf(units.get(i));
                Map<String, Integer> termFreq = new HashMap<>();
                for (String token : TextTokenizer.tokenize(text)) {
                    termFreq.merge(token, 1, Integer::sum);
                }
                termFreqs.add(termFreq);
                lens[i] = termFreq.values().stream().mapToInt(Integer::intValue).sum();
                totalLen += lens[i];

                Map<String, Integer> unigramFreq = new HashMap<>();
                for (String token : TextTokenizer.unigrams(text)) {
                    unigramFreq.merge(token, 1, Integer::sum);
                }
                unigramFreqs.add(unigramFreq);
                unigramLens[i] = unigramFreq.values().stream().mapToInt(Integer::intValue).sum();
                unigramTotalLen += unigramLens[i];
            }
            return new Bm25Index(units, termFreqs, lens, totalLen > 0 ? totalLen / n : 1.0,
                    unigramFreqs, unigramLens, unigramTotalLen > 0 ? unigramTotalLen / n : 1.0);
        }

        int size() {
            return chunks.size();
        }

        /** 按 BM25 得分降序返回命中（得分为 0 的不进榜，与改造前一致）。 */
        List<DocumentChunk> search(String query) {
            List<String> queryTerms = TextTokenizer.tokenize(query);
            int n = chunks.size();
            if (queryTerms.isEmpty() || n == 0) {
                // 二元组切不出词元时要再给单字通道一次机会：
                // 单字查询（「钙」「片」）或纯标点查询会走到这里，
                // 直接返回空等于把单字通道关在门外
                return unigramSearch(query, n);
            }

            // 文档频率：包含该词元的检索单元数
            Map<String, Integer> unitFreq = new HashMap<>();
            for (String term : queryTerms) {
                int df = 0;
                for (Map<String, Integer> termFreq : termFreqs) {
                    if (termFreq.containsKey(term)) {
                        df++;
                    }
                }
                unitFreq.put(term, df);
            }

            List<ScoredChunk> scored = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                Map<String, Integer> termFreq = termFreqs.get(i);
                double score = 0;
                for (String term : queryTerms) {
                    int tf = termFreq.getOrDefault(term, 0);
                    if (tf == 0) {
                        continue;
                    }
                    int df = unitFreq.get(term);
                    double idf = Math.log((n - df + 0.5) / (df + 0.5) + 1.0);
                    score += idf * (tf * (K1 + 1)) / (tf + K1 * (1 - B + B * lens[i] / avgLen));
                }
                if (score > 0) {
                    scored.add(new ScoredChunk(chunks.get(i), score));
                }
            }

            scored.sort((a, b) -> Double.compare(b.score(), a.score()));
            if (scored.isEmpty()) {
                return unigramSearch(query, n);
            }

            // 二元组与单字按排名融合，而不是直接相加：两套分数量纲不同（二元组的 idf
            // 比单字高一个数量级），直接相加会让单字通道完全不起作用。
            // 用 RRF 融合后再按权重折算，是对「补一路候选」这件事更诚实的表达。
            return fuseWithUnigrams(scored, query, n);
        }

        /** 纯单字通道检索：二元组一颗子都没命中时的兜底。 */
        private List<DocumentChunk> unigramSearch(String query, int n) {
            List<ScoredChunk> scored = scoreUnigrams(query, n);
            scored.sort((a, b) -> Double.compare(b.score(), a.score()));
            return scored.stream().map(ScoredChunk::chunk).toList();
        }

        /** 按 BM25 公式给单字通道打分（词元为查询的单字集合）。 */
        private List<ScoredChunk> scoreUnigrams(String query, int n) {
            List<String> terms = TextTokenizer.unigrams(query);
            if (terms.isEmpty()) {
                return new ArrayList<>();
            }
            Map<String, Integer> unitFreq = new HashMap<>();
            for (String term : terms) {
                int df = 0;
                for (Map<String, Integer> freq : unigramFreqs) {
                    if (freq.containsKey(term)) {
                        df++;
                    }
                }
                unitFreq.put(term, df);
            }
            List<ScoredChunk> scored = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                Map<String, Integer> freq = unigramFreqs.get(i);
                double score = 0;
                for (String term : terms) {
                    int tf = freq.getOrDefault(term, 0);
                    if (tf == 0) {
                        continue;
                    }
                    int df = unitFreq.get(term);
                    double idf = Math.log((n - df + 0.5) / (df + 0.5) + 1.0);
                    score += idf * (tf * (K1 + 1))
                            / (tf + K1 * (1 - B + B * unigramLens[i] / unigramAvgLen));
                }
                if (score > 0) {
                    scored.add(new ScoredChunk(chunks.get(i), score));
                }
            }
            return scored;
        }

        /**
         * 二元组与单字两路的 RRF 融合 —— 越靠前的两路都认，排名越靠前。
         * <p>
         * 权重只作用在单字那一路：二元组是主通道，权重恒为 1。这样当单字通道
         * 在无关文档上凑分时，它最多只能在二元组已经排好的次序里做有限扰动。
         */
        private List<DocumentChunk> fuseWithUnigrams(List<ScoredChunk> bigramScored, String query, int n) {
            List<ScoredChunk> unigramScored = scoreUnigrams(query, n);
            if (unigramScored.isEmpty()) {
                return bigramScored.stream().map(ScoredChunk::chunk).toList();
            }
            unigramScored.sort((a, b) -> Double.compare(b.score(), a.score()));

            Map<DocumentChunk, Double> fused = new LinkedHashMap<>();
            for (int rank = 0; rank < bigramScored.size(); rank++) {
                fused.merge(bigramScored.get(rank).chunk(), 1.0 / (RRF_CONST + rank + 1), Double::sum);
            }
            for (int rank = 0; rank < unigramScored.size(); rank++) {
                fused.merge(unigramScored.get(rank).chunk(),
                        UNIGRAM_WEIGHT / (RRF_CONST + rank + 1), Double::sum);
            }
            return fused.entrySet().stream()
                    .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                    .map(Map.Entry::getKey)
                    .toList();
        }
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
    private List<DocumentChunk> rrfMerge(List<List<DocumentChunk>> ranked, int topK) {
        return rrfMerge(ranked, List.of(), topK);
    }

    /**
     * 互惠排名融合，并保留「这轮图谱路命中过哪几片」这一事实。
     * <p>
     * {@code graphChunks} 是图谱路<b>原始</b>的那份结果（尚未与其他路融合）。它单独传进来，
     * 是因为融合会丢掉图谱身份：同一片被文本路与图谱路同时召回时，{@code byKey.putIfAbsent}
     * 留的是先到的文本版，而它 {@code source=manual}。正文该留原文（转述不如原文），
     * 但「图谱也认同这一片」这件事不能跟着一起消失——下游拒答门正是靠它决定要不要给
     * 图谱依据提级（见 {@link EvidenceGate}）。把正文与来源压成一个字段，就是 U76。
     */
    private List<DocumentChunk> rrfMerge(List<List<DocumentChunk>> ranked,
                                         List<DocumentChunk> graphChunks, int topK) {
        Map<String, Double> scores = new HashMap<>();
        Map<String, DocumentChunk> byKey = new LinkedHashMap<>();

        for (List<DocumentChunk> list : ranked) {
            mergeOnce(scores, byKey, list);
        }

        // 图谱路的 key 集合。注意按与融合同一把尺子取 key（切片级=chunkId，文档级=docId），
        // 否则「归一到同一把尺子」这件事在打标这一步会断掉
        Set<String> graphKeys = new HashSet<>();
        for (DocumentChunk chunk : graphChunks) {
            if (chunk != null) {
                graphKeys.add(keyOf(chunk));
            }
        }

        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                // RRF 分<b>只用来定序，不透传</b>。它的量纲是 1/(60+rank)：第 1 名 1/60、
                // 第 5 名 1/64，绝对大小不表达「有多相关」，拿它当阈值等于拿名次当置信度。
                // 下游需要的相关性信号在别处——向量路由 VectorStore 写在 chunk.score 上，
                // 重排器会再覆盖一次。这里原样带出去。
                .map(e -> withGraphFlag(copyOf(byKey.get(e.getKey())), graphKeys.contains(e.getKey())))
                .toList();
    }

    /** 融合归一用的 key：与 {@link #mergeOnce} 保持同一套规则，两处不一致就是分叉的土壤 */
    private String keyOf(DocumentChunk chunk) {
        return groupByDocId ? chunk.getDocId() : chunk.getChunkId();
    }

    /**
     * 给切片打上「本轮被图谱路命中」的标记。
     * <p>
     * 只写 true，不写 false：{@code null} 表示「这条来自文本路、与图谱无关」，与
     * {@code false} 在语义上没有区别，写 false 只是给每个切片多加一个字段。
     */
    private static DocumentChunk withGraphFlag(DocumentChunk chunk, boolean graphBacked) {
        if (chunk == null || !graphBacked) {
            return chunk;
        }
        return chunk.toBuilder().graphBacked(true).build();
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
     * <p>
     * <b>「先到者胜」丢掉的只是正文，不该丢掉来源。</b>文本版赢了这场取舍，但图谱路
     * 同样召回了这一片这个事实，要被 {@link #rrfMerge} 单独记下来（{@code graphBacked}）——
     * 下游拒答门据此判断本轮有没有图谱依据在场。同一片正文用原文、来源记住图谱，
     * 两者本来就该分开存，见 {@link DocumentChunk#getGraphBacked()}。
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
