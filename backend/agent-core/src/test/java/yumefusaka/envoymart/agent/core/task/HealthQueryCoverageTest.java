package yumefusaka.envoymart.agent.core.task;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 症状类提问的覆盖判据。
 * <p>
 * 这个类只回答一个问题：<b>这一轮该不该去搜商品、到底搜了没有。</b>
 * 它的价值在于把「agent 又说没有」拆成两种完全不同的情况——
 * 「平台真的没有」与「它根本没去查」，而这两者在界面上长得一模一样。
 */
class HealthQueryCoverageTest {

    @Test
    void 症状类提问未检索商品时报警() {
        var verdict = HealthQueryCoverage.check("最近肠道不好，应该吃什么药", List.of("knowledge_search"));

        assertThat(verdict.uncovered())
                .as("问了身体状况却没搜商品——「没有相关商品」这个结论没有依据")
                .isTrue();
    }

    @Test
    void 症状类提问检索了商品时不报警() {
        var verdict = HealthQueryCoverage.check("最近肠道不好，应该吃什么药",
                List.of("knowledge_search", "product_search"));

        assertThat(verdict.uncovered()).isFalse();
        assertThat(verdict.searchedProducts()).isTrue();
    }

    @Test
    void 非症状类提问不判定() {
        var verdict = HealthQueryCoverage.check("我的订单到哪了", List.of("order_query"));

        assertThat(verdict.requiresProductSearch())
                .as("问订单不是症状类提问，不需要强制搜商品")
                .isFalse();
        assertThat(verdict.uncovered()).isFalse();
    }

    @Test
    void 没有工具执行记录时也报警() {
        var verdict = HealthQueryCoverage.check("睡不着该吃什么", List.of());

        assertThat(verdict.uncovered())
                .as("一个工具都没调，更不可能查过商品")
                .isTrue();
    }

    @Test
    void 用户消息为空时不判定() {
        assertThat(HealthQueryCoverage.check(null, List.of()).uncovered()).isFalse();
        assertThat(HealthQueryCoverage.check("  ", List.of()).uncovered()).isFalse();
    }
}
