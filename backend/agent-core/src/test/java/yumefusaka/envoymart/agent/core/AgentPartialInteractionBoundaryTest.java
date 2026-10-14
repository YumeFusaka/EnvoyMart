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
    void 交付了已核验正文的边界说明不把整轮证据门降为弱证据() {
        var response = response(EvidenceGate.Level.SUFFICIENT, List.of(mixed()));

        Agent.enforceUnknownInteractionRefusal(response, "阿司匹林的已核验条款保留[1]。", true);

        // 「图谱没收录某个称呼」是**子结论**，它的适用范围写在它自己的文本里
        //（「仅依据本次图谱检查，无法判断X与Y」），不该升级成对整轮作答能力的否定。
        // 实测代价：正文完整给出了有原文依据的结论，只因末尾追加了这段边界，
        // 整轮 level 被降成 WEAK——评测记为过拒，前端挂上「相关度不足」横幅。
        assertThat(response.getEvidenceLevel()).isEqualTo(EvidenceGate.Level.SUFFICIENT);
    }

    @Test
    void 交付物就是边界拒答本身时证据门必须同步降为弱证据() {
        var response = response(EvidenceGate.Level.SUFFICIENT, List.of(mixed()));

        String boundary = Agent.enforceUnknownInteractionRefusal(response, "", true);
        Agent.enforceUnknownInteractionRefusal(response, boundary, true);

        // 另一半不能一起放宽：这一轮交付的就是这段拒答，没有可作答的内容，
        // level 必须与输出说的是同一件事，否则会出现「输出说无法判断、记账说证据充分」。
        assertThat(boundary).contains("无法判断磷虾油与阿司匹林的具体相互作用");
        assertThat(response.getEvidenceLevel()).isEqualTo(EvidenceGate.Level.WEAK);
    }

    @Test
    void 对象全部收录时不改写结论也不动证据门() {
        var resolved = report(List.of(Map.of("input", "磷虾油软胶囊", "found", true),
                Map.of("input", "阿司匹林", "found", true)));
        var response = response(EvidenceGate.Level.SUFFICIENT, List.of(resolved));

        assertThat(Agent.enforceUnknownInteractionRefusal(response, "最新核验结论", true))
                .isEqualTo("最新核验结论");
        assertThat(response.getEvidenceLevel()).isEqualTo(EvidenceGate.Level.SUFFICIENT);
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

    @Test
    void 探测边界文本不改写整轮证据门() {
        var response = response(EvidenceGate.Level.SUFFICIENT, List.of(mixed()));

        // 发布审核用纯函数探测「边界文本在不在正文里」。它必须是只读的——
        // 从前这里走的是交付入口（传空正文），那条路径的「交付物里没有已核验正文就降级」
        // 判据在空正文下恒成立，于是一次探测把整轮从 SUFFICIENT 降成 WEAK。
        // 实测代价（c091b3b9 真跑 36 题）：4 例过拒全部由此产生，且正文完整答对。
        Agent.InteractionBoundary probe = Agent.interactionBoundary(response);

        assertThat(probe).as("本轮图谱未收录「磷虾油」，应当探到边界文本").isNotNull();
        assertThat(probe.text()).contains("无法判断磷虾油与阿司匹林");
        assertThat(response.getEvidenceLevel())
                .as("探测只读，不得改写证据门——它没有交付任何东西")
                .isEqualTo(EvidenceGate.Level.SUFFICIENT);
    }

    @Test
    void 交付入口仍按有没有已核验正文决定是否降级() {
        // 两条路要一起钉住：探测不降级，交付该降还是要降，否则输出说无法判断、
        // 记账却说证据充分，两边说的不是同一件事
        var withContent = response(EvidenceGate.Level.SUFFICIENT, List.of(mixed()));
        Agent.enforceUnknownInteractionRefusal(withContent, "阿司匹林的已核验条款保留[1]。", true);
        assertThat(withContent.getEvidenceLevel()).isEqualTo(EvidenceGate.Level.SUFFICIENT);

        var boundaryOnly = response(EvidenceGate.Level.SUFFICIENT, List.of(mixed()));
        String delivered = Agent.enforceUnknownInteractionRefusal(boundaryOnly, "", true);
        assertThat(delivered).contains("无法判断磷虾油与阿司匹林");
        assertThat(boundaryOnly.getEvidenceLevel()).isEqualTo(EvidenceGate.Level.WEAK);
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
