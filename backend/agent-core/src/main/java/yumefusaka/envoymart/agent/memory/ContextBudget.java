package yumefusaka.envoymart.agent.memory;

import yumefusaka.envoymart.agent.llm.ChatMessage;

import java.util.List;

/**
 * 注入前的 token 上界 —— 上下文管理从「按条数」补齐到「按 token」的那一半。
 * <p>
 * 在这之前，上下文只有条数上限：窗口 16 条、知识 3 片、情节 3 条、画像 6 槽。
 * 条数对一个「每条长度不受控」的输入来说不是上界 —— 用户贴一篇三千字的商品对比进对话，
 * 16 条窗口就是几万 token，而这不会报错（模型的上下文窗口装得下），
 * 只是成本静默上涨：<b>能跑，但账单在涨，而且没人看得见。</b>
 * <p>
 * 这一版只裁历史，不裁系统提示与本轮问题。理由是它们的上界本来就在别处：
 * 知识片段由 {@code StructuralSplitter} 按 512 字切分、画像 6 槽、情节 3 条，
 * 用户消息则是「用户自己打的字」，裁它等于替用户改问题。
 * 而历史是<b>唯一一份长度随对话轮次无界增长、又可以被安全丢弃</b>的输入。
 */
public final class ContextBudget {

    /**
     * 历史对话的 token 上限。
     * <p>
     * 4000 是针对「窗口 16 条」定的：中文一条 60 字的来回约 100 token，
     * 16 条约 1600，正常对话永远碰不到这个上界 —— 它拦的是异常输入，
     * 不是日常对话。真触发了说明有人往对话里贴了长文，那时丢掉最旧的几轮
     * 比把整篇贴文发给模型更划算。
     * <p>
     * 留出的余量：system prompt（画像 + 情节 + 知识）实测约 1000–2000，
     * 工具观测（{@code MAX_TOOL_OUTPUT_CHARS} 截断后）约 800，
     * 输出上限 2048 —— 合起来离模型的上下文窗口还很远。
     */
    public static final int DEFAULT_HISTORY_TOKENS = 4000;

    private ContextBudget() {
    }

    /**
     * 估算一段文本的 token 数。
     * <p>
     * <b>刻意高估。</b>这里没有引入 tokenizer：那是一个几百 KB 的词表加一套 BPE 实现，
     * 而它带来的精度对「该不该丢几轮旧对话」这个判断毫无影响 —— 差 10% 与差 20%
     * 落在同一个决策上。真正要紧的是<b>估算方向</b>：低估会让预算形同虚设
     * （以为没超，其实超了），高估只是提前丢一两轮本可以留下的话。
     * <p>
     * 取值：汉字 1 字记 1 token（实际约 0.6），其余字符每 3 个记 1 token（实际约 0.25/字符）。
     * 于是中文被高估约 1.7 倍，英文约 1.3 倍。
     */
    public static int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int han = 0;
        int other = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) {
                han++;
            } else {
                other++;
            }
        }
        return han + other / 3;
    }

    /** 一批消息的估算总量 */
    public static int estimate(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (ChatMessage message : messages) {
            total += estimate(message.getContent());
        }
        return total;
    }

    /**
     * 把历史裁进预算：<b>从最旧的开始丢</b>。
     * <p>
     * 丢最旧而不是丢最长，是因为对话的指代链是向后的 —— 「那单」「上次那个」
     * 指的一定是更近的某一轮，丢掉最旧的一轮损失最小。
     * 按长度丢会让「用户随口提了一句订单号」那种短而关键的消息活下来，
     * 而把长的那条（往往正是上下文所在）丢掉。
     * <p>
     * <b>至少保留最近一条</b>：只剩一条时不再丢，哪怕它自己就超预算。
     * 那一瞬间裁掉的是「上一轮说了什么」，而多轮对话里这正是最不能丢的一条；
     * 至于它本身就很长——那是用户自己贴的，该由入口的长度校验去管，
     * 不该在这里被静默截断成半句话。
     *
     * @param messages 时间正序的历史（不含本轮）
     * @return 裁剪后的新列表；未超预算时原样返回
     */
    public static List<ChatMessage> fit(List<ChatMessage> messages, int budget) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        int total = estimate(messages);
        if (total <= budget) {
            return messages;
        }
        int from = 0;
        while (from < messages.size() - 1 && total > budget) {
            total -= estimate(messages.get(from).getContent());
            from++;
        }
        return List.copyOf(messages.subList(from, messages.size()));
    }
}
