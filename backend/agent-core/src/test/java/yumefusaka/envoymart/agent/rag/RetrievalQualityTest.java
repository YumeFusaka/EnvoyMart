package yumefusaka.envoymart.agent.rag;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 检索质量回归 —— 用标注样本锁住召回与排序，防止改动把效果悄悄改坏。
 * <p>
 * 语料与样本见 {@link EvalFixtures}。这里只跑 BM25 路（向量库留空），
 * 因此指标反映的是<b>关键词路的底线</b>，用于防退化；
 * 接入真实向量与重排后的对照见 {@link RetrievalComparisonTest}。
 */
class RetrievalQualityTest {

    private static final int TOP_K = 3;

    /**
     * 各档的召回增益下限（条数），40 条样本一档。
     * <p>
     * 实测（预录夹具、关键词路）：口语改写 30 → 34（+4），语义鸿沟 14 → 18（+4），
     * 字面重合 39 → 40（+1）。线画在实测值下方留 1~2 条余量，因为关键词路全程确定性，
     * 没有需要容忍的抖动——余量只为"提示词微调后重采夹具"这类可接受的正常变化留一点空间。
     * <p>
     * <b>增益的绝对值比补入单字通道之前小，是因为基线本身被抬高了</b>：单字通道
     * 把口语档从 0.700 抬到 0.750、语义档从 0.225 抬到 0.350，扩写能再往上加的
     * 空间相应变窄。这里量的是"扩写相对当前基线还有没有增益"，
     * 不是"扩写比历史版本多赚了多少"——后者会随任何检索改进而缩小，不能当门禁。
     * <p>
     * <b>这两条线是整批改动存在与否的判据。</b>角度改写一旦没进到词法路，指标会整段
     * 退回"没有扩写"的那一档，正是它们要拦的事。
     */
    private static final int PARAPHRASE_MIN_GAIN = 3;

    /** 语义鸿沟档的增益下限。这一档原来是 0.225，是所有检索改进里最难动的一块 */
    private static final int HARD_MIN_GAIN = 3;

    @Test
    void 关键词检索在字面重合的查询上表现稳定() {
        RetrievalEvaluator.EvalReport report = evaluate(EvalFixtures.LEXICAL_CASES);

        System.out.println("[检索评测-字面] " + report);

        // 实测 0.975 / 0.883（90 篇语料下不再是满分——同主题的多篇文档开始产生干扰）
        assertThat(report.hitRate()).isGreaterThanOrEqualTo(0.90);
        assertThat(report.mrr()).isGreaterThanOrEqualTo(0.80);
    }

    @Test
    void 口语化改写与语义鸿沟查询会掉分_用于对比引入向量与重排的收益() {
        RetrievalEvaluator.EvalReport paraphrase = evaluate(EvalFixtures.PARAPHRASE_CASES);
        RetrievalEvaluator.EvalReport hard = evaluate(EvalFixtures.HARD_CASES);

        System.out.println("[检索评测-口语] " + paraphrase);
        System.out.println("[检索评测-难例] " + hard);

        // 这组不设高门槛：它的价值是暴露关键词检索的短板，不是刷分
        assertThat(paraphrase.caseCount()).isEqualTo(EvalFixtures.PARAPHRASE_CASES.size());
        assertThat(hard.caseCount()).isEqualTo(EvalFixtures.HARD_CASES.size());
    }

