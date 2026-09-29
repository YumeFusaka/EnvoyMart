package yumefusaka.envoymart.aiservice.knowledge;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import yumefusaka.envoymart.agent.rag.Document;
import yumefusaka.envoymart.agent.rag.DocumentChunk;
import yumefusaka.envoymart.agent.rag.HybridRetriever;
import yumefusaka.envoymart.agent.rag.TextSplitter;
import yumefusaka.envoymart.agent.rag.VectorStore;

import java.util.List;

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

    /**
     * 是否在启动时自动构建索引。
     * <p>
     * 默认开。关掉它的场景是测试与「只想起个空壳」的排查——
     * 关掉之后检索恒为空，AI 的每句话都会落到「知识库中没有相关依据」。
     */
    private final boolean autoIndex;

    public KnowledgeIndexer(KnowledgeCorpus corpus,
                            @Qualifier("knowledgeVectorStore") VectorStore vectorStore,
                            TextSplitter splitter,
                            HybridRetriever retriever,
                            KnowledgeGraphBuilder graphBuilder,
                            @Value("${envoymart.rag.auto-index:true}") boolean autoIndex) {
        this.corpus = corpus;
        this.vectorStore = vectorStore;
        this.splitter = splitter;
        this.retriever = retriever;
        this.graphBuilder = graphBuilder;
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
        List<Document> documents = corpus.reload();
        List<DocumentChunk> chunks = documents.stream()
                .flatMap(doc -> splitter.split(doc).stream())
                .toList();

        // 先按 docId 清旧再写：向量库是持久化的，入库本身没有幂等性。
        // 少了这一步，每重建一次集合里就多堆一份，重复条目会挤占 topK、
        // 让同一篇文档在结果里出现多次。
        documents.forEach(doc -> vectorStore.deleteByDocId(doc.getId()));
        vectorStore.indexBatch(chunks);

        // BM25 侧整份替换：切分结果一变，片数与编号全变，没有逐篇对齐的可能
        retriever.rebuild(chunks);

        // 图谱放在索引之后，且**单独兜住异常**：它要调十几次模型、还依赖一个外部图库，
        // 任何一步失败都不该把已经建好的检索索引一起算成失败——检索是主链路，
        // 图谱是它的增强，增强挂了主链路必须照常可用。
        KnowledgeGraphBuilder.BuildReport graph = buildGraph(documents);

        log.info("[Knowledge] 索引重建完成：文档 {} 篇，切片 {} 片；图谱入库 {} 条",
                documents.size(), chunks.size(), graph == null ? 0 : graph.accepted());
        return new Result(documents.size(), chunks.size(),
                graph == null ? 0 : graph.accepted(),
                graph == null ? 0 : graph.rejected(),
                graph == null ? 0 : graph.failed(),
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
     * 重建结果 —— 管理台要显示「重建了几篇、几片」，而不只是「成功」。
     * <p>
     * 图谱的四个数字单独回传而不是合成一个布尔：{@code rejected} 高说明提示词或校验在掐掉
     * 大量结果，{@code failed} 高说明模型或写入有问题，{@code graphAvailable=false} 说明
     * 图库根本没连上——三种情况的处置完全不同，一个「图谱构建失败」把它们抹平了就没法排查。
     */
    public record Result(int documentCount, int chunkCount,
                         int graphAccepted, int graphRejected, int graphFailed,
                         boolean graphAvailable) {
    }
}
