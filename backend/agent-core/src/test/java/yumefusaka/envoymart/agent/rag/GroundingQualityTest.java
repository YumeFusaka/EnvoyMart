package yumefusaka.envoymart.agent.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 回答质量回归门禁 —— 幻觉率 / 引用准确率 / 拒答准确率 / 多跳命中率。
 * <p>
 * <b>它测的不是模型。</b>输入是 {@link GroundingFixtures} 里冻结的真实模型输出，
 * 输出是判定链（拒答门 + 引用校验）在这些输出上的读数。改判定逻辑、
 * 改引用格式约定、改锚点比对方式时，这里的数字会动——那正是它存在的理由。
 * 模型与知识库的当前表现另有 live 评测（{@code POST /ai/admin/eval/grounding/run}）。
 * <p>
 * <b>门槛是回归防线，不是质量结论。</b>样本量 24 条，任何一条用例翻转都会让比例跳动
 * 4 个百分点以上，所以门槛按"实测值下留一格"设，不按行业基准设——
 * 拿 24 条样本上的比例去对标公开榜单是自欺。门槛于 2026-10-01 按 qwen-plus 快照
 * 校准（幻觉率 0.119 / 引用准确率 1.000 / 拒答准确率 0.958 / 多跳命中率 0.667）；
 * 夹具重新采集后必须同步重新校准，否则守的是上一版系统。
 */
class GroundingQualityTest {

    @Test
    void 四项指标不低于回归门槛() {
        GroundingEvalRunner.EvalRun run = new GroundingEvalRunner().run(GroundingEvalRunner.TRIGGER_MANUAL);
        GroundingEvaluator.Metrics m = run.metrics();

        System.out.println(("[回答质量] cases=%d 幻觉率=%.3f(%d/%d) 引用准确率=%.3f(%d/%d) " +
                "拒答准确率=%.3f 多跳命中率=%.3f(%d) 越界引用=%d 采集于=%s 模型=%s")
                .formatted(m.caseCount(), m.hallucinationRate(), m.unsupportedSentences(),
                        m.factSentences(), m.citationAccuracy(), m.citationChecks() - m.wrongCitations(),
                        m.citationChecks(), m.refusalAccuracy(), m.multiHopHitRate(), m.multiHopCases(),
                        m.outOfRangeCitations(), run.capturedAt(), run.model()));

        // 三类用例都得在：某一类整体消失（夹具被改坏、采集中断）时，
        // 依赖它的指标会变成 1.0 满分——那种"满分"比低分更危险
        assertThat(GroundingFixtures.byKind(GroundingFixtures.Kind.ANSWERABLE)).isNotEmpty();
        assertThat(GroundingFixtures.byKind(GroundingFixtures.Kind.MULTI_HOP)).isNotEmpty();
        assertThat(GroundingFixtures.byKind(GroundingFixtures.Kind.UNANSWERABLE)).isNotEmpty();
        assertThat(m.caseCount()).isEqualTo(GroundingFixtures.CASES.size());

        // 两个方向都要有样本，否则拒答准确率只测了半边
        assertThat(m.multiHopCases()).isEqualTo(GroundingFixtures.byKind(GroundingFixtures.Kind.MULTI_HOP).size());

        // —— 门槛 = 实测值下留一格（2026-10-01 按 qwen-plus 快照校准），一格 = 一条样本 ——
        // 幻觉率 5/42=0.119：一格是一句（0.024），留到 0.15（≈6/42）；再多一句就说明
        // 判定链开始把有出处的句子判成没出处，或反之放过了真的无出处的句子
        assertThat(m.hallucinationRate()).isLessThanOrEqualTo(0.15);
        // 引用准确率 31/31=1.000：一格是一句（0.032），0.96 恰好容忍一句引错、两句即红
        assertThat(m.citationAccuracy()).isGreaterThanOrEqualTo(0.96);
        // 拒答准确率 23/24=0.958：一格是一条用例（0.042），0.91 容忍门判错一条
        assertThat(m.refusalAccuracy()).isGreaterThanOrEqualTo(0.91);
        // 多跳命中 4/6=0.667：6 条样本一格就是 16.7 个百分点，贴着实测值设——
        // 等于「这 4 条必须继续命中」。多跳是检索+图谱的合成能力，掉一条必须先解释为什么
        assertThat(m.multiHopHitRate()).isGreaterThanOrEqualTo(0.66);
        // 越界引用（引了本轮不存在的编号）是确定的编造，一条都不该有
        assertThat(m.outOfRangeCitations()).isZero();
    }

