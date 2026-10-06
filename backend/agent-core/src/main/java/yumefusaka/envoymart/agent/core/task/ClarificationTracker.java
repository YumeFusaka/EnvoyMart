package yumefusaka.envoymart.agent.core.task;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 多轮澄清的收敛判断 —— 把「问了几轮、拿到了什么条件」从对话历史里挪到一个可核对的载体。
 * <p>
 * <b>要解决的问题是「澄清变成绕圈」。</b>A/C 两批给了模型「症状不明确先问一轮」的能力，
 * 但那份能力只有一句话的约束（prompt 里写着「一轮问不清就再问一轮」），
 * <b>系统手里没有任何账本</b>。后果有两种，实测都会发生：
 * <ul>
 *   <li><b>原地打转</b>：用户答了「腹泻」，下一轮又被问「是便秘还是腹泻」——
 *       因为模型看的是整段历史，分不清哪句是用户已经答过的；</li>
 *   <li><b>条件叠加不覆盖</b>：第二轮用户改口（「其实不是便秘，是腹泻」），
 *       模型把「便秘」和「腹泻」一起当成限制条件，检索出空集。</li>
 * </ul>
 * 两者的修法都不在提示词层：<b>需要一份「已经知道什么、还缺什么」的显式账</b>。
 * 本类就是这份账的算法部分（存储见 {@link SessionContextStore}）。
 * <p>
 * <b>为什么条件按「槽位」而不是按句子存。</b>用户说「有点便秘，最近还胖了」包含两条信息，
 * 而它们更新的槽位不同；按句子存会让「改口」只能整体覆盖，改一条就得把另一条丢掉。
 * 槽位键取「症状 / 人群 / 品类 / 偏好 / 时间」，是这套澄清实际会问到的几类问题。
 * <p>
 * <b>「已确定」的判据是「条件覆盖了这一次的全部候选，且没有互斥项」</b>，不是
 * 「条件多到某个数量」。数量是错觉：问五轮拿到五个同义的说法，仍然选不出商品。
 */
public final class ClarificationTracker {

    /** 槽位键。取值是稳定契约（会写进 context_snapshot 并在 prompt 里出现），不随措辞改 */
    public static final String SLOT_SYMPTOM = "症状";
    public static final String SLOT_POPULATION = "人群";
    public static final String SLOT_CATEGORY = "品类";
    public static final String SLOT_PREFERENCE = "偏好";
    public static final String SLOT_TIME = "时间";

    /**
     * 互斥取值的小表 —— <b>只收那些「同时成立就不可能」的项</b>，因此它必然是短表。
     * <p>
     * 表不追求完备：漏掉一组意味着「改口」要等下一个人发现，而多收一组意味着
     * 两个本来能共存的描述会被判成矛盾（「便秘」和「腹胀」完全可以同时存在，
     * 所以它们在表里，但分在同一个冲突组之外的两个不同组）。
     */
    private static final List<Set<String>> MUTUALLY_EXCLUSIVE = List.of(
            // 排便方向相反，不可能同时是「不通畅」和「太通畅」
            Set.of("便秘", "腹泻"),
            Set.of("便秘", "拉肚子"),
            Set.of("腹泻", "排便困难"),
            // 需求方向相反
            Set.of("增肌", "减脂"),
            Set.of("增重", "减重"),
            // 人群互斥
            Set.of("儿童", "孕妇"),
            Set.of("儿童", "老人")
    );

    /**
     * 澄清进度。
     *
     * @param collected    已收集的条件，槽位 → 取值
     * @param round        已经澄清的轮次
     * @param converged    条件是否已经足以选出候选集
     * @param missing      还缺什么（给下一轮提问用；已收敛时为空）
     * @param corrections  本轮识别出的改口：用户推翻了此前某个取值
     */
    public record Progress(Map<String, String> collected,
                           int round,
                           boolean converged,
                           List<String> missing,
                           List<String> corrections) {

        public Progress {
            collected = collected == null ? Map.of() : Map.copyOf(collected);
            missing = missing == null ? List.of() : List.copyOf(missing);
            corrections = corrections == null ? List.of() : List.copyOf(corrections);
        }
    }

