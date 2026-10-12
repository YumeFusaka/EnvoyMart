package yumefusaka.envoymart.agent.core;

import org.junit.jupiter.api.Test;
import yumefusaka.envoymart.agent.llm.ToolExecution;
import yumefusaka.envoymart.agent.rag.EvidenceGate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentPartialInteractionBoundaryTest {

    @Test
    void 部分未知不能借另一对象已收录掩盖本品关系无法判断() {
        for (var level : EvidenceGate.Level.values()) {
            String verified = "阿司匹林的已核验条款保留[1]。";
            var response = response(level, List.of(mixed()));

            String answer = Agent.enforceUnknownInteractionRefusal(response, verified, true);

            assertThat(answer).startsWith(verified)
                    .contains("无法判断磷虾油与阿司匹林的具体相互作用", "没有收录不等于安全")
                    .contains("磷虾油软胶囊", "确认")
                    .doesNotContain("磷虾油会增加出血风险");
        }
    }

    @Test
    void 图谱全未知但已有足够文字证据时仅说明图谱范围不抹已核验正文() {
        var tool = report(List.of(Map.of("input", "磷虾油", "found", false),
                Map.of("input", "阿司匹林", "found", false)));
        String verified = "本品说明书明确要求用药前咨询医师[1]。";

        assertThat(Agent.enforceUnknownInteractionRefusal(
                response(EvidenceGate.Level.SUFFICIENT, List.of(tool)), verified, true))
                .startsWith(verified).contains("无法判断", "没有收录不等于安全");
    }

    @Test
    void 最近一次已找到对象不能沿用之前未知结论() {
        var resolved = report(List.of(Map.of("input", "磷虾油软胶囊", "found", true),
                Map.of("input", "阿司匹林", "found", true)));

        assertThat(Agent.enforceUnknownInteractionRefusal(
                response(EvidenceGate.Level.SUFFICIENT, List.of(mixed(), resolved)), "最新核验结论", true))
                .isEqualTo("最新核验结论");
    }

    @Test
    void 最近一次失败或结构不完整不能借旧报告声称图谱未知() {
        for (var latest : List.of(
                ToolExecution.builder().tool("interaction_check").success(false).build(),
                report(List.of(Map.of("input", "磷虾油"), Map.of("input", "阿司匹林", "found", true))))) {
            assertThat(Agent.enforceUnknownInteractionRefusal(
                    response(EvidenceGate.Level.WEAK, List.of(mixed(), latest)), "保留失败说明", true))
                    .isEqualTo("保留失败说明");
        }
    }

    @Test
    void 非医学请求不新增图谱医学边界() {
        assertThat(Agent.enforceUnknownInteractionRefusal(
                response(EvidenceGate.Level.WEAK, List.of(mixed())), "普通请求", false))
                .isEqualTo("普通请求");
    }

    private static Agent.AgentResponse response(EvidenceGate.Level level, List<ToolExecution> tools) {
        return Agent.AgentResponse.builder().evidenceLevel(level).toolExecutions(tools).build();
    }

    private static ToolExecution mixed() {
        return report(List.of(Map.of("input", "磷虾油", "found", false, "nearest", "磷虾油软胶囊"),
                Map.of("input", "阿司匹林", "found", true)));
    }

    private static ToolExecution report(List<Map<String, Object>> items) {
        return ToolExecution.builder().tool("interaction_check").success(true)
                .rawData(Map.of("available", true, "items", items)).build();
    }
}