    /**
     * 全量指标与回归门槛 —— 文档引用的就是这一组数字。
     * <p>
     * 门槛设在实测值之下留出余量，作用是回归防线：改动把关键词路改坏时报错。
     * 注意这只测了关键词路的底线；接入向量与重排后整体指标会高于此。
     */
    @Test
    void 全量评测指标不低于回归门槛() {
        RetrievalEvaluator.EvalReport report = evaluate(EvalFixtures.allCases());

        System.out.println("[检索评测-全量] " + report);
        System.out.printf("[随机基线] corpus=%d topK=%d hitRate=%.3f%n",
                EvalFixtures.DOCS.size(), TOP_K,
                EvalFixtures.randomBaselineHitRate(EvalFixtures.DOCS.size(), TOP_K));

        // 顺带打一份 @5：面试里常见的对照是"别人的 Hit@5 是多少"，
        // 而 @3 与 @5 不同口径，没有同一份语料上的 @5 数字就没法直接比。
        RetrievalEvaluator.EvalReport at5 = evaluate(EvalFixtures.allCases(), 5);
        System.out.println("[检索评测-全量@5] " + at5);
        System.out.printf("[随机基线@5] corpus=%d topK=5 hitRate=%.3f%n",
                EvalFixtures.DOCS.size(),
                EvalFixtures.randomBaselineHitRate(EvalFixtures.DOCS.size(), 5));

        assertThat(report.caseCount()).isEqualTo(EvalFixtures.allCases().size());
        // 门槛按 90 篇语料 / 120 条样本的实测值（0.633 / 0.565 / 0.572）下留余量设定。
        // 关键词路无外部依赖、结果确定，余量留的是"分词或融合策略改动带来的正常波动"。
        assertThat(report.hitRate()).isGreaterThanOrEqualTo(0.58);
        assertThat(report.mrr()).isGreaterThanOrEqualTo(0.52);
        assertThat(report.ndcg()).isGreaterThanOrEqualTo(0.52);
    }

    /**
     * 查询扩写的回归门禁 —— <b>把"扩写是有用的"这句话钉成一条会失败的断言</b>。
     * <p>
     * 用预录夹具（{@link RecordedQueryExpander}）而非现场调模型：CI 无 Key、不该有网络、
     * 更不该计费，而且真实模型有随机性，同一份代码两次跑出不同数字的断言不是门禁。
     * 夹具是真实模型输出，只是冻结在某一时刻。
     * <p>
     * <b>这里测的是纯词法侧的增益，测不出 HyDE 的。</b>CI 的向量库是伪随机实现
     * （{@code SimpleEmbeddingService} 按 hashCode 播种），假想答案送进去只是一段
     * 无语义的噪声——它真正的收益要连真实向量服务才看得见，那在
     * {@link RetrievalComparisonTest} 里。这条线守的是另一半：<b>换个说法重问一次，
     * 词法路能不能捞到原句捞不到的文档。</b>
     * <p>
     * 顺带说明为什么这一半值得单独守：<b>语义鸿沟档的提升可以完全由词法侧拿到。</b>
     * 用户说「太贵了」，文档写「定价依据」——这不是向量才能跨的鸿沟，把口语翻译成
     * 文档用词就够了，而翻译恰好是模型的强项。真实对照里再叠上向量与重排，两条路各补各的。
     */
    @Test
    void 预录扩写在词法路上带来可复现的召回增益() {
        HybridRetriever base = new HybridRetriever(
                new InMemoryVectorStore(new SimpleEmbeddingService()), EvalFixtures.DOCS);
        MultiQueryRetriever expandedRetriever =
                new MultiQueryRetriever(base, new RecordedQueryExpander());
        RetrievalEvaluator evaluator = new RetrievalEvaluator();

        System.out.println("[扩写门禁] 夹具采集于 " + RecordedQueryExpander.CAPTURED_AT
                + "（模型 " + RecordedQueryExpander.MODEL + "），"
                + "覆盖 " + RecordedQueryExpander.recordedCount() + " 条查询");

        Map<String, Integer> gains = new LinkedHashMap<>();
        for (EvalFixtures.Stratum stratum : EvalFixtures.strata()) {
            RetrievalEvaluator.EvalReport before = evaluator.evaluate(base, stratum.cases(), TOP_K);
            RetrievalEvaluator.EvalReport after = evaluator.evaluate(expandedRetriever, stratum.cases(), TOP_K);
            // 命中条数直接数，不从比率反算——比率是浮点数，反算出来的"条数"会随舍入漂移
            int hitBefore = countHits(evaluator.evaluateEach(base, stratum.cases(), TOP_K));
            int hitAfter = countHits(evaluator.evaluateEach(expandedRetriever, stratum.cases(), TOP_K));
            gains.put(stratum.key(), hitAfter - hitBefore);
            System.out.printf("[扩写门禁] %-6s 扩写前 %.3f → 扩写后 %.3f  （命中 %d → %d，%+d）%n",
                    stratum.label(), before.hitRate(), after.hitRate(),
                    hitBefore, hitAfter, hitAfter - hitBefore);
        }

        // 夹具与样本必须一一对应。样本增删后忘了重新采集，缺的那条会静默地"按原句检索"——
        // 数字只是偏低，看不出任何异常，门禁就在测一个越来越小的子集。
        // 这条断言把"夹具过期"从沉默变成红灯，代价是改样本后要重采一次（本来也该重采）。
        assertThat(EvalFixtures.allCases())
                .as("扩写夹具与评测样本已经对不上，重新采集："
                        + "RUN_EXPANSION_CAPTURE=true mvn -pl agent-core test -Dtest=ExpansionCaptureTest")
                .allMatch(evalCase -> RecordedQueryExpander.covers(evalCase.query()));

        // 门槛按 90 篇语料 / 120 条样本的实测值下留余量设定。这一组的价值全在"有没有增益"——
        // 断言写成恒真（比如只断言不为空）就等于没有门禁。
        assertThat(gains.get("PARAPHRASE"))
                .as("口语改写档是角度改写的用武之地——用户换个说法，文档里就有句子能对上字面。"
                        + "这条线断了意味着角度没进词法路，那正是这块门禁存在的理由")
                .isGreaterThanOrEqualTo(PARAPHRASE_MIN_GAIN);
        assertThat(gains.get("HARD"))
                .as("语义鸿沟档是这批改动最该动的一块：原来的 0.225 说明关键词路几乎捞不动它，"
                        + "而换个说法重问能把「用户的口语」翻译成「文档的用词」")
                .isGreaterThanOrEqualTo(HARD_MIN_GAIN);
        assertThat(gains.get("LEXICAL"))
                .as("字面档本来就近乎满分，扩写不该把它弄坏——这是「多了几路候选」的代价上限")
                .isGreaterThanOrEqualTo(-1);
    }

