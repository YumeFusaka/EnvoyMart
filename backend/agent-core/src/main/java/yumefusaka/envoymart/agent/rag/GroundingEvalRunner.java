package yumefusaka.envoymart.agent.rag;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 回答质量评测的<b>离线重放</b>入口 —— CI 门禁与报告页读的是它。
 * <p>
 * 它把夹具里的真实输出喂给 {@link GroundingEvaluator}，不碰模型、不碰知识库、不碰网络。
 * 因此它可以在 CI 里跑、可以逐位复现，也可以被管理员随时重跑而不产生任何费用。
 * <p>
 * <b>为什么不顺手也在这里调模型：</b>那会把门禁变成"有时过有时不过"的东西——
 * 模型是概率系统，同一份输入两次运行分数不同，一条会自己变红的门禁只会被绕过。
 * 真实的模型质量走另一条路（ai-service 的 live 评测），两条路的指标同名同算法，
 * 来源不同：一条答"判定逻辑有没有退化"，另一条答"现在的模型和知识库表现如何"。
 */
public final class GroundingEvalRunner {

    /** 快照来源。与检索评测的 TRIGGER_* 同义：报告页要显示"这份数字是怎么来的" */
    public static final String TRIGGER_STARTUP = "STARTUP";
    public static final String TRIGGER_MANUAL = "MANUAL";

    /**
     * 一次评测的完整结果。
     *
     * @param capturedAt 夹具的采集时间 —— <b>必须跟着数字一起展示</b>。
     *                   一份 2026-09 采集的答案配着 2026-10 的日期，会被读成"现在的表现"
     * @param model      采集时的对话模型标识
     * @param metrics    四项指标
     * @param questions  id → 问题原文。<b>判定结果里没有这个问题</b>（判定层不需要它），
     *                   但报告页的逐条明细需要——只给一个 G-01，读者没法核对这一条在问什么
     * @param cases      逐条明细，报告页据此展开
     */
    public record EvalRun(String generatedAt, String trigger, String capturedAt, String model,
                          GroundingEvaluator.Metrics metrics, Map<String, String> questions,
                          List<GroundingEvaluator.CaseOutcome> cases) {
    }

    public EvalRun run(String trigger) {
        List<GroundingEvaluator.Sample> samples = GroundingFixtures.CASES.stream()
                .map(GroundingEvalRunner::toSample)
                .toList();
        GroundingEvaluator.Report report = GroundingEvaluator.evaluate(samples);
        Map<String, String> questions = GroundingFixtures.CASES.stream()
                .collect(Collectors.toMap(GroundingFixtures.Case::id, GroundingFixtures.Case::question));
        return new EvalRun(OffsetDateTime.now().toString(), trigger, GroundingFixtures.CAPTURED_AT,
                GroundingFixtures.MODEL, report.metrics(), questions,
                report.cases().stream().sorted(GroundingEvaluator.byId()).toList());
    }

    private static GroundingEvaluator.Sample toSample(GroundingFixtures.Case fixtureCase) {
        return new GroundingEvaluator.Sample(fixtureCase.id(), fixtureCase.kind(),
                fixtureCase.expectRefuse(), fixtureCase.mustMention(), fixtureCase.evidence(),
                fixtureCase.answer(), fixtureCase.evidenceLevel(), fixtureCase.toolEvidence());
    }
}
