package yumefusaka.envoymart.agent.rag;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 检索评测的共享夹具 —— 语料与标注样本的单一来源。
 * <p>
 * 数据在 {@code resources/eval/retrieval-fixtures.json}，放在 main 而不是测试目录，
 * 因为它现在有<b>两类消费者</b>：CI 回归测试（{@code RetrievalQualityTest}）与
 * 报告页的现场重跑（{@code RetrievalEvalRunner}）。两处各持一份数据的话，
 * "页面上的分数"与"CI 里的分数"迟早对不上，而且没人知道该信哪边。
 * <p>
 * <b>规模与结构</b>：90 篇语料、120 条标注查询，按三档分层（各 40 条）。
 * <p>
 * <b>这批样本的定位是回归防线，不是质量结论，读数字时必须带上这些前提：</b>
 * <ul>
 *   <li>语料与标注<b>出自同一作者</b>。三档的难度梯度由作者构造，字面档的高分是设计出来的，
 *       不是能力证明——它的作用是确认"链路没坏"，不是"检索很强"。</li>
 *   <li>样本同时用于开发、对照实验与 CI 门禁，<b>没有留出集</b>。缓解这一点的不是再加样本，
 *       而是：本项目未针对这批样本做过参数调优（无 BM25 权重、RRF 系数或 topK 的搜索），
 *       因此不存在"调到样本上去"的过拟合路径。真实的多标注者一致性校验仍然缺席。</li>
 *   <li>它与线上知识库（{@code AiAgentConfig.knowledgeDocuments()}）是两套独立数据，
 *       规模与主题分布接近但内容不重合。两组指标各自描述各自的语料，<b>不可互相推算</b>。</li>
 * </ul>
 * <p>
 * <b>语料为什么按主题成簇</b>：真实知识库里一个主题下有多篇相互竞争的文档，
 * 而不是一问对一答。本夹具刻意让「售后」「物流」「营销」等主题各占十篇上下，
 * 使同一条查询的 top-3 里出现多篇语义相邻的候选——这既是检索的真实难点，
 * 也让"命中"不再是唯一候选下的必然结果。
 */
public final class EvalFixtures {

    /** 一个分层：键、展示名、该层的样本。展示名给报告页用，测试不看它 */
    public record Stratum(String key, String label, List<RetrievalEvaluator.EvalCase> cases) {
    }

    /** 切片参数 —— 与线上 {@code AiAgentConfig} 的 SimpleRAGEngine 保持一致，避免评测与生产走不同切片 */
    public static final int CHUNK_SIZE;
    public static final int CHUNK_OVERLAP;

    public static final List<Document> DOCS;

    /** 字面重合：查询与文档用词高度一致，关键词检索应该稳拿。 */
    public static final List<RetrievalEvaluator.EvalCase> LEXICAL_CASES;
    /** 口语化改写：与文档几乎无字面重合，纯关键词检索会明显掉分。 */
    public static final List<RetrievalEvaluator.EvalCase> PARAPHRASE_CASES;
    /** 语义鸿沟：词汇与语义都远，关键词路的天然短板。 */
    public static final List<RetrievalEvaluator.EvalCase> HARD_CASES;

    /** 分层按固定顺序：报告页的图例与表格顺序随数据变的话，每次刷新都在跳 */
    private static final List<Stratum> STRATA;

    static {
        Map<String, Object> raw = load();
        CHUNK_SIZE = ((Number) raw.get("chunkSize")).intValue();
        CHUNK_OVERLAP = ((Number) raw.get("chunkOverlap")).intValue();

        DOCS = ((List<?>) raw.get("documents")).stream()
                .map(EvalFixtures::toDocument)
                .toList();

        Map<String, List<RetrievalEvaluator.EvalCase>> byKey = new LinkedHashMap<>();
        STRATA = ((List<?>) raw.get("strata")).stream()
                .map(EvalFixtures::toStratum)
                .peek(stratum -> byKey.put(stratum.key(), stratum.cases()))
                .toList();

        LEXICAL_CASES = byKey.get("LEXICAL");
        PARAPHRASE_CASES = byKey.get("PARAPHRASE");
        HARD_CASES = byKey.get("HARD");
    }

