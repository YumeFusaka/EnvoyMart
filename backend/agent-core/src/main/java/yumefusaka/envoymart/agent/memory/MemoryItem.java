package yumefusaka.envoymart.agent.memory;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/**
 * 记忆条目 —— 可以是用户消息、AI 回复、抽取的事实或总结。
 */
@Data
@Builder
public class MemoryItem {
    private String id;

    /**
     * 归属用户 —— 长期记忆的隔离维度。
     * <p>
     * 短期窗口按 {@link #sessionId} 隔离（会话内上下文，本就该随会话消失）；
     * 长期记忆按 userId 隔离（跨会话，这才叫"长期"）。
     */
    private String userId;

    private String sessionId;
    private String content;
    private Type type;

    /**
     * 写入时刻。
     * <p>
     * 必须在召回时原样带回来 —— 曾经召回路径重新 builder 时不传该字段，
     * 于是所有历史条目在读出时时间戳都变成了"此刻"，任何基于新鲜度的策略都失去依据，
     * 而且不会报错，只会静默按错误前提计算。
     */
    @Builder.Default
    private Instant timestamp = Instant.now();

    /**
     * 这条记忆对用户有多重要 —— 容量压力下先淘汰低分的那条。
     * <p>
     * <b>为什么需要它，而不是只靠 {@link Type} 分层。</b>类型分层只分得出「偏好 vs 事件」，
     * 分不出同样两条事件里哪条更该留：「用户是学生党、预算有限」与「用户问过衬衫尺码」
     * 都是事件，前者会影响后面每一次推荐，后者答完就没用了，而原实现按入库顺序淘汰，
     * 谁先进谁先走——留下哪条纯属运气。
     * <p>
     * <b>打分必须确定、可解释、可复现。</b>用模型给记忆打重要性分看着更"聪明"，
     * 实际上会让淘汰行为不可复现（同一份数据两次运行淘汰不同的条目），
     * 于是「为什么这条记忆丢了」这个问题永远答不出来。这里用
     * {@link #score()} 这条固定公式，每一项都能在代码里指出来。
     * <p>
     * {@code 0} 表示未标注，按公式自身给分——不是"不重要"。
     * 缺省必须有确定含义，否则所有历史条目都会被当成低价值挤出去。
     */
    @Builder.Default
    private int importance = 0;

    /** 重要性的上限。打分公式与显式标注共用同一个刻度，比较才有意义 */
    public static final int MAX_IMPORTANCE = 100;

    /**
     * 综合分：显式标注优先，未标注时按内容特征推导。
     * <p>
     * 未标注时的三项依据，每项都对应一个「这条记忆会不会改变后续回答」的判断：
     * <ul>
     *   <li><b>类型</b>：偏好与事实是抽取出来的结论，对话原文（{@code MESSAGE}）
     *       只是过程记录——同样容量下先留结论；</li>
     *   <li><b>长度</b>：过短的内容（「好的」「谢谢」）几乎没有信息量，
     *       它是记忆噪声，留着只会占召回带宽；</li>
     *   <li><b>是否含具体约束词</b>（预算、过敏、忌口、不能、必须…）：这类词一旦出现，
     *       这条记忆大概率约束着后续所有推荐与回答，是最该留的一类。</li>
     * </ul>
     * <b>它是启发式，不是真理</b>——所以显式标注一旦给出就完全覆盖它，
     * 让上游有办法纠正算错的分数。
     */
    public int score() {
        if (importance > 0) {
            return Math.min(importance, MAX_IMPORTANCE);
        }
        int score = switch (type == null ? Type.MESSAGE : type) {
            case PREFERENCE -> 60;
            case FACT -> 50;
            case SUMMARY -> 30;
            case MESSAGE -> 20;
        };
        String text = content == null ? "" : content;
        if (text.length() < 6) {
            // 「好的」「嗯」「谢谢」——不是记忆，是噪声
            score -= 15;
        }
        if (CONSTRAINT_HINTS.stream().anyMatch(text::contains)) {
            score += 20;
        }
        return Math.max(0, Math.min(score, MAX_IMPORTANCE));
    }

    /** 出现这些词说明这条记忆约束着后续回答，而不只是一次问答的记录 */
    private static final java.util.List<String> CONSTRAINT_HINTS = java.util.List.of(
            "预算", "过敏", "忌口", "不能", "必须", "禁忌", "禁用", "不吃", "素食",
            "学生", "孕", "哺乳", "慢性", "长期服用", "医生");

    public enum Type {
        MESSAGE,
        FACT,
        SUMMARY,
        PREFERENCE
    }
}
