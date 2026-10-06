package yumefusaka.envoymart.aiservice.knowledge;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import yumefusaka.envoymart.agent.llm.TokenLedger;
import yumefusaka.envoymart.agent.rag.Document;
import yumefusaka.envoymart.agent.rag.DocumentChunk;
import yumefusaka.envoymart.agent.rag.HybridRetriever;
import yumefusaka.envoymart.agent.rag.TextSplitter;
import yumefusaka.envoymart.agent.rag.VectorStore;
import yumefusaka.envoymart.aiservice.llm.ModelPricing;
import yumefusaka.envoymart.contract.KnowledgeIndexResult;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 检索索引的构建与重建 —— <b>知识库是事实源，索引是它的派生物</b>。
 * <p>
 * 这个类存在的理由是「重建」这件事必须只有一个入口。索引有两个部分：
 * 向量库（Milvus 或内存）与 BM25 的语料快照。它们分属两个不同的组件，
 * 如果各自被单独更新，就会出现「向量路搜得到、关键词路搜不到」这种半新半旧的状态——
 * 而两条路的结果是要做 RRF 融合的，一边缺了不会报错，只会让召回悄悄变差。
 * <p>
 * 语料全部来自 knowledge-service（{@link KnowledgeCorpus}），本服务不持有任何文档原文。
 */
@Slf4j
@Service
public class KnowledgeIndexer {

    private final KnowledgeCorpus corpus;
    private final VectorStore vectorStore;
    private final TextSplitter splitter;
    private final HybridRetriever retriever;
    private final KnowledgeGraphBuilder graphBuilder;
    private final ModelPricing pricing;

    /**
     * 是否在启动时自动构建索引。
     * <p>
     * 默认开。关掉它的场景是测试与「只想起个空壳」的排查——
     * 关掉之后检索恒为空，AI 的每句话都会落到「知识库中没有相关依据」。
     */
    private final boolean autoIndex;

    /** 后台重建的互斥位。见 {@link #rebuildAsync()}：它挡的是「重复发起」，不是「并发写」 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * 排队等待重建的文档编号 —— 见 {@link #rebuildOneAsync(String)}。
     * <p>
     * <b>为什么是队列而不是「忙就丢弃」</b>：单篇重建的调用方是商品上下架联动，
     * 它要的语义是「这件事一定会发生」。忙时直接返回会让**下架的说明书继续留在索引里**，
     * 而调用方只看到一条「已发起」的日志，没有任何地方会报错——
     * 一个静默的丢请求。队列把「忙」变成「稍后」，恢复的是「不丢」而不是「不排队」。
     * <p>
     * 同一个 docNo 重复入队只留一个（{@link java.util.concurrent.ConcurrentLinkedQueue} 允许重复，
     * 这里用 {@code contains} 去重）：连续上下架同一个商品时，
     * 最终态才是要紧的，中间态重建不重建都不影响结果，而每次重建都是一次模型调用。
     */
    private final java.util.concurrent.ConcurrentLinkedQueue<String> pendingDocs =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

    /**
     * 最近一次重建的状态。{@code volatile} 而不是加锁：读写双方都只做一次引用赋值/读取，
     * 状态对象本身不可变，不存在需要原子更新的复合状态。
     */
    private volatile Status status = new Status(false, null, null, null, null);