    private EvalFixtures() {
    }

    public static List<Stratum> strata() {
        return STRATA;
    }

    public static List<RetrievalEvaluator.EvalCase> allCases() {
        return STRATA.stream().flatMap(stratum -> stratum.cases().stream()).toList();
    }

    /**
     * 随机检索的 Hit Rate@K 基线 —— 用于判断实测值是否只是"碰巧"。
     * <p>
     * 单篇相关文档时，随机取 K 篇至少命中一篇的概率是 {@code 1 - C(n-1, K)/C(n, K)}。
     * <b>必须逐例按各自的相关文档数算再取平均</b>：本夹具里有若干样本标了 2 篇相关文档
     * （如「优惠券能和满减一起用吗」对应 coupon 与 promotion），套用单文档公式会低估基线。
     * <p>
     * （早先这里的注释把公式写成了 {@code 1 - C(n-K,K)/C(n,K)}，代入 30/3 得 0.28，
     * 与代码实际返回的 0.1 对不上——代码是对的，注释是错的。注释里的推导错误比代码错误更危险，
     * 因为它会让后来者按错误前提去推理。）
     */
    public static double randomBaselineHitRate(int corpusSize, int topK) {
        return randomBaselineHitRate(corpusSize, topK, allCases());
    }

    public static double randomBaselineHitRate(int corpusSize, int topK,
                                               List<RetrievalEvaluator.EvalCase> cases) {
        if (cases.isEmpty()) {
            return 0.0;
        }
        double sum = 0;
        for (RetrievalEvaluator.EvalCase evalCase : cases) {
            int relevant = Math.max(1, evalCase.relevantDocIds().size());
            sum += hitProbability(corpusSize, topK, relevant);
        }
        return sum / cases.size();
    }

    /** 随机取 topK 篇，至少命中一篇相关文档的概率。 */
    private static double hitProbability(int corpusSize, int topK, int relevant) {
        if (relevant >= corpusSize) {
            return 1.0;
        }
        // 全部落空的概率 = 从 非相关 里取出 topK 篇 / 从全量里取出 topK 篇
        double miss = 1.0;
        for (int i = 0; i < topK; i++) {
            miss *= (double) (corpusSize - relevant - i) / (corpusSize - i);
            if (miss <= 0) {
                return 1.0;
            }
        }
        return 1.0 - miss;
    }

    private static Map<String, Object> load() {
        try (InputStream in = EvalFixtures.class.getResourceAsStream("/eval/retrieval-fixtures.json")) {
            if (in == null) {
                // 资产缺失是构建问题而不是运行问题——与其让报告页显示一个空评测，
                // 不如在类加载时就喊出来
                throw new IllegalStateException("缺少评测夹具资源 /eval/retrieval-fixtures.json");
            }
            return new ObjectMapper().readValue(in, new TypeReference<Map<String, Object>>() {
            });
        } catch (java.io.IOException e) {
            throw new IllegalStateException("评测夹具读取失败", e);
        }
    }

    private static Document toDocument(Object raw) {
        Map<?, ?> map = (Map<?, ?>) raw;
        return Document.builder()
                .id((String) map.get("id"))
                .title((String) map.get("title"))
                .content((String) map.get("content"))
                .tags(((List<?>) map.get("tags")).stream().map(String::valueOf).toList())
                .build();
    }

    private static Stratum toStratum(Object raw) {
        Map<?, ?> map = (Map<?, ?>) raw;
        List<RetrievalEvaluator.EvalCase> cases = ((List<?>) map.get("cases")).stream()
                .map(EvalFixtures::toCase)
                .toList();
        return new Stratum((String) map.get("key"), (String) map.get("label"), cases);
    }

    private static RetrievalEvaluator.EvalCase toCase(Object raw) {
        Map<?, ?> map = (Map<?, ?>) raw;
        List<String> relevant = ((List<?>) map.get("relevantDocIds")).stream()
                .map(String::valueOf)
                .toList();
        return new RetrievalEvaluator.EvalCase((String) map.get("query"), relevant);
    }
}
