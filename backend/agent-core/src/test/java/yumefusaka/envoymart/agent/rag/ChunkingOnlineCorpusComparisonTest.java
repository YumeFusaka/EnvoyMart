package yumefusaka.envoymart.agent.rag;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 线上原文语料上的「融合粒度 + 真实向量 + 真实重排」对照（U14 · P2-3）。
 * <p>
 * 它补的是哪段空白：此前有两套 A/B，各缺一半——
 * MultiChunkRetrievalTest 跑在线上原文夹具（9 篇原文 / 92 片 / 22 条标注）上，但只用 BM25 词法路，量不到真实语义；
 * ChunkingRetrievalComparisonTest 有真实向量与重排，但跑在聚合出来的 LongDocFixtures 上，不是线上那 47 篇原文。
 * 这一条把两半合起来：线上原文语料 + 真实 embedding + 真实重排 + 四种粒度。
 * <p>
 * 为什么必须加第 4 档（切片级 + 重排）：前三档里没有一档是生产配置，线上是「切片级混合 + 重排」。
 * 少了它，这份表回答的是「哪种粒度在无重排时更强」，而不是「线上那套配置里切片级融合贡献了多少」。
 * <p>
 * 为什么不设「切片级 >= 文档级」：MultiChunkRetrievalTest 的类注释已论证那是错的——
 * 文档级的命中里混着「整篇被召回」的假高，只要那篇文档进了 topK，它内部任何条款都算命中。
 * 本用例只报告，只在两件确定的事上断言：夹具确实是多切片形态；库外样本确实命中不了对应条款。
 * <p>
 * 需要 RUN_RETRIEVAL_COMPARISON=true 与 DASHSCOPE_API_KEY（或 EMBEDDING_API_KEY）。
 */
@EnabledIfEnvironmentVariable(named = "RUN_RETRIEVAL_COMPARISON", matches = "true")
class ChunkingOnlineCorpusComparisonTest {

    private static final String EMBEDDING_MODEL = "text-embedding-v4";
    private static final String RERANK_MODEL = "gte-rerank-v2";

    /** 与线上 Agent.Config.ragTopK(3) 一致 */
    private static final int TOP_K = 3;

    /** API key 从两个名字里取，哪个在就用哪个。 */
    private static String apiKey() {
        String key = System.getenv("DASHSCOPE_API_KEY");
        if (key == null || key.isBlank()) {
            key = System.getenv("EMBEDDING_API_KEY");
        }
        return key;
    }

    @Test
    void 线上原文语料上的粒度与重排对照() {
        String apiKey = apiKey();
        List<Document> docs = MultiChunkFixtures.DOCS;
        TextSplitter splitter = MultiChunkFixtures.splitter();
        List<DocumentChunk> chunks = MultiChunkFixtures.chunks();

        InMemoryVectorStore store = new InMemoryVectorStore(
                new DashScopeEmbeddingService(apiKey, EMBEDDING_MODEL));
        DashScopeReranker reranker = new DashScopeReranker(
                apiKey, RERANK_MODEL, null, java.time.Duration.ofSeconds(30));

        Retriever vectorOnly = (query, topK) -> store.search(query, topK);
        Retriever docLevel = new HybridRetriever(store, docs);
        Retriever chunkLevel = HybridRetriever.overChunks(store, chunks, Reranker.NOOP);
        Retriever chunkLevelRerank = HybridRetriever.overChunks(store, chunks, reranker);

        new SimpleRAGEngine(store, docLevel, splitter).ingestBatch(docs);

        System.out.printf("%n========== 线上原文语料 粒度x重排 对照（%d 篇 / %d 片 / %d 条标注，topK=%d） ==========%n",
                docs.size(), chunks.size(), MultiChunkFixtures.CASES.size(), TOP_K);
        System.out.printf("%-26s %10s %10s %10s %8s%n", "配置", "TEXTUAL", "PARAPHRASE", "HARD", "REFUSE");

        Map<String, Retriever> configs = new LinkedHashMap<>();
        configs.put("1 仅向量路 切片", vectorOnly);
        configs.put("2 文档级混合", docLevel);
        configs.put("3 切片级混合", chunkLevel);
        configs.put("4 切片级混合+重排 生产", chunkLevelRerank);

        for (Map.Entry<String, Retriever> entry : configs.entrySet()) {
            Map<MultiChunkFixtures.Stratum, String> rates = new LinkedHashMap<>();
            int refuseHit = countRefuseHits(entry.getValue());
            for (MultiChunkFixtures.Stratum stratum : List.of(MultiChunkFixtures.Stratum.TEXTUAL,
                    MultiChunkFixtures.Stratum.PARAPHRASE, MultiChunkFixtures.Stratum.HARD)) {
                rates.put(stratum, rate(countHits(entry.getValue(), MultiChunkFixtures.casesOf(stratum)),
                        MultiChunkFixtures.casesOf(stratum).size()));
            }
            System.out.printf("%-26s %10s %10s %10s %8d%n", entry.getKey(),
                    rates.get(MultiChunkFixtures.Stratum.TEXTUAL),
                    rates.get(MultiChunkFixtures.Stratum.PARAPHRASE),
                    rates.get(MultiChunkFixtures.Stratum.HARD), refuseHit);
        }

        int success = reranker.successCount();
        int degraded = reranker.degradedCount();
        System.out.printf("重排调用：生效 %d 次，降级 %d 次%n", success, degraded);
        if (degraded > 0) {
            System.out.println("存在降级（原因示例：" + reranker.lastDegradeReason() + "）");
            System.out.println("   降级等价于不重排，第 4 档数字不可复现，不代表重排的真实效果。");
        }
        System.out.println("========================================================================================");

        assertThat(chunks.size())
                .as("线上原文夹具必须切成多片，否则粒度对照无从谈起")
                .isGreaterThan(docs.size() * 2);
        assertThat(countRefuseHits(chunkLevelRerank))
                .as("库外样本不该命中任何标注条款")
                .isZero();
        assertThat(success)
                .as("第 4 档若全部降级，等同第 3 档，不能当作生产配置的数字")
                .isGreaterThan(0);
    }

    private static int countHits(Retriever retriever, List<MultiChunkFixtures.Case> cases) {
        int hit = 0;
        for (MultiChunkFixtures.Case c : cases) {
            if (c.clause() == null) {
                continue;
            }
            List<DocumentChunk> retrieved = retriever.retrieve(c.query(), TOP_K);
            boolean found = retrieved.stream().anyMatch(chunk ->
                    MultiChunkFixtures.normalize(chunk.getContent()).contains(
                            MultiChunkFixtures.normalize(c.clause())));
            if (found) {
                hit++;
            }
        }
        return hit;
    }

    /** 库外样本被捞到标注条款的条数，正确行为恒为 0 */
    private static int countRefuseHits(Retriever retriever) {
        return countHits(retriever, MultiChunkFixtures.casesOf(MultiChunkFixtures.Stratum.REFUSE));
    }

    private static String rate(int hit, int total) {
        return "%.3f".formatted(total == 0 ? 0.0 : (double) hit / total);
    }
}
