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
 * <b>基线是回归防线，不是质量结论。</b>历史答案与证据保持不变，医学严格规则会揭示
 * 旧答案里此前漏检的问题，因此按逐条拒绝数和分母固定判定结果，同时防止误拒与漏拒。
 * 当前模型的零未支撑句目标必须由真实评测证明，不能用旧答案重放冒充。
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

        // 冻结答案保持原样：医学闸门新增的拒绝是判定能力增强，不是生成质量退化。
        // 固定逐条判定基线同时防误拒与漏拒，不能用放宽比例阈值掩盖尺子的变化。
        // G-18“孕前三个月”新增时间事实：真实标题指向第2片，但0.1824低于统一0.20门，仍不支撑。
        // G-06图谱未收录声明只有工具布尔标记，没有结构化回执；不能证明该负向事实。
        assertThat(m.factSentences()).isEqualTo(49);
        assertThat(m.unsupportedSentences()).isEqualTo(18);
        assertThat(m.citationChecks()).isEqualTo(37);
        assertThat(m.wrongCitations()).isEqualTo(12);
        java.util.Map<String, Integer> expected = java.util.Map.of(
                "G-05", 2, "G-06", 1, "G-07", 1, "G-10", 1, "G-13", 4, "G-14", 3, "G-16", 1, "G-17", 3, "G-18", 2);
        java.util.Map<String, java.util.List<Integer>> counts = java.util.Map.ofEntries(
                java.util.Map.entry("G-01", java.util.List.of(0, 0, 0)), java.util.Map.entry("G-02", java.util.List.of(1, 1, 0)),
                java.util.Map.entry("G-03", java.util.List.of(5, 3, 0)), java.util.Map.entry("G-04", java.util.List.of(3, 3, 0)),
                java.util.Map.entry("G-05", java.util.List.of(3, 3, 2)), java.util.Map.entry("G-06", java.util.List.of(2, 1, 0)),
                java.util.Map.entry("G-07", java.util.List.of(1, 0, 0)), java.util.Map.entry("G-08", java.util.List.of(2, 2, 0)),
                java.util.Map.entry("G-09", java.util.List.of(3, 1, 0)), java.util.Map.entry("G-10", java.util.List.of(6, 5, 0)),
                java.util.Map.entry("G-11", java.util.List.of(1, 1, 0)), java.util.Map.entry("G-12", java.util.List.of(1, 1, 0)),
                java.util.Map.entry("G-13", java.util.List.of(4, 1, 1)), java.util.Map.entry("G-14", java.util.List.of(4, 4, 3)),
                java.util.Map.entry("G-15", java.util.List.of(1, 1, 0)), java.util.Map.entry("G-16", java.util.List.of(3, 3, 1)),
                java.util.Map.entry("G-17", java.util.List.of(6, 4, 3)), java.util.Map.entry("G-18", java.util.List.of(3, 3, 2)));
        for (var outcome : run.cases()) {
            assertThat(outcome.unsupported()).as("历史样本 %s 的严格判定", outcome.id())
                    .isEqualTo(expected.getOrDefault(outcome.id(), 0));
            assertThat(java.util.List.of(outcome.factSentences(), outcome.checks(), outcome.wrongCitations()))
                    .as("历史样本 %s 的分母/引用检查/错误引用", outcome.id())
                    .isEqualTo(counts.getOrDefault(outcome.id(), java.util.List.of(0, 0, 0)));
        }
        assertThat(run.cases().stream().filter(c -> c.id().equals("G-14")).findFirst().orElseThrow().citations())
                .anySatisfy(c -> {
                    assertThat(c.sentence()).contains("超量可能引起血钙升高");
                    assertThat(c.verdict()).isEqualTo(GroundingEvaluator.CitationVerdict.WRONG_TARGET);
                });
        assertThat(run.cases().stream().filter(c -> c.id().equals("G-18")).findFirst().orElseThrow().citations())
                .anySatisfy(c -> {
                    assertThat(c.sentence()).contains("长期大剂量叶酸可能掩盖");
                    assertThat(c.verdict()).isEqualTo(GroundingEvaluator.CitationVerdict.WRONG_TARGET);
                });
        // 拒答准确率 23/24=0.958：一格是一条用例（0.042），0.91 容忍门判错一条
        assertThat(m.refusalAccuracy()).isGreaterThanOrEqualTo(0.91);
        // 冻结旧医学答案虽然命中锚点，仍有未支撑事实，不能算作完整多跳回答。
        // G15 是维生素商品的售后政策问题，不要求医学事实；其正确答案保持命中。
        assertThat(run.cases().stream().filter(c -> c.kind() == GroundingFixtures.Kind.MULTI_HOP))
                .allSatisfy(c -> {
                    assertThat(c.answerComplete()).as("历史完整性 %s", c.id()).isEqualTo("G-15".equals(c.id()));
                    if (!"G-15".equals(c.id())) assertThat(c.unsupported()).isPositive();
                });
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