    public KnowledgeIndexer(KnowledgeCorpus corpus,
                            @Qualifier("knowledgeVectorStore") VectorStore vectorStore,
                            TextSplitter splitter,
                            HybridRetriever retriever,
                            KnowledgeGraphBuilder graphBuilder,
                            ModelPricing pricing,
                            @Value("${envoymart.rag.auto-index:true}") boolean autoIndex) {
        this.corpus = corpus;
        this.vectorStore = vectorStore;
        this.splitter = splitter;
        this.retriever = retriever;
        this.graphBuilder = graphBuilder;
        this.pricing = pricing;
        this.autoIndex = autoIndex;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void indexOnStartup() {
        if (!autoIndex) {
            log.warn("[Knowledge] envoymart.rag.auto-index=false，跳过启动建索引 —— "
                    + "本次运行中检索将恒为空，AI 回答会全部落到「知识库中没有相关依据」");
            return;
        }
        // 异步建，**不能同步**。这句曾经是 rebuild()：它把整库 embedding + 全量图谱抽取
        // 压在 ApplicationReadyEvent 的同步监听器里，而 readiness 探针要等这个事件广播完
        // 才把实例标成 ACCEPTING_TRAFFIC——于是服务在「已启动、正在建索引」的几分钟里
        // /actuator/health 一直是 503 OUT_OF_SERVICE。后果不是慢，是**起不来**：
        // run-local.sh 的 svc_ready 只认 200/404，会把 503 当未就绪，补启轮再 kill 掉它
        // 重启，索引从头再建一遍，永远追不上脚本的等待窗口（2026-10-06 实测三轮全失败）。
        //
        // 语料从 15 篇涨到 47 篇后，这段阻塞从七十几秒涨到三分多钟，才把这个一直存在的
        // 设计问题暴露出来。异步化后：readiness 立刻可用，索引在后台建，进度由
        // status() / 管理台接口可观测；期间检索会偏空，这是「索引尚未建完」的如实结果。
        rebuildAsync();
    }

    /**
     * 整份重建索引：重新拉语料 → 重切 → 覆盖向量库 → 替换 BM25 快照。
     * <p>
     * {@code synchronized}：重建是「先清空、再写入」的两步，两个重建交错会让先清空的那个
     * 把后写入的清掉，最终索引里少一篇文档且没有任何报错。重建是管理动作，不是高频路径，
     * 串行化的代价可以忽略。
     */
    public synchronized Result rebuild() {
        Instant startedAt = Instant.now();
        // 状态由这里推进，不由 rebuildAsync 推进：启动时的 indexOnStartup 也走这个方法，
        // 只在异步包装里记状态的话，启动建的那一次在管理台上会显示成「从没跑过」
        status = new Status(true, startedAt, null, status.result(), null);
        // 重建是花钱最集中的动作（整库 embedding + 全量图谱抽取），却不在任何一轮对话里，
        // 没有调用方会去读这份账。不自己开一次，那些 TokenLedger.record 就是往真空里记
        // （无账本即静默丢弃），这笔钱只剩 [LLM] 里几十行碎片，没人加得起来
        try (TokenLedger.Scope ledger = TokenLedger.begin()) {
            try {
                Result result = doRebuild();
                status = new Status(false, startedAt, Instant.now(), result, null);
                logCost("完成", ledger.snapshot());
                return result;
            } catch (RuntimeException | Error e) {
                // 失败必须落到状态里：只回 running=false 的话，「跑完了什么都没发生」
                // 与「跑起来就崩了」在调用方看来完全一样
                log.error("[Knowledge] 索引重建失败", e);
                status = new Status(false, startedAt, Instant.now(), status.result(),
                        e.getClass().getSimpleName() + ": " + e.getMessage());
                // 失败也要结账：已经花掉的钱不会因为结尾失败退回来
                logCost("失败", ledger.snapshot());
                throw e;
            }
        }
    }

    /**
     * 本次重建的账单。
     * <p>
     * 没配单价的模型单独点名——不点的话，金额看起来像是个完整的数，而它其实只覆盖了其中一部分。
     */
    private void logCost(String outcome, TokenLedger.Snapshot snapshot) {
        if (snapshot.isEmpty()) {
            log.info("[Knowledge] 索引重建{}，本次未发生模型调用", outcome);
            return;
        }
        List<String> unpriced = pricing.unpricedModels(snapshot.models());
        log.info("[Knowledge] 索引重建{}，本次用量 tokens={}（输入 {} / 输出 {}）约 {} 元{}",
                outcome, snapshot.totalTokens(), snapshot.promptTokens(), snapshot.completionTokens(),
                pricing.estimateCny(snapshot.models()),
                unpriced.isEmpty() ? "" : "；未配单价、未计入金额的模型：" + unpriced);
    }

    private Result doRebuild() {
        List<Document> documents = corpus.reload();
        List<DocumentChunk> chunks = documents.stream()
                .flatMap(doc -> splitter.split(doc).stream())
                .toList();

        // 整份清空再写：向量库是持久化的，入库本身没有幂等性。
        //
        // 这里曾经是逐篇 deleteByDocId(doc.getId())，只清得掉「这次语料里有的」那些文档。
        // 语料一旦改名或换目录，旧条目就永远留在库里：实测有 15 条早期种子数据
        // （docId 形如 after_sale_1、guide_1）躺了几个月，它们连 title/position 都没有，
        // 被召回时模型引用不了、用户点开无处可去，还实打实地挤占 topK ——
        // 一次「蓝牙耳机怎么连」的问句就把其中一条捞了上来。
        // 逐篇删要求「库里的 docId 集合恰好等于历史语料的并集」，这是个没人维护得住的假设；
        // 整份清空则把「库里恰好等于当前语料」变成重建的性质本身。
        vectorStore.removeAll();
        vectorStore.indexBatch(chunks);

        // BM25 侧整份替换：切分结果一变，片数与编号全变，没有逐篇对齐的可能
        retriever.rebuild(chunks);

        // 图谱放在索引之后，且**单独兜住异常**：它要调十几次模型、还依赖一个外部图库，
        // 任何一步失败都不该把已经建好的检索索引一起算成失败——检索是主链路，
        // 图谱是它的增强，增强挂了主链路必须照常可用。
        KnowledgeGraphBuilder.BuildReport graph = buildGraph(documents);

        // 图谱那三个数字给的是 stored 而不是 accepted：这一行是**运维读的那一行**，
        // 写「入库 40 条」就必须真的是图上有 40 条。图谱不可用那一批两者会差一个数量级
        log.info("[Knowledge] 索引重建完成：文档 {} 篇，切片 {} 片；图谱入库 {} 条，丢弃 {} 条，未入库 {} 篇，跳过 {} 篇",
                documents.size(), chunks.size(),
                graph == null ? 0 : graph.stored(), graph == null ? 0 : graph.rejected(),
                graph == null ? 0 : graph.failed(), graph == null ? 0 : graph.skipped());
        return new Result(documents.size(), chunks.size(),
                graph == null ? 0 : graph.accepted(),
                graph == null ? 0 : graph.stored(),
                graph == null ? 0 : graph.rejected(),
                graph == null ? 0 : graph.failed(),
                graph == null ? 0 : graph.skipped(),
                graph != null && graph.graphAvailable());
    }

    private KnowledgeGraphBuilder.BuildReport buildGraph(List<Document> documents) {
        try {
            return graphBuilder.build(documents);
        } catch (RuntimeException e) {
            log.error("[Knowledge] 图谱构建失败，本次不更新图谱；检索索引已建好不受影响", e);
            return null;
        }
    }

    /**
     * 单篇增量重建 —— 管理台上传/编辑/停用一篇文档后，让这一篇立刻生效。
     * <p>
     * <b>为什么不能直接复用全量 {@link #rebuild()}</b>：全量重建是「清空 + 整库 embedding +
     * 全量图谱抽取」，实测七十几秒、十几次模型调用。上传一篇文档就付这个代价，
     * 等于把「改一条规则」变成了「每次都要等一分钟、还按整库计费」。
     * <p>
     * <b>三段各自的最小改动面</b>：
     * <ul>
     *   <li><b>向量库</b>：{@code deleteByDocId} + 只编码这一篇的切片，是真正的增量。</li>
     *   <li><b>BM25</b>：索引是不可变快照（{@code Bm25Index}），没有按篇增删的能力，
     *       只能整份重建。但它<b>纯计算、不调模型、不花钱</b>，几十毫秒量级，
     *       所以这里接受整份重建——「为了让实现看起来对称而给它硬套增量」会多出一套
     *       需要维护的倒排更新逻辑，换来的收益是几十毫秒。</li>
     *   <li><b>图谱</b>：写入语义本来就是「按文档整体替换」（{@code replaceDocument}），
     *       所以只重抽这一篇。这是天然的增量点。</li>
     * </ul>
     * <p>
     * <b>删/停用也必须走这里</b>：{@code docNo} 传进来但语料里已经没有它时，
     * {@code documents} 里找不到，那就<b>只删不写</b>——把该篇的向量与图谱边清掉。
     * 漏掉这一步的后果是「停用的文档照样被检索到」，正是下架商品必须立刻不可买的那类问题。
     *
     * @param docNo 变化的文档编号
     * @return 这一篇的处理结果；文档不存在于当前语料时返回 deletedOnly=true 的结果
     */
    public synchronized KnowledgeIndexResult rebuildOne(String docNo) {
        Instant startedAt = Instant.now();
        try (TokenLedger.Scope ledger = TokenLedger.begin()) {
            List<Document> documents = corpus.reload();
            Document target = documents.stream()
                    .filter(doc -> docNo.equals(doc.getId()))
                    .findFirst()
                    .orElse(null);

            if (target == null) {
                // 文档被删、改名，或**被停用**（停用的文档不会出现在语料里——
                // knowledge-service 的语料查询带着 status=1，所以停用无需另写分支）：只删不写。
                // 逐篇删在这里是**正确的**粒度——要清掉的恰好就是这一篇，
                // 与全量重建里「逐篇删清不掉历史孤儿」是两种不同的场景，不矛盾
                vectorStore.deleteByDocId(docNo);
                List<DocumentChunk> chunks = documents.stream()
                        .flatMap(doc -> splitter.split(doc).stream())
                        .toList();
                retriever.rebuild(chunks);
                // 空 triples 会把这篇在图上的边整体替换成空，等于删掉它的边
                boolean graphOk = graphBuilder.rebuildOne(docNo, null);
                KnowledgeIndexResult result = new KnowledgeIndexResult(docNo, true, 0, chunks.size(),
                        graphOk, null);
                log.info("[Knowledge] 单篇增量：文档 {} 已不在语料中，仅执行删除（切片总数 {}）",
                        docNo, chunks.size());
                return result;
            }

            List<DocumentChunk> own = splitter.split(target);
            // 先删这一篇的旧切片，再写新的：只删不写会让文档在重建中途消失，
            // 只写不删会留下编号已经变了的旧切片（切片编号基于位置，改一行正文就整体错位）
            vectorStore.deleteByDocId(docNo);
            vectorStore.indexBatch(own);

            List<DocumentChunk> all = documents.stream()
                    .flatMap(doc -> splitter.split(doc).stream())
                    .toList();
            retriever.rebuild(all);

            boolean graphOk = graphBuilder.rebuildOne(docNo, target);
            KnowledgeIndexResult result = new KnowledgeIndexResult(docNo, false, own.size(), all.size(),
                    graphOk, null);
            log.info("[Knowledge] 单篇增量完成：文档 {} 切片 {} 片（语料共 {} 片），图谱{}",
                    docNo, own.size(), all.size(), graphOk ? "已更新" : "未更新（保持上一版）");
            logCost("单篇增量", ledger.snapshot());
            return result;
        }
    }

    /**
     * 单篇增量重建的<b>异步</b>入口 —— 供服务间调用（商品上下架联动）。
     * <p>
     * <b>为什么它必须是异步的</b>：单篇重建要走一次图谱抽取，那是模型调用，
     * 实测这一篇花了约 120 秒；而商品服务侧 Feign 的读超时是 30 秒。
     * 同步版本在这里的表现是「调用方 100% 超时报失败，而索引其实建好了」——
     * 一条永远报错、实际成功的链路，比没有还难排查。
     * <p>
     * <b>与管理台那条同步接口的分工</b>：管理台是人在点、能等一会儿、要立刻看到结果，
     * 而且 FE 的请求走网关、超时口径可以按需放宽；联动是商品上架事务提交后的尾巴，
     * 只要求「一定会发生」，不要求「在这一个请求里发生完」。
     * 所以这里复用后台任务那套 {@code running} 状态，发起即返回，进度由 {@link #status()} 观察。
     * <p>
     * 正在跑时再调它<b>不排队、不阻塞</b>：直接把当前状态回给调用方——同 {@link #rebuildAsync()}。
     *
     * @return 当前状态；刚发起时为 {@code running=true}
     */
    public Status rebuildOneAsync(String docNo) {
        // 先入队再尝试起线程：即使此刻正有重建在跑，这一篇也不会丢，会在当前那次结束后被排空。
        // 去重是因为连续上下架同一个商品时「最终态」才要紧，而每次重建都要花一次模型调用的钱
        if (!pendingDocs.contains(docNo)) {
            pendingDocs.add(docNo);
        }
        if (running.compareAndSet(false, true)) {
            status = new Status(true, Instant.now(), null, status.result(), null);
            Thread.ofVirtual().name("knowledge-reindex-one").start(this::drainPending);
        }
        return status;
    }

    /**
     * 排空待重建队列 —— 一次只跑一篇，跑完再取下一篇，直到队列空。
     * <p>
     * <b>为什么在这里循环而不是每篇各起一个线程</b>：重建之间是互斥的（{@link #rebuildOne(String)}
     * 的 {@code synchronized} 与全量重建共用同一把锁），并发起多个线程只会让它们互相等，
     * 还会让 {@code status} 的读写变得难以推理。串行排空既满足互斥，又不丢请求。
     */
    private void drainPending() {
        Instant startedAt = status.startedAt();
        String lastError = null;
        try {
            String docNo;
            while ((docNo = pendingDocs.poll()) != null) {
                try {
                    KnowledgeIndexResult result = rebuildOne(docNo);
                    if (result != null && result.getError() != null) {
                        lastError = result.getError();
                    }
                    log.info("[Knowledge] 待重建队列出队并完成：docNo={}（剩余 {}）", docNo, pendingDocs.size());
                } catch (Throwable e) {
                    log.error("[Knowledge] 单篇后台重建失败 docNo={}", docNo, e);
                    lastError = e.getMessage();
                }
            }
        } finally {
            // 先放锁再落状态：放锁之后可能有新请求入队并立刻起新线程，
            // 那时它会写一份更新的 status；这里若在锁内写会把它覆盖成旧的
            running.set(false);
            status = new Status(false, startedAt, Instant.now(), status.result(), lastError);
            // 兜住「跑完一篇、正在写状态」这一瞬入队的请求：从入队到起线程之间没有锁，
            // 可能发生「入队时 running 还是 true，等 running 变 false 时没人再来排空」
            if (!pendingDocs.isEmpty() && running.compareAndSet(false, true)) {
                Thread.ofVirtual().name("knowledge-reindex-one").start(this::drainPending);
            }
        }
    }

    /**
     * 单篇增量的结果。
     *
     * @param deletedOnly 文档已不在语料中，只做了删除
     * @param chunkCount  这一篇的切片数（deletedOnly 时为 0）
     * @param totalChunks 更新后语料的总切片数，便于调用方判断索引规模
     * @param graphUpdated 图谱是否更新成功。false 时图谱保持上一版，检索不受影响
     */

    /**
     * 后台重建，<b>立即返回</b>——这是管理台调用的那个入口。
     * <p>
     * 为什么不直接同步跑完：一次完整重建要调十几次模型抽关系，实测七十几秒，
     * 而 HTTP 客户端等不了那么久。同步版的表现是<b>接口超时（客户端看到 HTTP 000）
     * 但重建其实成功完成了</b>——调用方以为失败、系统状态却是新的，这是最难排查的一类不一致。
     * 所以把「已经开始」和「跑完了没有」拆成两件事：这个接口只回答前者，
     * 后者由 {@link #status()} 回答。
     * <p>
     * 正在跑的时候再调它<b>不排队、不阻塞</b>，直接把当前状态回给调用方：
     * 重建是「先清空、再写入」的，排队等锁的那个请求会让发起者挂在这里几十秒，
     * 而它想要的信息（现在在不在跑）立刻就能给。
     */
    public Status rebuildAsync() {
        if (running.compareAndSet(false, true)) {
            // 先表态再起线程：线程真正跑到 rebuild() 还得等一会儿（要能拿到 synchronized 锁），
            // 不等这一下的话 POST 的响应会回一个 running=false——刚发起的人被告知「没在跑」
            status = new Status(true, Instant.now(), null, status.result(), null);
            Thread.ofVirtual().name("knowledge-reindex").start(() -> {
                try {
                    // 状态的推进全在 rebuild() 里；这里只负责「起一次」和「起完放锁」。
                    // 连 Error 一起兜住：从 run() 漏出去的异常会让这个虚拟线程静默死掉，
                    // 而 running 永远停在 true——表现是「重建接口从此一直说在跑」，再也起不来
                    rebuild();
                } catch (Throwable e) {
                    log.error("[Knowledge] 后台重建失败", e);
                } finally {
                    running.set(false);
                    // 全量重建期间入队的单篇请求不能丢：全量已经把整库刷成最新，
                    // 但这些请求里可能有刚被停用的文档，它的旧向量得被清掉才算落地
                    if (!pendingDocs.isEmpty() && running.compareAndSet(false, true)) {
                        Thread.ofVirtual().name("knowledge-reindex-one").start(this::drainPending);
                    }
                }
            });
        }
        // 已经在跑的时候不排队也不阻塞，直接把当前状态给调用方：它想知道的
        // 「现在在不在跑」立刻就有答案，而排队等锁会让请求挂在这里几十秒
        return status;
    }

    /** 重建状态。{@code running=true} 时后三个字段都还没有意义 */
    public Status status() {
        return status;
    }

    /**
     * @param running   是否正在重建。为 true 时检索会落在一个「已清空、还没写满」的索引上，
     *                  那期间 AI 的回答会偏「没有相关依据」——管理台要把这段显示出来
     * @param result    上次成功的结果。从没成功过时是 null
     * @param error     上次失败的原因，成功或还没跑过时是 null。
     *                  <b>失败必须留痕</b>：只回 running=false 的话，「跑完了什么都没发生」
     *                  与「跑起来就崩了」在调用方看来是一样的
     */
    public record Status(boolean running, Instant startedAt, Instant finishedAt,
                         Result result, String error) {
    }

    /**
     * 重建结果 —— 管理台要显示「重建了几篇、几片」，而不只是「成功」。
     * <p>
     * 图谱的几个数字单独回传而不是合成一个布尔：{@code rejected} 高说明提示词或校验在掐掉
     * 大量结果，{@code failed}/{@code skipped} 高说明模型、写入或目录有问题，
     * {@code graphAvailable=false} 说明图库根本没连上——几种情况的处置完全不同，
     * 一个「图谱构建失败」把它们抹平了就没法排查。
     *
     * @param graphAccepted 校验通过的条数，**不是**图上真正有的条数
     * @param graphStored   真正写进图的条数。图谱不可用时它会是 0 而 accepted 有一堆
     * @param graphSkipped  因整批中止而根本没跑的篇数
     */
    public record Result(int documentCount, int chunkCount,
                         int graphAccepted, int graphStored, int graphRejected,
                         int graphFailed, int graphSkipped,
                         boolean graphAvailable) {
    }
}
