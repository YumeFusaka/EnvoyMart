package yumefusaka.envoymart.agent.rag;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 切分质量 —— 用长文档语料对比不同切分策略。
 * <p>
 * {@link ChunkingFixtures} 的语料每篇 800~1100 字，远超切分窗口，
 * 因此在它上面能真正区分出策略差异（{@code RetrievalFixtures} 的短文档做不到这件事）。
 * <p>
 * 两个指标：
 * <ul>
 *   <li><b>句子完整性</b>——切片是否以标点收尾。切在句子中途说明语义单元被拦腰截断。</li>
 *   <li><b>语义完整性</b>——18 条用例里，有多少条的必需信息能在<b>单个</b>切片内找齐。
 *       这个判据与检索算法无关：只要没有任何一片含全部必要信息，答案就必然是残的。</li>
 * </ul>
 */
class ChunkingQualityTest {

    /** 句末标点 —— 切片以这些收尾说明没有切在句子中途。 */
    private static final String SENTENCE_ENDS = "。！？；";

    /** 参与对比的切分策略。 */
    private static Map<String, TextSplitter> strategies() {
        Map<String, TextSplitter> strategies = new LinkedHashMap<>();
        strategies.put("定长滑窗 256/32（原实现）", new FixedSizeSplitter(256, 32));
        strategies.put("结构分层 512/40（线上）", new StructuralSplitter(512, 40));
        return strategies;
    }

    @Test
    void 对比_句子完整性与语义完整性() {
        System.out.printf("%n========== 切分策略对比 ==========%n");
        System.out.printf("语料：%d 篇长文档，%d 条语义完整性用例%n%n",
                ChunkingFixtures.LONG_DOCS.size(), ChunkingFixtures.COMPLETENESS_CASES.size());

        System.out.printf("%-28s %7s %11s %11s%n", "策略", "切片数", "句末标点", "语义完整");
        System.out.println("-".repeat(62));

        for (Map.Entry<String, TextSplitter> entry : strategies().entrySet()) {
            Stats stats = measure(entry.getValue());
            System.out.printf("%-28s %7d %10.0f%% %8d/%d%n",
                    entry.getKey(),
                    stats.chunkCount,
                    100.0 * (stats.chunkCount - stats.brokenEnding) / Math.max(1, stats.chunkCount),
                    stats.intactCases,
                    stats.totalCases);
            for (String failed : stats.failedCases) {
                System.out.println("        ✗ " + failed);
            }
        }
        System.out.println();

        // 回归门槛：线上策略的切片必须落在标点上，且关键信息不被切散。
        // 门槛按实测值（0 片切在句中、18/18 通过）设定，语义完整性留 1 条余量——
        // 它的作用是「改动把切分改坏时报错」，不是刷分。
        Stats online = measure(new StructuralSplitter(512, 40));
        assertThat(online.brokenEnding)
                .as("结构分层按标点收尾，不该有切片切在句子中途")
                .isZero();
        assertThat(online.intactCases)
                .as("语义完整性用例（同一语义单元的关键信息必须同片）")
                .isGreaterThanOrEqualTo(online.totalCases - 1);
    }

    @Test
    void 切片长度分布对比() {
        System.out.printf("%n========== 切片长度分布 ==========%n%n");

        for (Map.Entry<String, TextSplitter> entry : strategies().entrySet()) {
            System.out.println("【" + entry.getKey() + "】");
            for (Document doc : ChunkingFixtures.LONG_DOCS) {
                List<DocumentChunk> chunks = entry.getValue().split(doc);
                int min = chunks.stream().mapToInt(c -> c.getContent().length()).min().orElse(0);
                int max = chunks.stream().mapToInt(c -> c.getContent().length()).max().orElse(0);
                double avg = chunks.stream().mapToInt(c -> c.getContent().length()).average().orElse(0);
                System.out.printf("  %-18s %5d 字 → %2d 片   最短 %3d / 平均 %4.0f / 最长 %3d%n",
                        doc.getId(), doc.getContent().length(), chunks.size(), min, avg, max);
            }
            System.out.println();
        }

        assertThat(ChunkingFixtures.LONG_DOCS).isNotEmpty();
    }

    // ==================== 度量 ====================

    private record Stats(int chunkCount, int brokenEnding, int intactCases, int totalCases,
                         List<String> failedCases) {
    }

    private Stats measure(TextSplitter splitter) {
        int chunkCount = 0;
        int brokenEnding = 0;
        List<String> failed = new ArrayList<>();

        for (Document doc : ChunkingFixtures.LONG_DOCS) {
            for (DocumentChunk chunk : splitter.split(doc)) {
                chunkCount++;
                String text = chunk.getContent().stripTrailing();
                if (!text.isEmpty() && SENTENCE_ENDS.indexOf(text.charAt(text.length() - 1)) < 0) {
                    brokenEnding++;
                }
            }
        }

        int intact = 0;
        for (ChunkingFixtures.CompletenessCase c : ChunkingFixtures.COMPLETENESS_CASES) {
            List<DocumentChunk> chunks = splitter.split(findDoc(c.docId()));
            boolean ok = chunks.stream().anyMatch(chunk ->
                    c.mustCoexist().stream().allMatch(f -> containsNormalized(chunk.getContent(), f)));
            if (ok) {
                intact++;
            } else {
                failed.add(c.name());
            }
        }

        return new Stats(chunkCount, brokenEnding, intact,
                ChunkingFixtures.COMPLETENESS_CASES.size(), failed);
    }

    /**
     * 比较前去掉所有空白字符。
     * <p>
     * 夹具里的换行只是排版，一个片段跨行并不代表它被切开了——
     * 用原始字符序列比较会把「同一商品\n同一账号」这种正常换行误报成缺陷。
     */
    private static boolean containsNormalized(String content, String fragment) {
        return normalize(content).contains(normalize(fragment));
    }

    private static String normalize(String s) {
        return s.replaceAll("\\s+", "");
    }

    private static Document findDoc(String docId) {
        return ChunkingFixtures.LONG_DOCS.stream()
                .filter(d -> d.getId().equals(docId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("夹具里没有这篇文档：" + docId));
    }
}