    private static int countHits(List<RetrievalEvaluator.CaseOutcome> outcomes) {
        return (int) outcomes.stream().filter(RetrievalEvaluator.CaseOutcome::hit).count();
    }

    @Test
    void 重排器可以改变最终排序() {
        Retriever retriever = new HybridRetriever(
                new InMemoryVectorStore(new SimpleEmbeddingService()), EvalFixtures.DOCS,
                // 假重排：把含"发票"的候选顶到第一位，验证重排确实生效
                (query, candidates, topK) -> candidates.stream()
                        .sorted((a, b) -> Boolean.compare(
                                b.getContent().contains("发票"), a.getContent().contains("发票")))
                        .limit(topK)
                        .toList());

        List<DocumentChunk> result = retriever.retrieve("怎么开发票？", TOP_K);

        assertThat(result).isNotEmpty();
        assertThat(result.get(0).getDocId()).isEqualTo("invoice");
    }

    private RetrievalEvaluator.EvalReport evaluate(List<RetrievalEvaluator.EvalCase> cases) {
        return evaluate(cases, TOP_K);
    }

    private RetrievalEvaluator.EvalReport evaluate(List<RetrievalEvaluator.EvalCase> cases, int topK) {
        Retriever retriever = new HybridRetriever(
                new InMemoryVectorStore(new SimpleEmbeddingService()),
                EvalFixtures.DOCS);
        return new RetrievalEvaluator().evaluate(retriever, cases, topK);
    }
}