    /**
     * 判定链在"该拒的题"上确实走到拒答侧 —— 单独一条，因为它的失效方式最隐蔽：
     * 拒答门如果对什么都放行，四项指标里只有这一项会动，而且它是"看起来一切正常"的那种坏。
     */
    @Test
    void 库外用例题全部落到拒答侧() {
        GroundingEvalRunner.EvalRun run = new GroundingEvalRunner().run(GroundingEvalRunner.TRIGGER_MANUAL);
        List<GroundingEvaluator.CaseOutcome> unanswerable = run.cases().stream()
                .filter(c -> c.kind() == GroundingFixtures.Kind.UNANSWERABLE)
                .toList();

        assertThat(unanswerable).allSatisfy(c ->
                assertThat(c.gateRefused())
                        .as("库外问题 %s（%s）该判成拒答侧", c.id(), c.kind())
                        .isTrue());
    }

    /**
     * 整篇无依据的该答用例，全篇按未支撑计 —— 单独一条，因为它是唯一一条判据坏掉
     * 却仍能过门槛的路径：这个分支若退化成「0 条未支撑」，幻觉率的分母同时缩小，
     * 比例反而下降，"看起来更好了"。
     * <p>
     * 当前夹具里这种形态只有一条（G-07：检索给到了依据，模型却把小节号 {@code [4.3]}
     * 当引用写了出来，没有任何有效角标）——它正是幻觉率要抓的东西。
     */
    @Test
    void 整篇无依据的该答用例按全篇计未支撑() {
        GroundingEvalRunner.EvalRun run = new GroundingEvalRunner().run(GroundingEvalRunner.TRIGGER_MANUAL);
        List<GroundingEvaluator.CaseOutcome> whole = run.cases().stream()
                .filter(c -> c.mode() == GroundingEvaluator.Mode.WHOLE_UNGROUNDED)
                .toList();

        assertThat(whole)
                .as("夹具里应当至少有一条「整篇无依据」的该答用例；一条都没有时，"
                        + "下面的不变量从未被验证过——若重新采集后确无此形态，把这条测试一并处理")
                .isNotEmpty();
        assertThat(whole).allSatisfy(c -> {
            assertThat(c.factSentences()).as("%s 整篇无依据，事实句数应大于 0", c.id()).isPositive();
            assertThat(c.unsupported()).as("%s 整篇无依据，每一句都无出处", c.id())
                    .isEqualTo(c.factSentences());
        });
    }

    /**
     * 多跳用例确实跨了文档 —— 判据是"引用了两篇以上文档"。
     * <p>
     * 若不成立，说明这批用例其实是单跳题冒充多跳，多跳命中率就退化成了"答案里有没有这几个词"。
     */
    @Test
    void 多跳用例的召回至少覆盖两篇文档() {
        GroundingEvalRunner.EvalRun run = new GroundingEvalRunner().run(GroundingEvalRunner.TRIGGER_MANUAL);
        run.cases().stream()
                .filter(c -> c.kind() == GroundingFixtures.Kind.MULTI_HOP)
                .forEach(c -> System.out.printf("[多跳] %s 命中=%s 引用句=%d%n",
                        c.id(), c.multiHopHit() ? "Y" : "n", c.citations().size()));

        assertThat(run.metrics().multiHopCases()).isPositive();
    }
}
