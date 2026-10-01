package yumefusaka.envoymart.agent.rag;

import org.junit.jupiter.api.Test;
import yumefusaka.envoymart.agent.llm.ChatMessage;
import yumefusaka.envoymart.agent.llm.LLMConfig;
import yumefusaka.envoymart.agent.llm.LLMProvider;
import yumefusaka.envoymart.agent.llm.LLMResponse;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 扩写器的边界：<b>模型答歪了只能少扩，不能扩错。</b>
 * <p>
 * 扩写是检索侧第二次模型参与（第一次是指代消解），它出错的表现同样是「悄悄换一批证据」
 * 而不是抛异常。所以这里的断言集中在清洗与降级：非法 JSON、围栏、超长、
 * 编出来的空壳，各自该被怎么处理——尤其是<b>「丢弃不合规的那一项」而不是「整批作废」</b>：
 * 假想答案跑偏时，那两条角度改写可能还是好的。
 */
class LlmQueryExpanderTest {

    private static final LLMConfig CONFIG = LLMConfig.builder().model("stub").build();

    /** 固定返回一段文本（或抛异常）的桩，并记录模型被调用了几次 */
    private static class StubProvider implements LLMProvider {
        private final AtomicInteger calls = new AtomicInteger();
        private final String reply;
        private final RuntimeException boom;
        private final boolean reasoning;

        StubProvider(String reply) {
            this(reply, null, true);
        }

        StubProvider(String reply, RuntimeException boom) {
            this(reply, boom, true);
        }

        StubProvider(String reply, boolean reasoning) {
            this(reply, null, reasoning);
        }

        private StubProvider(String reply, RuntimeException boom, boolean reasoning) {
            this.reply = reply;
            this.boom = boom;
            this.reasoning = reasoning;
        }

        @Override
        public boolean supportsReasoning() {
            return reasoning;
        }

        @Override
        public LLMResponse chat(List<ChatMessage> messages, LLMConfig config) {
            calls.incrementAndGet();
            if (boom != null) {
                throw boom;
            }
            return LLMResponse.builder().content(reply).build();
        }
    }

    private static LlmQueryExpander expander(LLMProvider provider) {
        return new LlmQueryExpander(provider, CONFIG);
    }

    @Test
    void 正常JSON解析出假想答案与角度() {
        QueryExpansions expansions = expander(new StubProvider("""
                {"hypothetical":"深海鱼油与华法林合用时可能增加出血风险，建议在医师指导下调整剂量。",
                 "angles":["鱼油和抗凝药能一起吃吗","华法林相互作用 保健品"]}"""))
                .expand("鱼油和华法林冲突吗");

        assertThat(expansions.hypothetical()).contains("华法林");
        assertThat(expansions.angles()).containsExactly("鱼油和抗凝药能一起吃吗", "华法林相互作用 保健品");
    }

    @Test
    void 带代码块围栏或开场白时仍能抠出JSON() {
        QueryExpansions expansions = expander(new StubProvider("""
                好的，以下是扩写结果：
                ```json
                {"hypothetical":"现货订单 24 小时内出库，华东地区 1 到 2 天送达。","angles":["多久发货","配送时效"]}
                ```"""))
                .expand("什么时候能到");

        assertThat(expansions.hypothetical()).contains("24 小时");
        assertThat(expansions.angles()).hasSize(2);
    }

    @Test
    void 不是JSON时退回没有扩写() {
        assertThat(expander(new StubProvider("我不确定你在问什么。")).expand("随便问问"))
                .as("解析失败不是异常，是一条要降级的正常路径")
                .isEqualTo(QueryExpansions.none());
    }

    @Test
    void 模型抛异常时退回没有扩写() {
        assertThat(expander(new StubProvider(null, new IllegalStateException("超时"))).expand("随便问问"))
                .isEqualTo(QueryExpansions.none());
    }

    @Test
    void 假想答案超长时截断而不是整条丢弃() {
        String long_ = "深海鱼油".repeat(200);
        QueryExpansions expansions = expander(new StubProvider(
                "{\"hypothetical\":\"" + long_ + "\",\"angles\":[\"角度一\"]}"))
                .expand("鱼油");

        assertThat(expansions.hypothetical())
                .as("它从不示人、只用于向量化——一段被截断的同主题文字仍落在该落的地方，"
                        + "而整条丢弃等于白花了这次调用")
                .hasSize(300);
        assertThat(expansions.angles()).containsExactly("角度一");
    }

    @Test
    void 超长的角度被丢弃但不影响其它项() {
        String longAngle = "很长的一句".repeat(50);
        QueryExpansions expansions = expander(new StubProvider(
                "{\"hypothetical\":\"一段资料\",\"angles\":[\"" + longAngle + "\",\"正常角度\"]}"))
                .expand("问题");

        assertThat(expansions.hypothetical()).isEqualTo("一段资料");
        assertThat(expansions.angles())
                .as("一条角度改写超长说明它已经不是「一句查询」了；但它是逐条判断的，"
                        + "不该牵连同批的其它项")
                .containsExactly("正常角度");
    }

    @Test
    void 角度条数超过上限时只取前三条() {
        QueryExpansions expansions = expander(new StubProvider(
                "{\"hypothetical\":\"一段资料\",\"angles\":[\"a1\",\"a2\",\"a3\",\"a4\",\"a5\"]}"))
                .expand("问题");

        assertThat(expansions.angles()).containsExactly("a1", "a2", "a3");
    }

    @Test
    void 模型判断不需要扩写时返回空而不是报错() {
        QueryExpansions expansions = expander(new StubProvider("{\"hypothetical\":\"\",\"angles\":[]}"))
                .expand("订单 20260101 到哪了");

        assertThat(expansions.isEmpty()).isTrue();
    }

    @Test
    void 空查询或不支持推理的模型不产生调用() {
        StubProvider provider = new StubProvider("{\"hypothetical\":\"x\",\"angles\":[]}");
        assertThat(expander(provider).expand("  ").isEmpty()).isTrue();
        assertThat(expander(provider).expand(null).isEmpty()).isTrue();
        assertThat(provider.calls)
                .as("空查询连模型都不该叫——这是一次真实计费的调用")
                .hasValue(0);

        StubProvider mock = new StubProvider("{\"hypothetical\":\"x\",\"angles\":[]}", false);
        assertThat(expander(mock).expand("鱼油和华法林冲突吗").isEmpty()).isTrue();
        assertThat(mock.calls)
                .as("Mock 路径必须与改造前逐位相同：没有 Key 时不该多出任何一次调用")
                .hasValue(0);
    }
}
