package yumefusaka.envoymart.agent.core.task;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 意图切换判断的契约。
 * <p>
 * 这个判据的两种错法代价不同，测试要把它们分开钉：
 * - <b>该接续却判成切换</b>：用户说「那第二个呢」，系统当成新话题，上下文全丢；
 * - <b>该切换却判成接续</b>：上一件事的语境渗进新话题，模型把两件事缝在一起。
 * <p>
 * 第一类由「短句接续」那几条钉住，第二类由「领域无重叠」那几条钉住。
 * 中间地带（长句、无工具关键词）刻意判**接续**——它更接近「接着抱怨两句」而不是「换件事」。
 */
class IntentSwitchDetectorTest {

    @Test
    void 同一领域的追问算接续() {
        var verdict = IntentSwitchDetector.check("帮我推荐一款蛋白粉", "那第二个多少钱");
        assertThat(verdict.switched()).isFalse();
    }

    @Test
    void 短句无领域关键词算接续() {
        assertThat(IntentSwitchDetector.check("帮我查下订单", "嗯").switched())
                .as("「嗯」不可能是一次话题切换").isFalse();
        assertThat(IntentSwitchDetector.check("帮我查下订单", "好的，谢谢").switched()).isFalse();
    }

    @Test
    void 换到另一个领域算切换() {
        var verdict = IntentSwitchDetector.check("帮我推荐一款蛋白粉", "帮我查下我上个月的订单");
        assertThat(verdict.switched()).isTrue();
        assertThat(verdict.next()).contains("order_query");
    }

    @Test
    void 会话还没有进行中的事项时不报切换() {
        var verdict = IntentSwitchDetector.check(null, "帮我查下订单");
        assertThat(verdict.switched())
                .as("「还没有事项」与「换了一件事」不是同一种语义，不能混报")
                .isFalse();
        assertThat(verdict.detail()).contains("无进行中的事项");
    }

    /**
     * 最容易写错的一处：上一件事没落到任何工具领域（纯咨询），
     * 这一轮来一句长的、指向明确领域的话——那不叫切换，叫「开始办事」。
     */
    @Test
    void 上一事项无工具领域时短句仍按接续处理() {
        assertThat(IntentSwitchDetector.check("什么是维生素D", "嗯嗯").switched()).isFalse();
    }

    @Test
    void 空白消息不判定() {
        assertThat(IntentSwitchDetector.check("帮我推荐蛋白粉", "  ").switched()).isFalse();
    }
}
