package yumefusaka.envoymart.agent.rag;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 多切片语料上的检索评测（U14）—— 补上原夹具结构性地测不到的那一段。
 * <p>
 * <b>原夹具的盲区。</b>90 篇短文每篇 30~51 字，短于切分窗口，于是<b>每篇恰好一片</b>：
 * 一篇文档只有一个切片，按 docId 归一和按 chunkId 归一得到同一份结果——
 * 切片级融合、切分散列、跨片召回这些只在线上升效的行为，在那套语料上<b>换任何实现都测不出差别</b>。
 * 线上是成篇文档（每篇 4~23 片），差别才真正显现。
 * <p>
 * <b>这一套测什么、不测什么。</b>同一份多切片语料（线上 9 篇原文切成 92 片），
 * 同一批标注查询，只改<b>融合粒度</b>，把两档数字都打出来：
 * <ul>
 *   <li>文档级：RRF 按 docId 归一——一篇含 13 条规则的文档只能占一个候选位；</li>
 *   <li>切片级：RRF 按 chunkId 归一——那一篇里真正相关的条款可以独立占位。</li>
 * </ul>
 * <p>
 * <b>不设「切片级必须 ≥ 文档级」这条断言——它是错的。</b>实测切片级在 TEXTUAL 档反而低
 * （0.625 vs 0.875），根因不是切片级差，而是文档级的命中<b>来自「整篇被召回」这个假高</b>：
 * 只要那篇文档进了 top3，它内部的任何条款都算命中，于是「整篇召回」把「片内排序是否正确」
 * 这件事整个盖住了。切片级把同一篇的几个切片摆在各自的位置上，反而暴露了真实排序
 * （期望条款在 KB-0005_3，而 top3 给的是 _0/_1/_6）。<b>拿一个假高的数当基线去要求别人不低于它，
 * 就是把度量缺陷固化成验收标准。</b>所以本用例只报告、只在两条真正确定的事实上断言：
 * 语料确实是多切片形态，以及库外问题确实检索不到对应条款。
 * <p>
 * <b>为什么这里不接真实向量。</b>向量路要花 API 费且结果不可复现；本用例的价值是<b>确定性</b>地
 * 量出融合粒度的差异，BM25 路已经足够说明问题（长文档里大量同主题条款，正是关键词路最容易
 * 互相挤位的地方）。真实向量 + 重排的对照见 {@code ChunkingRetrievalComparisonTest}。
 */
class MultiChunkRetrievalTest {

    /** 与线上 {@code Agent.Config.ragTopK(3)} 一致 */
    private static final int TOP_K = 3;

    /** 语料必须是多切片形态，否则这个用例又退化成原夹具 */
    @Test
    void 夹具语料确实是多切片形态() {
        List<DocumentChunk> chunks = MultiChunkFixtures.chunks();

        Map<String, Integer> perDoc = new LinkedHashMap<>();
        for (DocumentChunk chunk : chunks) {
            perDoc.merge(chunk.getDocId(), 1, Integer::sum);
        }

        System.out.printf("%n[多切片夹具] 语料 %d 篇 / %d 片，每篇片数 %s%n",
                MultiChunkFixtures.DOCS.size(), chunks.size(), perDoc.values());

        assertThat(chunks.size())
                .as("多切片夹具的意义全在「每篇不止一片」——退化成一问一答整个用例就白测了")
                .isGreaterThan(MultiChunkFixtures.DOCS.size() * 2);
        // 至少有一篇切出 4 片以上：线上形态是「一篇含十几条规则」，切片级融合才有戏可唱
        assertThat(perDoc.values().stream().mapToInt(Integer::intValue).max().orElse(0))
                .as("至少要有一篇被切成 4 片以上，才谈得上切片级融合")
                .isGreaterThanOrEqualTo(4);
    }

