package yumefusaka.envoymart.aiservice.eval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import yumefusaka.envoymart.agent.core.Agent;
import yumefusaka.envoymart.agent.llm.ToolExecution;
import yumefusaka.envoymart.agent.rag.EvidenceGate;
import yumefusaka.envoymart.agent.rag.QueryExpansions;
import yumefusaka.envoymart.agent.rag.RetrievalOutcome;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GroundingBoundaryEvidenceWiringTest {
    @TempDir Path directory;

    @Test
    void 成功结构化未知检查即使没有图谱召回切片仍满足本次关系边界契约() throws Exception {
        // KG-05 金标 2026-10-13 对齐为「该答」：结构化未知检查不再直接判定多跳命中，
        // 但它仍驱动当前关系边界契约（answerComplete）——这条断言保护那段接线不退化。
        var outcome = run(List.of(check("磷虾油", true)), false);
        assertThat(outcome.answerComplete()).isTrue();
        assertThat(outcome.multiHopHit()).isFalse();
    }

    @Test
    void 只有图谱切片召回而未做结构化检查不能冒充边界证据() throws Exception {
        assertThat(run(List.of(), true).multiHopHit()).isFalse();
    }

    @Test
    void 最近检查失败不能借早先未知结果或其它图谱切片通过() throws Exception {
        var failed = ToolExecution.builder().tool("interaction_check").success(false).build();
        assertThat(run(List.of(check("磷虾油", true), failed), true).multiHopHit()).isFalse();
    }

    @Test
    void 不可用或检查了别的对象不能证明本场景未收录() throws Exception {
        assertThat(run(List.of(check("磷虾油", false)), true).multiHopHit()).isFalse();
        assertThat(run(List.of(check("其它商品", true)), true).multiHopHit()).isFalse();
    }

    @Test
    void 没有知识依据的成功未知检查不能把生产NONE伪提升为SUFFICIENT() throws Exception {
        var outcome = run(List.of(check("磷虾油", true)), false, EvidenceGate.Level.NONE);
        assertThat(outcome.gateRefused()).isTrue();
        assertThat(outcome.answerComplete()).isTrue();
        assertThat(outcome.multiHopHit()).isFalse();
    }

    private yumefusaka.envoymart.agent.rag.GroundingEvaluator.CaseOutcome run(
            List<ToolExecution> tools, boolean graphRetrieved) throws Exception {
        return run(tools, graphRetrieved, EvidenceGate.Level.WEAK);
    }

    private yumefusaka.envoymart.agent.rag.GroundingEvaluator.CaseOutcome run(
            List<ToolExecution> tools, boolean graphRetrieved, EvidenceGate.Level level) throws Exception {
        var agent = mock(Agent.class);
        var retrieval = new RetrievalOutcome(List.of(), graphRetrieved ? Set.of("other-graph_0") : Set.of(),
                graphRetrieved, QueryExpansions.none());
        var response = Agent.AgentResponse.builder().source("assistant")
                .reply("仅依据本次图谱检查，无法判断磷虾油与阿司匹林的具体相互作用。图谱没有收录磷虾油；没有收录不等于安全。")
                .evidenceLevel(level).toolExecutions(tools).retrieval(retrieval).build();
        when(agent.chat(anyString(), anyString(), anyString(), isNull())).thenReturn(response);
        var mapper = new ObjectMapper();
        Path snapshot = directory.resolve(java.util.UUID.randomUUID().toString());
        var service = new GroundingLiveEvalService(agent, mapper, new EvalSnapshotStore(mapper, snapshot));
        service.start();
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while ((service.current().status().equals("RUNNING")
                || !Files.exists(snapshot.resolve("production-grounding.json"))) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(service.current().status()).isEqualTo("COMPLETED");
        assertThat(service.current().failedCases()).isZero();
        return service.current().cases().stream().map(GroundingLiveEvalService.CaseResult::outcome)
                .filter(item -> item.id().equals("KG-05")).findFirst().orElseThrow();
    }

    private static ToolExecution check(String input, boolean available) {
        return ToolExecution.builder().tool("interaction_check").success(true)
                .rawData(Map.of("available", available, "items", List.of(
                        Map.of("input", input, "found", false), Map.of("input", "阿司匹林", "found", true))))
                .build();
    }
}
