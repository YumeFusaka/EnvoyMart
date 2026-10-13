package yumefusaka.envoymart.agent.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GroundingInteractionBoundaryTest {
    @Test
    void KG05完整商品名称与已验证SPU身份使用同一独立契约() {
        var found = yumefusaka.envoymart.agent.llm.ToolExecution.builder().tool("interaction_check").success(true)
                .rawData(java.util.Map.of("available", true, "items", List.of(
                        java.util.Map.of("input", "磷虾油软胶囊", "label", "磷虾油软胶囊", "found", true),
                        java.util.Map.of("input", "阿司匹林", "label", "阿司匹林", "found", true)))).build();
        var fixture = GraphMultiHopFixtures.CASES.stream().filter(item -> item.id().equals("KG-05")).findFirst().orElseThrow();
        var sample = new GroundingEvaluator.Sample(fixture.id(), GroundingFixtures.Kind.MULTI_HOP, fixture.expectRefuse(),
                fixture.mustMention(), List.of(), "磷虾油与阿司匹林的具体关系，无法根据本次图谱检查判断，请咨询医师。",
                EvidenceGate.Level.NONE, true, List.of(), true, fixture.question(), false, List.of(found));
        var outcome = GroundingEvaluator.evaluate(List.of(sample)).cases().getFirst();
        assertThat(outcome.answerComplete()).isTrue();
        assertThat(outcome.multiHopHit()).isFalse();

        for (var invalid : List.of(yumefusaka.envoymart.agent.llm.ToolExecution.builder().tool("interaction_check").success(true).rawData(java.util.Map.of("available", true, "items", List.of(
                java.util.Map.of("input", "鱼油软胶囊", "label", "磷虾油软胶囊", "found", true),
                java.util.Map.of("input", "阿司匹林", "found", true)))).build(),
                yumefusaka.envoymart.agent.llm.ToolExecution.builder().tool("interaction_check").success(true).rawData(java.util.Map.of("available", true)).build())) {
            var rejected = new GroundingEvaluator.Sample(sample.id(), sample.kind(), sample.expectRefuse(), sample.mustMention(),
                    sample.evidence(), sample.answer(), sample.evidenceLevel(), true, List.of(), true, sample.userMessage(), false,
                    List.of(invalid));
            assertThat(GroundingEvaluator.evaluate(List.of(rejected)).cases().getFirst().answerComplete()).isFalse();
        }
        var failedLatest = new GroundingEvaluator.Sample(sample.id(), sample.kind(), sample.expectRefuse(), sample.mustMention(),
                sample.evidence(), sample.answer(), sample.evidenceLevel(), true, List.of(), true, sample.userMessage(), false,
                List.of(found, yumefusaka.envoymart.agent.llm.ToolExecution.builder().tool("interaction_check").success(false).build()));
        assertThat(GroundingEvaluator.evaluate(List.of(failedLatest)).cases().getFirst().answerComplete()).isFalse();
    }

    private static final List<String> BOUNDARY = List.of("磷虾油", "阿司匹林", "无法判断", "没有收录不等于安全");
    private static final String EXPLICIT = "仅依据本次图谱检查，无法判断磷虾油与阿司匹林的具体相互作用。"
            + "图谱没有收录磷虾油；没有收录不等于安全。";

    @Test
    void KG05对齐当前语料按事实结论判定而不是沿用未收录边界() {
        assertThat(GraphMultiHopFixtures.CASES).hasSize(12);
        var item = GraphMultiHopFixtures.CASES.stream().filter(value -> value.id().equals("KG-05")).findFirst().orElseThrow();
        assertThat(item.question()).isEqualTo("磷虾油和阿司匹林一起用要注意什么？");
        // 语料已入库《磷虾油软胶囊产品说明书》（KB-0049），正确产出是原文事实而非未收录边界
        assertThat(item.expectRefuse()).isFalse();
        assertThat(item.mustMention()).containsExactly("磷虾油", "阿司匹林", "出血风险");
    }

    @Test
    void 图谱边界需要证据门与明确正文同时满足() {
        var accepted = outcome(EXPLICIT, EvidenceGate.Level.WEAK);
        assertThat(accepted.multiHopHit()).isTrue();
        assertThat(accepted.refusalCorrect()).isTrue();
        assertThat(accepted.unsupported()).isZero();

        assertThat(outcome(EXPLICIT, EvidenceGate.Level.SUFFICIENT).multiHopHit()).isFalse();
    }

    @Test
    void 边界词齐全但又声明无依据安全同服不能算多跳边界通过() {
        var rejected = outcome(EXPLICIT + "磷虾油与阿司匹林可以安全同服[1]。", EvidenceGate.Level.WEAK);
        assertThat(rejected.unsupported()).isPositive();
        assertThat(rejected.multiHopHit()).isFalse();
    }

    @Test
    void 其他成分的出血风险不能替代本品无法判断说明() {
        assertThat(outcome("磷虾油和阿司匹林待核对；鱼油与阿司匹林合用可能增加出血风险[1]。",
                EvidenceGate.Level.WEAK).multiHopHit()).isFalse();
    }

    @Test
    void 旧构造器的图谱召回标记不能自动升级为本次结构化检查证明() {
        var sample = new GroundingEvaluator.Sample("KG-05-legacy", GroundingFixtures.Kind.MULTI_HOP,
                true, BOUNDARY, List.of(), EXPLICIT, EvidenceGate.Level.WEAK, true, List.of(), true,
                "磷虾油和阿司匹林一起用要注意什么？");
        assertThat(GroundingEvaluator.evaluate(List.of(sample)).cases().getFirst().multiHopHit()).isFalse();
    }

    private static GroundingEvaluator.CaseOutcome outcome(String answer, EvidenceGate.Level level) {
        var source = DocumentChunk.builder().docId("other-oil").chunkId("other-oil_0")
                .title("鱼油说明书").source("manual").reranked(true).score(0.9)
                .content("鱼油与阿司匹林合用可能增加出血风险。").build();
        var sample = new GroundingEvaluator.Sample("KG-05-boundary", GroundingFixtures.Kind.MULTI_HOP,
                true, BOUNDARY, List.of(source), answer, level, true, List.of(), true,
                "磷虾油和阿司匹林一起用要注意什么？", true);
        return GroundingEvaluator.evaluate(List.of(sample)).cases().getFirst();
    }
}
