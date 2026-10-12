package yumefusaka.envoymart.aiservice.eval;

import org.junit.jupiter.api.Test;
import yumefusaka.envoymart.agent.core.Agent;
import yumefusaka.envoymart.agent.llm.ToolExecution;
import yumefusaka.envoymart.agent.rag.EvidenceGate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GroundingLiveEvalServiceTest {

    @Test
    void 低相关证据不能因工具成功而被提升为足够() {
        for (var level : List.of(EvidenceGate.Level.NONE, EvidenceGate.Level.WEAK, EvidenceGate.Level.SUFFICIENT)) {
            var response = Agent.AgentResponse.builder().evidenceLevel(level)
                    .toolExecutions(List.of(ToolExecution.builder().tool("interaction_check").success(true).build()))
                    .build();
            assertThat(GroundingLiveEvalService.finalEvidenceLevel(response)).isEqualTo(level);
        }
    }

    @Test
    void 未提供生产证据门状态时默认没有知识依据() {
        assertThat(GroundingLiveEvalService.finalEvidenceLevel(Agent.AgentResponse.builder().build()))
                .isEqualTo(EvidenceGate.Level.NONE);
    }
}
