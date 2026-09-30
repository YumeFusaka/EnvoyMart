package yumefusaka.envoymart.aiservice.llm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import yumefusaka.envoymart.agent.llm.TokenLedger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型单价表：把 token 用量折算成钱。
 * <p>
 * <b>为什么是配置项而不是常量。</b>价格是外部事实，会变，而且不会通知我们。写成常量的话，
 * 涨价那天代码里的数字就悄悄变成了错的——没有人会因此报错，只会有人拿着一个过时的金额
 * 去做决策。放在配置里，改价是一次改配置，不必发版。
 * <p>
 * <b>它算出来的是估价，不是账单。</b>三点差异必须说清楚：① 单价随阶梯用量、
 * 缓存命中、批处理折扣变化，这里只有一档；② 各供应商的计费口径不同（有的按输入输出分开，
 * 有的按调用次数）；③ 汇率与税费不含在内。所以界面上带「≈」，且真实计费以云厂商账单为准。
 * <p>
 * <b>没配到单价的模型不算钱，只算 token。</b>这条是刻意的：拿一个邻近型号的价格去顶替，
 * 会得出一个看起来精确的错误金额，而用户无从分辨。少一个数字比多一个错数字好。
 */
@Slf4j
@Component
@ConfigurationProperties(prefix = "envoymart.llm")
public class ModelPricing {

    /**
     * 模型名 → [输入单价, 输出单价]，单位<b>元 / 千 token</b>。
     * <p>
     * 用 List 而不是对象是为了配置好写（{@code qwen-plus: [0.0008, 0.002]}）；
     * 长度不足或含非正数的条目按「没配单价」处理，不让一条写歪的配置把估算算成负数。
     */
    private Map<String, List<Double>> pricing = new LinkedHashMap<>();

    public Map<String, List<Double>> getPricing() {
        return pricing;
    }

    public void setPricing(Map<String, List<Double>> pricing) {
        this.pricing = pricing == null ? new LinkedHashMap<>() : pricing;
    }

    /**
     * 折算这一轮的总金额（元）。只覆盖<b>配到单价的那些模型</b>，
     * 是否全覆盖由 {@link #unpricedModels} 单独回答——两者不能合成一个返回值，
     * 否则调用方拿到的金额无法判断它是一部分还是全部。
     */
    public double estimateCny(List<TokenLedger.ModelUsage> usages) {
        if (usages == null) {
            return 0;
        }
        double total = 0;
        for (TokenLedger.ModelUsage usage : usages) {
            Price price = priceOf(usage.model());
            if (price != null) {
                total += usage.promptTokens() * price.inputPerK() / 1000.0
                        + usage.completionTokens() * price.outputPerK() / 1000.0;
            }
        }
        return total;
    }

    /** 这一轮里没配单价、因而未计入金额的模型名 */
    public List<String> unpricedModels(List<TokenLedger.ModelUsage> usages) {
        if (usages == null) {
            return List.of();
        }
        return usages.stream()
                .filter(usage -> priceOf(usage.model()) == null)
                .map(TokenLedger.ModelUsage::model)
                .toList();
    }

    private Price priceOf(String model) {
        if (model == null) {
            return null;
        }
        List<Double> entry = pricing.get(model);
        if (entry == null || entry.size() < 2) {
            return null;
        }
        Double input = entry.get(0);
        Double output = entry.get(1);
        if (input == null || output == null || input < 0 || output < 0) {
            // 配歪的一条不该被静默忽略——它会表现为"这个模型明明配了却不计价"，
            // 而排查的人会先去怀疑用量统计
            log.warn("[Pricing] 单价配置不合法 model={} value={}", model, entry);
            return null;
        }
        return new Price(input, output);
    }

    private record Price(double inputPerK, double outputPerK) {
    }
}