    /**
     * 融合粒度对比 —— <b>U14 的核心数字</b>：同一份索引、同一批查询，只改「谁来当检索单元」。
     * <p>
     * 只报告不断言方向（见类注释）：这一行数字的用途是<b>让「切片级融合作不作数」可被观察</b>，
     * 而不是宣判哪个粒度更好。要判断收益，需要真实向量与重排的对照实验。
     */
    @Test
    void 报告融合粒度对比() {
        List<DocumentChunk> chunks = MultiChunkFixtures.chunks();

        Retriever docLevel = new HybridRetriever(new InMemoryVectorStore(new SimpleEmbeddingService()),
                MultiChunkFixtures.DOCS);
        Retriever chunkLevel = HybridRetriever.overChunks(
                new InMemoryVectorStore(new SimpleEmbeddingService()), chunks, Reranker.NOOP);

        System.out.printf("%n========== 融合粒度对比（多切片语料 %d 篇 / %d 片，topK=%d） ==========%n",
                MultiChunkFixtures.DOCS.size(), chunks.size(), TOP_K);
        System.out.printf("%-14s %8s %8s %8s%n", "分层", "文档级", "切片级", "样本数");

        for (MultiChunkFixtures.Stratum stratum : MultiChunkFixtures.Stratum.values()) {
            List<MultiChunkFixtures.Case> cases = MultiChunkFixtures.casesOf(stratum);
            if (cases.isEmpty()) {
                continue;
            }
            int doc = countHits(docLevel, cases);
            int chunk = countHits(chunkLevel, cases);
            System.out.printf("%-14s %8s %8s %8d%n", stratum,
                    rate(doc, cases.size()), rate(chunk, cases.size()), cases.size());
        }

        // 唯一断言：该答的每一档都必须有样本，否则「三档对比」是个空表——
        // 夹具被改坏时（比如 stratum 名打错），这一条会红，而数字行不会
        for (MultiChunkFixtures.Stratum stratum : List.of(MultiChunkFixtures.Stratum.TEXTUAL,
                MultiChunkFixtures.Stratum.PARAPHRASE, MultiChunkFixtures.Stratum.HARD)) {
            assertThat(MultiChunkFixtures.casesOf(stratum))
                    .as("%s 档必须有样本", stratum).isNotEmpty();
        }
    }

    /**
     * 该拒的样本：库里没有对应条款，正确行为是检索不到。
     * <p>
     * 这一组的价值不是「分数」，而是<b>卡住一个具体的坏形态</b>：
     * 如果多切片语料让无关条款互相挤位、把某个看似相关的切片顶上 topK，
     * 拒答门就拿不到「无依据」这个输入，后面整条零幻觉链都无从谈起。
     */
    @Test
    void 库外问题在多切片语料上仍然检索不到相关条款() {
        List<DocumentChunk> chunks = MultiChunkFixtures.chunks();
        Retriever chunkLevel = HybridRetriever.overChunks(
                new InMemoryVectorStore(new SimpleEmbeddingService()), chunks, Reranker.NOOP);

        List<MultiChunkFixtures.Case> refuse = MultiChunkFixtures.casesOf(MultiChunkFixtures.Stratum.REFUSE);
        assertThat(refuse).as("至少要有一条库外样本").isNotEmpty();

        for (MultiChunkFixtures.Case c : refuse) {
            List<DocumentChunk> retrieved = chunkLevel.retrieve(c.query(), TOP_K);
            System.out.printf("[库外] 「%s」召回 %d 条，前 3 的 docId=%s%n", c.query(), retrieved.size(),
                    retrieved.stream().map(DocumentChunk::getDocId).limit(3).toList());
            // 不硬断言「一条都没有」：中文检索在无关查询上也可能因为共现词召回弱相关切片。
            // 断言的是「没有一条包含标注里那个具体条款」这个确定的事实——
            // 库外样本本来就没有对应条款可命中
            assertThat(retrieved)
                    .as("「%s」在库里没有对应条款，不该有切片包含它", c.query())
                    .noneMatch(chunk -> c.clause() != null && chunk.getContent().contains(c.clause()));
        }
    }

    private static int countHits(Retriever retriever, List<MultiChunkFixtures.Case> cases) {
        int hit = 0;
        for (MultiChunkFixtures.Case c : cases) {
            if (c.clause() == null) {
                continue;
            }
            List<DocumentChunk> retrieved = retriever.retrieve(c.query(), TOP_K);
            // 命中判据与文档边界无关：只看「召回的切片里有没有包含标注条款的那一条」。
            // 用「包含」而不是「相等」，是因为切片会带上位置前缀、也可能把相邻条款一起兜进来
            boolean found = retrieved.stream().anyMatch(chunk ->
                    MultiChunkFixtures.normalize(chunk.getContent()).contains(
                            MultiChunkFixtures.normalize(c.clause())));
            if (found) {
                hit++;
            }
        }
        return hit;
    }

    private static String rate(int hit, int total) {
        return "%.3f".formatted(total == 0 ? 0.0 : (double) hit / total);
    }
}
