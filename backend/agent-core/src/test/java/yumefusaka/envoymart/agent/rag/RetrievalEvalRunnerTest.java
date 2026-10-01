package yumefusaka.envoymart.agent.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 运行器的不变量 —— 它就是报告页的数据源，页面上的每个数字都要能追到这里。
 * <p>
 * 重点钉住两件容易在重构里断掉的事：
 * <ul>
 *   <li><b>分层聚合与单独评测等价</b>：报告页同时显示整体与三档，
 *       如果"从全量逐条切分"与"对该层单独跑一遍"结果不等，页面上就会出现对不上账的两个数字；</li>
 *   <li><b>hit 与 hitRank 互相一致</b>：明细行上的「未命中」标记与排序位次是同一件事的两种写法，
 *       一个说命中了、另一个位次为 0，会让失败样本分析整个失效。</li>
 * </ul>
 */
class RetrievalEvalRunnerTest {

    private final RetrievalEvalRunner runner = new RetrievalEvalRunner();

    @Test
    void 逐条明细覆盖全部样本且分档切分与单独评测等价() {
        RetrievalEvalRunner.EvalRun run = runner.run(RetrievalEvalRunner.TRIGGER_MANUAL);

        assertThat(run.corpus().documents()).isEqualTo(90);
        assertThat(run.corpus().cases()).isEqualTo(120);
        assertThat(run.cases()).hasSize(120);

        // 三档各 40 条，切分的逐条数与聚合的 caseCount 都要对上
        assertThat(run.strata()).hasSize(3);
        int summed = 0;
        for (RetrievalEvalRunner.StratumReport stratum : run.strata()) {
            assertThat(stratum.metrics().caseCount()).isEqualTo(40);
            summed += stratum.metrics().caseCount();

            // 等价性：对该层单独跑一遍评测，数字必须与从全量切分出来的完全相同
            EvalFixtures.Stratum source = EvalFixtures.strata().stream()
                    .filter(s -> s.key().equals(stratum.key()))
                    .findFirst().orElseThrow();
            Retriever retriever = new HybridRetriever(
                    new InMemoryVectorStore(new SimpleEmbeddingService()), EvalFixtures.DOCS);
            RetrievalEvaluator.EvalReport direct =
                    new RetrievalEvaluator().evaluate(retriever, source.cases(), 3);

            assertThat(stratum.metrics().hitRate())
                    .isEqualTo(direct.hitRate());
            assertThat(stratum.metrics().mrr()).isEqualTo(direct.mrr());
            assertThat(stratum.metrics().ndcg()).isEqualTo(direct.ndcg());
        }
        assertThat(summed).isEqualTo(run.overallAt3().caseCount());
    }

    @Test
    void 命中标记与排序位次互相一致() {
        RetrievalEvalRunner.EvalRun run = runner.run(RetrievalEvalRunner.TRIGGER_MANUAL);

        for (RetrievalEvalRunner.CaseReport c : run.cases()) {
            // hit ⇔ hitRank 落位在 1..topK 之内，且命中的 docId 真的在该位次上
            assertThat(c.hit()).isEqualTo(c.hitRank() > 0);
            if (c.hit()) {
                assertThat(c.hitRank()).isBetween(1, run.overallAt3().topK());
                assertThat(c.relevantDocIds()).contains(c.retrievedDocIds().get(c.hitRank() - 1));
            }
            assertThat(c.retrievedDocIds()).hasSizeLessThanOrEqualTo(run.overallAt3().topK());
            assertThat(c.stratum()).isIn("LEXICAL", "PARAPHRASE", "HARD");
        }
    }

