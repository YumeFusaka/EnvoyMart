package yumefusaka.envoymart.aiservice.eval;

import org.junit.jupiter.api.Test;
import yumefusaka.envoymart.agent.rag.EvidenceGate;

import static org.assertj.core.api.Assertions.assertThat;

class GroundingLiveEvalServiceTest {

    @Test
    void 低相关证据不能因工具成功而被提升为足够() {
        assertThat(GroundingLiveEvalService.finalEvidenceLevel(EvidenceGate.Level.WEAK, true))
                .isEqualTo(EvidenceGate.Level.WEAK);
    }

    @Test
    void 未检索但工具补回知识证据时才提升为足够() {
        assertThat(GroundingLiveEvalService.finalEvidenceLevel(EvidenceGate.Level.NONE, true))
                .isEqualTo(EvidenceGate.Level.SUFFICIENT);
    }
}
