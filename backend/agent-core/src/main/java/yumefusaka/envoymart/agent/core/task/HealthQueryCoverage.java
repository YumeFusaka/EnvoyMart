package yumefusaka.envoymart.agent.core.task;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 症状/健康类提问的覆盖检查 —— 用户问了身体不适，这一轮到底有没有去找过商品。
 * <p>
 * <b>为什么需要它。</b>实测失效（2026-10-06，alice「最近肠道不好，应该吃什么药」）：
 * 模型一次 {@code product_search} 都没调，只查了知识库，就回答
 * 「平台知识库里没有查到对应的用药依据」。它没说谎——知识库里确实没有"用药依据"，
 * 但用户问的是"该吃什么"，平台上有对症的商品，它没去找。
 * <p>
 * 提示词里已经写明了「必须先搜商品」（见 {@code Agent#buildSystemPrompt} 的症状类规则），
 * 但<b>提示词是请求，不是保证</b>：模型少调一次工具就能绕过，而绕过的表现
 * 与「平台真的没有」长得一模一样——都只是一段话。所以这里给它一个可观测的信号。
 * <p>
 * <b>只观测，不干预。</b>沿用 {@link IntentDriftDetector} 的同一条取舍：判据是启发式的
 * （靠症状词表），用一个启发式去拦住一次真实回答，代价远大于晚一点在日志里看到它。
 * 这个信号的价值在于：当用户说「agent 又说没有」时，能立刻分辨这是
 * 「真的没有」还是「它没去查」。
 */
public final class HealthQueryCoverage {

    /**
     * 症状与健康诉求词。收到「词表会漏」的边界是刻意的：只收那些<b>明确指向身体状况</b>的说法，
     * 不收「营养」「保健」这类品类词——用户问「有什么保健品」是在找商品，
     * 本来就会走商品检索，不报也不影响。
     */
    private static final Pattern HEALTH_COMPLAINT = Pattern.compile(
            "不舒服|不适|不好|难受|疼|痛|胀|腹泻|拉肚子|便秘|失眠|睡不着|上火|过敏|"
                    + "发烧|发热|咳嗽|感冒|头晕|恶心|呕吐|"
                    + "缺钙|缺铁|缺锌|贫血|免疫力|调理|补肾|护肝|养胃|"
                    + "症状|吃什么药|能不能吃|该吃|忌口|食疗");

    /** 本轮必须出现过的工具：商品检索。没有它，「找不到相关商品」这个结论就没有依据 */
    private static final String PRODUCT_TOOL = "product_search";

    /**
     * @param requiresProductSearch 这是不是一个「该去搜商品」的问题
     * @param searchedProducts      本轮是否执行过商品检索
     * @param detail                人类可读的说明，进日志
     */
    public record Verdict(boolean requiresProductSearch, boolean searchedProducts, String detail) {

        /**
         * 唯一值得报警的形态：问了身体状况、却没搜过商品。
         * <p>
         * 反向的两种都不报：不涉及健康的提问搜没搜商品都正常；涉及健康的提问搜了商品
         * 更是正常。只有这一种组合说明<b>结论是在没查过的情况下下的</b>。
         */
        public boolean uncovered() {
            return requiresProductSearch && !searchedProducts;
        }
    }

    private HealthQueryCoverage() {
    }

    /**
     * @param userMessage 用户本轮原话
     * @param toolNames   本轮实际执行过的工具名，按顺序
     */
    public static Verdict check(String userMessage, List<String> toolNames) {
        if (userMessage == null || userMessage.isBlank()) {
            return new Verdict(false, false, "无用户消息，不判定");
        }
        boolean health = HEALTH_COMPLAINT.matcher(userMessage).find();
        if (!health) {
            return new Verdict(false, false, "非症状类提问，不判定");
        }
        boolean searched = toolNames != null && toolNames.contains(PRODUCT_TOOL);
        return new Verdict(true, searched, searched
                ? "症状类提问，已检索商品"
                : "症状类提问，但本轮未检索商品——「没有相关商品」这类结论缺少依据");
    }

    /** 供测试与文档引用：当前认定的症状词（正则源码） */
    static Set<String> vocabulary() {
        return Set.of("不舒服", "腹泻", "便秘", "症状", "吃什么药", "调理");
    }
}
