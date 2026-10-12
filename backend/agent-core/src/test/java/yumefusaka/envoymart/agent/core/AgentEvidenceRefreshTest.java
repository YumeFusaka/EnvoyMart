package yumefusaka.envoymart.agent.core;

import org.junit.jupiter.api.Test;
import yumefusaka.envoymart.agent.rag.DocumentChunk;
import yumefusaka.envoymart.agent.rag.EvidenceGate;
import yumefusaka.envoymart.agent.rag.QueryExpansions;
import yumefusaka.envoymart.agent.rag.RetrievalOutcome;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentEvidenceRefreshTest {

    @Test
    void 工具补回更强知识切片后证据门按最终证据重算() {
        Agent.AgentResponse response = Agent.AgentResponse.builder()
                .retrieval(new RetrievalOutcome(
                        List.of(chunk("入口", 0.19)),
                        java.util.Set.of(), false, QueryExpansions.none()))
                .evidenceLevel(EvidenceGate.Level.WEAK)
                .build();

        Agent.refreshEvidenceLevel(response, List.of(
                chunk("入口", 0.19), chunk("工具补回", 0.29)), EvidenceGate.Thresholds.defaults());

        assertThat(response.getEvidenceLevel()).isEqualTo(EvidenceGate.Level.SUFFICIENT);
    }

    @Test
    void 没有入口检索对象的工具轮次也按真实强弱切片更新门() {
        for (double score : List.of(0.19, 0.29)) {
            var response = Agent.AgentResponse.builder().evidenceLevel(EvidenceGate.Level.NONE).build();
            Agent.refreshEvidenceLevel(response, List.of(chunk("工具证据", score)), EvidenceGate.Thresholds.defaults());
            assertThat(response.getEvidenceLevel()).isEqualTo(score < 0.2
                    ? EvidenceGate.Level.WEAK : EvidenceGate.Level.SUFFICIENT);
        }
    }

    private DocumentChunk chunk(String id, double score) {
        return DocumentChunk.builder()
                .chunkId(id).docId(id).content("证据")
                .score(score).reranked(true).build();
    }
}
