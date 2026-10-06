package yumefusaka.envoymart.agent.core.task;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 会话上下文隔离的判断 —— 这一轮该不该接上一件事。
 * <p>
 * <b>它要回答的问题只有一个</b>：用户这句新话，是在<b>接着</b>刚才那件事说，
 * 还是<b>换了一件事</b>？前者要带上文的意图与步骤（「那第二个呢」才落得到具体对象上），
 * 后者必须<b>清干净</b>——否则上一件事的语境会渗进新话题，模型会把两件事缝在一起。
 * <p>
 * <b>为什么是规则而不是再调一次模型。</b>判据可以由工具语义直接算出来（见
 * {@link IntentDriftDetector} 里那份工具名 → 意图关键词的映射），而多一次模型调用
 * 意味着每轮多一次计费、多一次可失败点，且结论会跟着采样方差浮动——
 * 同一句「那第二个呢」今天判继承、明天判切换。这里要的是一条每天读到都是同一个数的尺子。
 * <p>
 * <b>漏判的代价是不对称的。</b>该切换而判成继承，上下文串味；该继承而判成切换，
 * 用户得把那件事再说一遍。前者更坏，所以规则设计成<b>宁可判切换</b>：
 * 只有在明显没有语义重叠时才清空。
 * <p>
 * <b>但「没有任何工具相关」不等于「换了话题」。</b>这是最容易写错的一处：
 * 「嗯」「好的」「那第二个呢」这类话一个工具关键词都不沾，却是纯粹的接续。
 * 所以这里有一条明确的例外——<b>短句且不含新领域名词时不判切换</b>，
 * 交给指代消解与对话历史去接。反过来，「帮我查下订单」这类同时命中了另一个工具领域的话，
 * 即便很短也判切换。
 */
public final class IntentSwitchDetector {

    /**
     * 判定结果。
     *
     * @param switched 是否判定为换了话题
     * @param next     新话题归属的工具领域关键词（未判定切换时为空）
     * @param detail   人类可读的说明，进日志
     */
    public record Verdict(boolean switched, List<String> next, String detail) {
    }

    /**
     * 短句阈值。中文字符数（去掉空白后）不超过它、且不含任何新领域关键词时，
     * 一律当作「接着上一句说」。
     * <p>
     * 取 8 是个经验值：它覆盖「嗯」「好的」「那第二个呢」「帮我加两份」「就它了」这类最典型的接续话，
     * 又短于「帮我查一下我的订单」这类明确另起话题的句子。阈值本身不是判据，
     * 只是「没有领域关键词时」的兜底——真正决定判定的永远是领域是否重叠。
     */
    private static final int SHORT_UTTERANCE_CHARS = 8;

    private IntentSwitchDetector() {
    }

    /**
     * @param coreIntent   当前会话正在办的事（首轮意图原文）。空表示会话还没有进行中的事
     * @param userMessage  用户本轮原话
     */
    public static Verdict check(String coreIntent, String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return new Verdict(false, List.of(), "无用户消息，不判定");
        }
        if (coreIntent == null || coreIntent.isBlank()) {
            // 会话还没开始办事：这一轮无论问什么都是「新的一件」，但不该报成「切换」——
            // 切换意味着「有一件正在进行的事被换掉了」，而这里本来就没有。语义差别见
            // Agent 里对 core_intent 的写入时机
            return new Verdict(false, List.of(), "会话无进行中的事项，按新事项建立");
        }

        Set<String> current = domainsOf(coreIntent);
        Set<String> incoming = domainsOf(userMessage);
        if (incoming.isEmpty()) {
            // 沾不到任何工具领域：大概率是「嗯」「那第二个呢」这类纯接续。
            // 只有在**明显是另一个领域的长句**时才会走到澄清，这里直接判继承
            return new Verdict(false, List.of(),
                    "本轮未命中任何工具领域，按接续处理");
        }
        if (current.isEmpty()) {
            // 上一件事当初就没落到任何工具领域上（纯闲聊、纯知识问答），
            // 这时「领域不重叠」不构成切换的证据，只有新句子确实指向另一个领域时才换
            boolean shorter = stripped(userMessage).length() <= SHORT_UTTERANCE_CHARS;
            if (shorter) {
                return new Verdict(false, List.of(), "上一事项无工具领域且本轮为短句，按接续处理");
            }
            return new Verdict(true, List.copyOf(incoming),
                    "上一事项无工具领域，本轮明确指向 " + incoming);
        }

        Set<String> overlap = new LinkedHashSet<>(current);
        overlap.retainAll(incoming);
        if (!overlap.isEmpty()) {
            return new Verdict(false, List.copyOf(overlap),
                    "本轮与当前事项领域重叠: " + overlap);
        }

        // 领域完全不重叠，且本轮确有一个明确的新领域 —— 这才是切换。
        // 注意这里**不看句子长短**：短句同样可能是另一个领域（「帮我查订单」只有 6 个字）
        // 而领域重叠的判据已经足够把它证伪了
        return new Verdict(true, List.copyOf(incoming),
                "本轮领域 " + incoming + " 与当前事项 " + current + " 无重叠");
    }

    /**
     * 一句话落到了哪几个工具领域上。
     * <p>
     * 复用 {@link IntentDriftDetector.ToolSemantics} 的关键词表而不是另起一份：
     * 同一个语义写在两处迟早会分叉（本项目反复踩过，见 13b/13f 的经验）。
     */
    private static Set<String> domainsOf(String text) {
        String lower = text.toLowerCase();
        Set<String> domains = new LinkedHashSet<>();
        for (var entry : IntentDriftDetector.keywordTable().entrySet()) {
            for (String keyword : entry.getValue()) {
                if (lower.contains(keyword)) {
                    domains.add(entry.getKey());
                    break;
                }
            }
        }
        return domains;
    }

    private static String stripped(String text) {
        return text.replaceAll("\\s", "");
    }
}