    private ClarificationTracker() {
    }

    /**
     * 推进一轮澄清。
     * <p>
     * <b>新取值覆盖同槽位的旧取值</b>——这就是「改口」的实现：用户说「其实是腹泻」，
     * 症状槽从「便秘」换成「腹泻」，而不是两条并存。这不是猜测，是澄清的语义：
     * 同一个槽位问的是同一件事，用户后一次回答就是最新的事实。
     * <p>
     * <b>互斥项被认出时记进 {@code corrections} 并保留新值。</b>之所以要显式记一笔，
     * 是为了让日志与 prompt 都能看出「这个条件是改过的」——否则改口会被静默吸收，
     * 排查时只看到最终值，不知道中间发生过一次翻转。
     *
     * @param previous 上一轮的进度（首次澄清传 {@code null}）
     * @param message  用户本轮原话
     */
    public static Progress advance(Progress previous, String message) {
        Map<String, String> collected = new java.util.LinkedHashMap<>(
                previous == null ? Map.of() : previous.collected());
        List<String> corrections = new java.util.ArrayList<>();
        String text = message == null ? "" : message;

        for (String slot : List.of(SLOT_SYMPTOM, SLOT_POPULATION, SLOT_CATEGORY, SLOT_PREFERENCE, SLOT_TIME)) {
            String value = extract(slot, text);
            if (value == null) {
                continue;
            }
            String before = collected.get(slot);
            if (before != null && !before.equals(value) && exclusive(before, value)) {
                corrections.add(slot + "：" + before + " → " + value);
            }
            collected.put(slot, value);
        }

        int round = (previous == null ? 0 : previous.round()) + 1;
        List<String> missing = missingSlots(collected);
        // 收敛判据 = 「症状」+「人群或品类」都有值，且这一轮没有出现改口。
        //
        // 不要求把五个槽位都填满：偏好与时间只影响排序，缺了它们仍然能给出候选，
        // 而多问一轮的成本（用户多等一轮、多一次模型调用）明显高于收益。
        //
        // **改口的那一轮一律不收敛。**理由不是形式上的谨慎：刚换过条件的这一轮，
        // 用它去过滤很可能过滤的是旧的候选集，而结论会看起来完全正常。
        // 把这一轮用来检索新条件，下一轮再下结论，代价是多一轮；
        // 反过来的代价是给用户一个基于错症状的推荐。
        boolean converged = collected.containsKey(SLOT_SYMPTOM)
                && (collected.containsKey(SLOT_POPULATION) || collected.containsKey(SLOT_CATEGORY))
                && corrections.isEmpty();
        return new Progress(collected, round, converged, missing, corrections);
    }

    /** 还缺哪些槽位。已收敛时返回空 —— 让调用方不必自己推「现在够不够」 */
    private static List<String> missingSlots(Map<String, String> collected) {
        List<String> missing = new java.util.ArrayList<>();
        if (!collected.containsKey(SLOT_SYMPTOM)) {
            missing.add(SLOT_SYMPTOM);
        }
        if (!collected.containsKey(SLOT_POPULATION) && !collected.containsKey(SLOT_CATEGORY)) {
            missing.add(SLOT_POPULATION + "或" + SLOT_CATEGORY);
        }
        return missing;
    }

