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
        rebuild();
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
