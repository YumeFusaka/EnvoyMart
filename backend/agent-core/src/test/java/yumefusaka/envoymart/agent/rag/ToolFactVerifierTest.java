package yumefusaka.envoymart.agent.rag;

import org.junit.jupiter.api.Test;
import yumefusaka.envoymart.agent.llm.ToolExecution;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 事实核对的两条线：<b>该抓的抓得住，不该碰的一条也别碰。</b>
 * <p>
 * 后半句是重点。这道闸删的是回答里的句子，误伤等于平台主动把对的答案改错——
 * 比漏检严重得多，所以「订单状态查询」这类把标签当普通词用的地方，
 * 每条规则都要有用例守着。
 */
class ToolFactVerifierTest {

    private static ToolExecution order(Map<String, String> facts) {
        return ToolExecution.builder()
                .tool("order_query").input("{orderId=12}").output("...").success(true)
                .facts(facts)
                .build();
    }

    /** 一个真实订单该声明的两条事实 */
    private static ToolExecution paidOrder() {
        return order(Map.of("订单状态", "已支付", "应付金额", "¥128.00"));
    }

    @Test
    void 金额写错时剔除该句并报告() {
        ToolFactVerifier.Verdict verdict = ToolFactVerifier.verify(
                "你的订单已经付款成功。应付金额 ¥182.00，请核对。",
                List.of(paidOrder()));

        assertThat(verdict.reply())
                .as("写错的金额不能留在回答里——用户会照着它对账")
                .doesNotContain("182")
                .contains("你的订单已经付款成功");
        assertThat(verdict.mismatches()).hasSize(1);
        assertThat(verdict.mismatches().get(0)).contains("应付金额").contains("¥128.00");
        assertThat(verdict.stripped()).isTrue();
    }

    @Test
    void 金额的等价写法不算矛盾() {
        ToolFactVerifier.Verdict verdict = ToolFactVerifier.verify(
                "这笔订单应付金额是 128 元。",
                List.of(paidOrder()));

        assertThat(verdict.mismatches())
                .as("¥128.00 / 128元 / 128 是同一个数，逐字比会把它们判成矛盾")
                .isEmpty();
    }

    @Test
    void 金额后面跟明细数字不算矛盾() {
        ToolFactVerifier.Verdict verdict = ToolFactVerifier.verify(
                "应付金额 ¥128.00，其中运费 ¥8.00。",
                List.of(paidOrder()));

        assertThat(verdict.mismatches())
                .as("第二、三个数是拆账明细，不是对第一个数的反驳")
                .isEmpty();
    }

    @Test
    void 状态写错时剔除该句并报告() {
        ToolFactVerifier.Verdict verdict = ToolFactVerifier.verify(
                "订单状态：已发货，预计明天送达。",
                List.of(paidOrder()));

        assertThat(verdict.reply()).doesNotContain("已发货");
        assertThat(verdict.mismatches()).hasSize(1);
        assertThat(verdict.stripped()).isTrue();
    }

    @Test
    void 状态嵌在句中且取值在场不算矛盾() {
        ToolFactVerifier.Verdict verdict = ToolFactVerifier.verify(
                "订单状态已经从待支付变成已支付了。",
                List.of(paidOrder()));

        assertThat(verdict.mismatches())
                .as("取值在场就是一致的——不能要求模型按字段格式说话")
                .isEmpty();
    }

    @Test
    void 标签当普通词用时不算断言() {
        ToolFactVerifier.Verdict verdict = ToolFactVerifier.verify(
                "订单状态查询只对本人开放。应付金额说明见结算页。",
                List.of(paidOrder()));

        assertThat(verdict.mismatches())
                .as("标签后面没接连接成分，就没在下断言——这两句都是正确的话")
                .isEmpty();
    }

    @Test
    void 换个说法说错属于已知漏检() {
        ToolFactVerifier.Verdict verdict = ToolFactVerifier.verify(
                "你的订单已经发货了。",
                List.of(paidOrder()));

        assertThat(verdict.mismatches())
                .as("刻意留的漏检口子：模型不提标签时认不出来。见 ToolFactVerifier 类注释")
                .isEmpty();
        assertThat(verdict.reply()).isEqualTo("你的订单已经发货了。");
    }

    @Test
    void 工具没声明事实时回答原样通过() {
        String reply = "每日推荐摄入量是 400IU。";

        ToolFactVerifier.Verdict verdict = ToolFactVerifier.verify(reply, List.of(
                ToolExecution.builder().tool("knowledge_search").success(true).build()));

        assertThat(verdict.reply()).isEqualTo(reply);
        assertThat(verdict.mismatches()).isEmpty();
    }

    @Test
    void 失败的工具不参与事实认定() {
        ToolFactVerifier.Verdict verdict = ToolFactVerifier.verify(
                "应付金额 ¥128.00。",
                List.of(ToolExecution.builder().tool("order_query").success(false)
                        .facts(Map.of("应付金额", "¥999.00")).build()));

        assertThat(verdict.mismatches())
                .as("这次调用根本没成，它没有确立任何事实")
                .isEmpty();
    }

    @Test
    void 同名标签以最后一次调用为准() {
        ToolFactVerifier.Verdict verdict = ToolFactVerifier.verify(
                "取消后订单状态是已取消。",
                List.of(
                        order(Map.of("订单状态", "待支付")),
                        order(Map.of("订单状态", "已取消"))));

        assertThat(verdict.mismatches())
                .as("下单后查一次、取消后再查一次是常态；报的必须是当前那一次")
                .isEmpty();
    }

    @Test
    void 剔除后不留下空标题() {
        // 标题下面只有那一句被剔掉的话——留着它，用户会以为平台「确实有这么一段注意事项」
        ToolFactVerifier.Verdict verdict = ToolFactVerifier.verify(
                "请以实际账单为准。\n⚠️ 注意：\n应付金额 ¥182.00。",
                List.of(paidOrder()));

        assertThat(verdict.reply())
                .doesNotContain("⚠️")
                .contains("请以实际账单为准");
    }
}