    @Test
    void 全量指标落在夹具的已知区间且远高于随机基线() {
        RetrievalEvalRunner.EvalRun run = runner.run(RetrievalEvalRunner.TRIGGER_STARTUP);

        // 夹具与检索管线未变时实测 0.633 / 0.565 / 0.572；区间放宽容差防的是
        // 「数据被悄悄改了」或「检索路退化」，不是防正常波动（关键词路是确定性的）
        assertThat(run.overallAt3().hitRate()).isCloseTo(0.633, org.assertj.core.data.Offset.offset(0.01));
        assertThat(run.overallAt3().mrr()).isCloseTo(0.565, org.assertj.core.data.Offset.offset(0.01));
        assertThat(run.overallAt3().ndcg()).isCloseTo(0.572, org.assertj.core.data.Offset.offset(0.01));
        assertThat(run.overallAt5().hitRate()).isCloseTo(0.675, org.assertj.core.data.Offset.offset(0.01));

        assertThat(run.baseline().hitRateAt3()).isCloseTo(0.036, org.assertj.core.data.Offset.offset(0.005));
        assertThat(run.baseline().hitRateAt5()).isCloseTo(0.059, org.assertj.core.data.Offset.offset(0.005));
        assertThat(run.overallAt3().hitRate()).isGreaterThan(run.baseline().hitRateAt3());

        assertThat(run.generatedAt()).startsWith("20");
        assertThat(run.trigger()).isEqualTo(RetrievalEvalRunner.TRIGGER_STARTUP);
        assertThat(run.corpus().chunkSize()).isEqualTo(EvalFixtures.CHUNK_SIZE);
    }

    /**
     * 扩写对照栏与 CI 门禁必须是同一件事。
     * <p>
     * 报告页上写着"页面上的数字与 CI 逐位一致"，扩写栏既然上了页面，这条承诺就同样适用。
     * 这里现场按 CI 门禁的构造重算一遍（关键词路 + 预录扩写），与运行器给出的数字比对——
     * 两边哪天分了叉（比如运行器改了融合参数而门禁没跟上），这条会红。
     */
    @Test
    void 扩写对照栏与CI门禁算出同一组数字() {
        RetrievalEvalRunner.EvalRun run = runner.run(RetrievalEvalRunner.TRIGGER_MANUAL);

        assertThat(run.expansion()).isNotNull();
        assertThat(run.expansion().capturedAt())
                .as("夹具的采集时间要能一路传到页面上——没有它，这栏数字会被读成「现在的表现」")
                .isEqualTo(RecordedQueryExpander.CAPTURED_AT)
                .startsWith("20");
        assertThat(run.expansion().model()).isEqualTo(RecordedQueryExpander.MODEL);
        assertThat(run.expansion().recordedQueries()).isEqualTo(RecordedQueryExpander.recordedCount());

        Retriever expanded = new MultiQueryRetriever(new HybridRetriever(
                new InMemoryVectorStore(new SimpleEmbeddingService()), EvalFixtures.DOCS),
                new RecordedQueryExpander());
        RetrievalEvaluator evaluator = new RetrievalEvaluator();
        RetrievalEvaluator.EvalReport direct =
                evaluator.evaluate(expanded, EvalFixtures.allCases(), 3);
        assertThat(run.expansion().overallAt3().hitRate()).isEqualTo(direct.hitRate());

        assertThat(run.expansion().strata()).hasSize(3);
        for (RetrievalEvalRunner.StratumReport stratum : run.expansion().strata()) {
            assertThat(stratum.metrics().caseCount()).isEqualTo(40);
        }

        // 扩写栏的存在理由是「能看出增益」，所以增益本身要成立——
        // 一栏 0.633 → 0.633 的对照挂在页面上，比不挂更糟
        assertThat(run.expansion().overallAt3().hitRate())
                .isGreaterThan(run.overallAt3().hitRate());
    }

    @Test
    void 字面档应显著高于难例档_三档梯度是夹具的设计而不是偶然() {
        RetrievalEvalRunner.EvalRun run = runner.run(RetrievalEvalRunner.TRIGGER_MANUAL);

        var byKey = run.strata().stream()
                .collect(java.util.stream.Collectors.toMap(
                        RetrievalEvalRunner.StratumReport::key, s -> s.metrics()));
        assertThat(byKey.get("LEXICAL").hitRate()).isGreaterThan(byKey.get("PARAPHRASE").hitRate());
        assertThat(byKey.get("PARAPHRASE").hitRate()).isGreaterThan(byKey.get("HARD").hitRate());

        List<RetrievalEvalRunner.CaseReport> hardFailures = run.cases().stream()
                .filter(c -> "HARD".equals(c.stratum()) && !c.hit())
                .toList();
        // 难例档本就该有大量失败——一个都不失败说明夹具被换成了简单样本
        assertThat(hardFailures).isNotEmpty();
    }
}
