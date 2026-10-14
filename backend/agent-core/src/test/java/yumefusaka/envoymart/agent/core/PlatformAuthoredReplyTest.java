package yumefusaka.envoymart.agent.core;

import org.junit.jupiter.api.Test;
import yumefusaka.envoymart.agent.llm.ToolExecution;
import yumefusaka.envoymart.agent.rag.CitationVerifier;
import yumefusaka.envoymart.agent.tool.ToolResult;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 平台自写文案的两条保证 —— <b>不误标「无依据」、不被自己的门禁吃掉</b>。
 * <p>
 * 这两条守的是同一族缺陷的两个面，实测各出现过一次，且都由「这轮正文是谁写的」
 * 这个前提没被下游看见造成：
 * <ul>
 *   <li>确认中断轮报 {@code source="approval"}，正文是代码里的常量；</li>
 *   <li>确认执行轮报 {@code source="approved"}，正文是工具回执的逐字拼接；</li>
 *   <li>原批次恢复报 {@code source="approval-recovery"}；</li>
 *   <li>确定性流程报 {@code source="flow"}，正文由预定义工具序列产出；</li>
 *   <li>用户照口令回了一句、现场没有载荷时报 {@code source="approval-idle"}，正文是代码里的常量。</li>
 * </ul>
 * 这四种都没有「模型凭自身知识生成」的成分，但下游若不认识它们，就会按
 * 「没有引用、没有工具记录」处理——挂「本轮没有平台依据」横幅，
 * 甚至把平台自己写的确认提示当成无出处断言删成空白气泡（实测 3 例）。
 */
class PlatformAuthoredReplyTest {

    /** 审批中断轮的那两句话，逐字取自 Agent 的中断出口 */
    private static final String APPROVAL_REPLY = "这个操作不可撤销，需要你确认后才会执行。"
            + "确认无误请点击「确认执行」，或直接回复这四个字。";

    @Test
    void 五种平台来源都被认定为有依据() {
        assertThat(Agent.PLATFORM_AUTHORED_SOURCES)
                .as("集合本身就是判据：新增一种平台来源时，落点只有这里一处")
                .containsExactlyInAnyOrder("flow", "approval", "approved", "approval-recovery", "approval-idle");
    }

    @Test
    void 模型生成的来源不在平台来源集合里() {
        assertThat(Agent.PLATFORM_AUTHORED_SOURCES)
                .as("plan/react 的正文出自模型，把它们算成平台自写等于给幻觉开了后门")
                .doesNotContain("plan", "react", "fallback");
    }

    @Test
    void 审批话术不会被引用校验当成无出处断言剔除() {
        // 探针结论的固化：这四种组合下 unsupported 必须为空、stripped 必须为 false。
        // 「回复文本被自己的门禁吃掉」是这条链上最不该出现的形态——
        // 用户点确认之前看到的是一句平台保证，它不该需要「出处」。
        for (int evidenceCount : List.of(0, 3)) {
            for (boolean hasToolEvidence : List.of(false, true)) {
                CitationVerifier.Verdict verdict = CitationVerifier.verify(
                        APPROVAL_REPLY, evidenceCount, hasToolEvidence, Set.of(), null, Set.of());
                assertThat(verdict.unsupported())
                        .as("evidenceCount=%d hasToolEvidence=%s", evidenceCount, hasToolEvidence)
                        .isEmpty();
                assertThat(verdict.stripped())
                        .as("evidenceCount=%d hasToolEvidence=%s", evidenceCount, hasToolEvidence)
                        .isFalse();
                assertThat(verdict.reply()).isEqualTo(APPROVAL_REPLY);
            }
        }
    }

    @Test
    void 平台来源的回复不再被标成整篇无依据() {
        // toolBacked 的集合补齐之前，hasToolEvidence 只能靠 toolExecutions 判断，
        // 而确认轮与恢复轮压根不过执行图、结构上没有工具记录——
        // 于是刚执行完用户批准的操作，界面却告诉他「本轮没有平台依据」。
        for (String source : Agent.PLATFORM_AUTHORED_SOURCES) {
            assertThat(hasToolEvidence(source, null))
                    .as("source=%s 的正文是平台自己写的，没有工具记录也是有依据", source)
                    .isTrue();
        }
    }

    @Test
    void 模型来源且没有工具记录时仍按无依据处理() {
        // 另一半不能一起放宽：plan 轮的正文出自模型，没有工具记录时
        // 「无依据」正是该说的话。
        assertThat(hasToolEvidence("plan", null)).isFalse();
        assertThat(hasToolEvidence(null, null)).isFalse();
    }

    @Test
    void 任何来源只要确有工具记录就认定为有依据() {
        ToolExecution execution = ToolExecution.builder().tool("order_query").success(true).build();
        assertThat(hasToolEvidence("plan", List.of(execution))).isTrue();
    }

    @Test
    void 确认轮用户可见正文取工具的用户可读版本() {
        // 确认轮的失败/成功回执被逐字拼成用户看到的消息，这条路径不经过模型。
        // 工具输出里的「提醒用户：」「务必提醒」是对模型说的话，不该出现在这里——
        // 实测用户收到过 11 次（7 次加购 + 4 次下单）。
        ToolExecution execution = ToolExecution.builder()
                .tool("cart_add").success(true)
                .output("已加入购物车：褪黑素缓释片 × 2，小计 198.00 元。提醒用户：加入购物车还没有下单，需要结算并支付才算买到。")
                .userFacingText("已加入购物车：褪黑素缓释片 × 2，小计 198.00 元。还没下单，需要结算并支付才算买到。")
                .operationState(ToolResult.OperationState.COMMITTED)
                .build();

        String reply = Agent.renderApproved(List.of(execution));

        assertThat(reply).contains("已取得本批操作的权威回执", "198.00", "还没下单")
                .doesNotContain("提醒用户");
    }

    @Test
    void 未提供用户可读版本时回退到工具输出() {
        // 绝大多数工具的 output 本来就是纯事实，两个读者看到的一样——
        // 缺省即相同，不必让每个工具都写两遍
        ToolExecution execution = ToolExecution.builder()
                .tool("order_cancel").success(true)
                .output("订单 12 已取消，库存已回补。")
                .operationState(ToolResult.OperationState.COMMITTED)
                .build();

        assertThat(Agent.renderApproved(List.of(execution))).contains("订单 12 已取消，库存已回补。");
    }

    /** 与 {@code Agent#groundResponse} 里那段判据逐字同构 */
    private static boolean hasToolEvidence(String source, List<ToolExecution> executions) {
        return (source != null && Agent.PLATFORM_AUTHORED_SOURCES.contains(source))
                || (executions != null && !executions.isEmpty());
    }
}
