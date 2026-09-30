package yumefusaka.envoymart.aiservice.llm;

import org.junit.jupiter.api.Test;
import yumefusaka.envoymart.agent.llm.TokenLedger;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 单价表要守的不是算术，是<b>「不知道」和「零」不能混淆</b>。
 * <p>
 * 一个没配单价的模型如果被算成 0 元，界面上就会出现「本轮 ¥0.0000」——
 * 它读起来是「这一轮免费」，而真相是「这一轮用了什么模型、多少钱，我不知道」。
 * 所以计价与「哪些没计价」必须分成两个答案返回。
 */
class ModelPricingTest {

    private static ModelPricing pricingOf(Map<String, List<Double>> rates) {
        ModelPricing pricing = new ModelPricing();
        pricing.setPricing(rates);
        return pricing;
    }

    private static TokenLedger.ModelUsage usage(String model, long prompt, long completion) {
        return new TokenLedger.ModelUsage(model, prompt, completion);
    }

    @Test
    void 按输入输出两档单价折算() {
        ModelPricing pricing = pricingOf(Map.of("qwen-plus", List.of(0.0008, 0.002)));

        // 1000 输入 = 0.0008 元；500 输出 = 0.002 × 0.5 = 0.001 元
        double cost = pricing.estimateCny(List.of(usage("qwen-plus", 1000, 500)));

        assertThat(cost).isCloseTo(0.0018, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void 多模型各按各的单价相加() {
        ModelPricing pricing = pricingOf(Map.of(
                "qwen-plus", List.of(0.0008, 0.002),
                "gte-rerank-v2", List.of(0.0008, 0.0)));

        double cost = pricing.estimateCny(List.of(
                usage("qwen-plus", 1000, 0),
                usage("gte-rerank-v2", 2000, 0)));

        assertThat(cost).isCloseTo(0.0008 + 0.0016, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void 没配单价的模型既不计钱也被点名() {
        ModelPricing pricing = pricingOf(Map.of("qwen-plus", List.of(0.0008, 0.002)));
        List<TokenLedger.ModelUsage> usages = List.of(
                usage("qwen-plus", 1000, 0),
                usage("某個新模型", 5000, 0));

        assertThat(pricing.estimateCny(usages))
                .as("算不出来就不算，绝不用邻近型号的价格顶替")
                .isCloseTo(0.0008, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(pricing.unpricedModels(usages))
                .as("金额不完整这件事必须能被调用方看到，否则「总价」会被当成完整账单")
                .containsExactly("某個新模型");
    }

    @Test
    void 全部模型都没有单价时金额为零且未计价列表非空() {
        ModelPricing pricing = pricingOf(Map.of());
        List<TokenLedger.ModelUsage> usages = List.of(usage("whatever", 1000, 500));

        assertThat(pricing.estimateCny(usages)).isZero();
        assertThat(pricing.unpricedModels(usages)).hasSize(1);
    }

    @Test
    void 写歪的条目不参与计算() {
        ModelPricing pricing = pricingOf(Map.of(
                // 只写了一个数：当成没配，而不是拿输入价当输出价
                "half-configured", List.of(0.001),
                // 负数：配置错误不该变成负的金额，那会让总价凭空变小
                "negative", List.of(-1.0, 0.002),
                "ok", List.of(0.001, 0.002)));

        List<TokenLedger.ModelUsage> usages = List.of(
                usage("half-configured", 1000, 1000),
                usage("negative", 1000, 1000),
                usage("ok", 1000, 1000));

        assertThat(pricing.estimateCny(usages)).isCloseTo(0.003, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(pricing.unpricedModels(usages)).containsExactly("half-configured", "negative");
    }
}