    /**
     * 从一句话里抽一个槽位的取值。
     * <p>
     * <b>用规则而不是模型</b>：这一步每轮都要跑，而它要判的是「用户这句话里出没出现
     * 某个已知的说法」——这正是字面匹配擅长的。交给模型会多一次每轮计费，
     * 而且结论会随采样浮动，同一句「有点便秘」今天抽得出、明天抽不出。
     * <p>
     * 抽不出时返回 {@code null}（而不是空串）：空串会被当成「用户说了但说的是空」，
     * 那会让改口判断把一个未提及的槽位当成被清空。
     */
    /**
     * 否定标记 —— 出现在候选词前面的这些字，说明用户是在**否认**这件事。
     * <p>
     * 必需的理由是一条实测会犯的错：用户改口说「其实不是便秘，是腹泻」，
     * 字面匹配会先命中「便秘」（它排在前面），于是把「便秘」当成这一轮的新取值 ——
     * <b>用户的否认被读成了确认</b>，而这正是改口识别要防的反面。
     * <p>
     * 只往前看一小段（{@value #NEGATION_LOOKBACK} 字）：中文里否定标记紧贴被否定的词
     * （「不是便秘」「没有便秘」「不便秘」），隔得远的「不是」是针对另一件事的。
     */
    private static final Set<Character> NEGATION_CHARS = Set.of('不', '没', '无', '别', '非');

    private static final int NEGATION_LOOKBACK = 4;

    private static String extract(String slot, String text) {
        if (text.isBlank()) {
            return null;
        }
        for (String candidate : CANDIDATES.getOrDefault(slot, Set.of())) {
            int index = text.indexOf(candidate);
            if (index < 0) {
                continue;
            }
            if (negated(text, index)) {
                // 否认的候选不参与取值；继续看后面的候选（「不是便秘，是腹泻」→ 腹泻）
                continue;
            }
            return candidate;
        }
        return null;
    }

    private static boolean negated(String text, int candidateIndex) {
        int from = Math.max(0, candidateIndex - NEGATION_LOOKBACK);
        for (int i = from; i < candidateIndex; i++) {
            if (NEGATION_CHARS.contains(text.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static boolean exclusive(String a, String b) {
        for (Set<String> group : MUTUALLY_EXCLUSIVE) {
            if (group.contains(a) && group.contains(b)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 槽位 → 可识别的说法。用 {@link LinkedHashSet} 而不是 {@code Set.of}：
     * 迭代顺序决定「一句话里同时出现两个候选」时留下哪一个，而顺序必须稳定可预测
     * （「有点便秘又有点腹泻」取先出现的那个，由字面顺序决定，不由哈希决定）。
     */
    private static final Map<String, Set<String>> CANDIDATES = Map.of(
            SLOT_SYMPTOM, new LinkedHashSet<>(List.of(
                    "便秘", "排便困难", "腹泻", "拉肚子", "腹胀", "胀气", "消化不良",
                    "失眠", "睡不着", "乏力", "疲劳", "免疫力低", "贫血", "缺钙", "缺铁", "缺锌")),
            SLOT_POPULATION, new LinkedHashSet<>(List.of(
                    "儿童", "小孩", "宝宝", "孕妇", "哺乳期", "老人", "老年人", "青少年", "成人")),
            SLOT_CATEGORY, new LinkedHashSet<>(List.of(
                    "益生菌", "膳食纤维", "蛋白粉", "维生素", "钙片", "鱼油", "软胶囊", "口服液", "咀嚼片")),
            SLOT_PREFERENCE, new LinkedHashSet<>(List.of(
                    "便宜", "性价比", "进口", "国产", "无糖", "不含糖", "素食", "好吸收", "小包装")),
            // 时间槽刻意**不收「最近」**：它太泛，几乎会命中每一句症状描述，
            // 于是「最近肠道不好」会被同时抽成「症状=便秘」和「时间=最近」，
            // 而「最近」对筛选商品毫无帮助——它只会让 prompt 里多一条噪音条件。
            // 只收**有区分度**的时间说法（持续多久、是否反复），它们才真的改变候选集
            SLOT_TIME, new LinkedHashSet<>(List.of(
                    "这两天", "这几天", "一周左右", "一个月", "长期", "反复", "偶尔", "好几天"))
    );
}
