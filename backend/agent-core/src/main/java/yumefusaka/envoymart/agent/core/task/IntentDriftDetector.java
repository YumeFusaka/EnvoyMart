package yumefusaka.envoymart.agent.core.task;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 跑偏检测 —— 拿「冻结的首轮意图」与「实际用到的工具集合」对一次账。
 * <p>
 * <b>它检测的是什么，不是什么。</b>它不判断回答对不对（那是证据门与引用校验的活），
 * 只回答一个更窄的问题：<b>这一轮执行下来，有没有走到一个跟最初目的明显无关的地方。</b>
 * 多轮任务最常见的失败不是某一步算错，而是执行到一半目标被悄悄换掉——
 * 用户问「这单什么时候到」，跑了六轮工具之后答案变成了一张商品推荐单，
 * 每一步都成功，整体是错的。
 * <p>
 * <b>为什么是规则而不是模型判定。</b>判「跑偏」可以调一次模型，但那会让观测本身
 * 成为一次计费调用，而且它的结论会跟着模型方差浮动，同一个轨迹今天判跑偏明天判正常。
 * 这里要的是一条<b>每天读到的都是同一个数</b>的尺子。规则会漏判——它只在
 * 「一个工具都没沾到意图里的关键词」这种明显形态上报警，这正是它该管的部分；
 * 剩下的灰色地带交给日志与人工，不由它硬判。
 * <p>
 * <b>它只观测，不干预。</b>报警不中断执行：跑偏的判据是启发式的，
 * 用一个启发式去掐掉一次真实任务，代价远大于晚一点知道。
 */
public final class IntentDriftDetector {

    /**
     * 冻结意图与执行结果的对照。
     *
     * @param drifted    是否判定为跑偏
     * @param intentHits 意图关键词在工具语义里命中的部分，用于解释「为什么这样判」
     */
    public record Verdict(boolean drifted, List<String> intentHits, String detail) {
    }

    private IntentDriftDetector() {
    }

    /**
     * @param coreIntent 首轮冻结的意图原文（可以为空 —— ReAct 路径与未规划路径没有它）
     * @param toolNames  本轮实际执行过的工具名，按顺序
     */
    public static Verdict check(String coreIntent, List<String> toolNames) {
        if (coreIntent == null || coreIntent.isBlank()
                || toolNames == null || toolNames.isEmpty()) {
            // 判据不足时不报警。**「没有证据」不能当成「没有问题」，也不能当成「有问题」**——
            // 对一个观测信号来说，安静地返回 neutral 比返回一个猜出来的结论有用得多
            return new Verdict(false, List.of(), "无意图或无工具执行，不判定");
        }

        String intent = coreIntent.toLowerCase();
        Set<String> hits = new LinkedHashSet<>();
        List<String> unrelated = new ArrayList<>();

        for (String tool : toolNames) {
            if (tool == null) {
                continue;
            }
            Set<String> keywords = ToolSemantics.keywordsOf(tool);
            if (keywords.isEmpty()) {
                continue;
            }
            boolean hit = keywords.stream().anyMatch(intent::contains);
            if (hit) {
                hits.add(tool);
            } else {
                unrelated.add(tool);
            }
        }

        if (hits.isEmpty()) {
            // 一件意图相关的事都没做：这是最硬的跑偏形态，也最值得报
            return new Verdict(true, List.of(),
                    "本轮执行的全部工具与意图无一处相关: " + unrelated);
        }
        if (unrelated.isEmpty()) {
            return new Verdict(false, List.copyOf(hits), "全部工具都落在意图范围内");
        }
        // 有相关也有无关是**正常形态**：查订单顺手查物流、推荐商品前先搜一下库存。
        // 只有「无关的占了大头」才升级成跑偏——用一个比例而不是「出现即报」，
        // 是因为后者会把绝大多数正常的多步任务都标红，很快就被当成噪音忽略
        boolean mostlyUnrelated = unrelated.size() >= 3 && unrelated.size() > hits.size() * 2;
        return new Verdict(mostlyUnrelated, List.copyOf(hits),
                mostlyUnrelated
                        ? "多数工具与意图无关: 相关=" + hits + " 无关=" + unrelated
                        : "相关=" + hits + " 无关=" + unrelated + "，在正常范围");
    }

    /**
     * 工具名 → 它服务的意图关键词。
     * <p>
     * 收在一处而不是散在检测器里：这份映射既可能被检测器用，也可能被 prompt 组装、
     * 文档生成读到，而<b>同一个语义写在两处迟早会分叉</b>（本项目反复踩过）。
     * 这里用工具名而不是工具描述——描述是给模型看的长文本，会随措辞调整而变，
     * 拿它做判据等于把观测信号绑在一份会被人随手改写的文案上。
     */
    /**
     * 工具领域关键词表 —— 供「跑偏检测」与「意图切换判断」共用。
     * <p>
     * 原本是 {@code ToolSemantics} 的内部常量。开出来是因为
     * {@link IntentSwitchDetector} 要用同一份判据回答「这一轮落到哪个领域」，
     * 而同一个语义写在两处迟早会分叉。
     */
    static java.util.Map<String, Set<String>> keywordTable() {
        return ToolSemantics.KEYWORDS;
    }

    private static final class ToolSemantics {
        private static final java.util.Map<String, Set<String>> KEYWORDS = java.util.Map.of(
                "product_search", Set.of("商品", "推荐", "找", "搜", "买", "价格", "预算", "规格", "shopping"),
                "knowledge_search", Set.of("政策", "规则", "规定", "知识", "售后", "退", "换", "怎么", "能不能"),
                "interaction_check", Set.of("一起", "同服", "相互作用", "冲突", "能不能吃", "禁忌", "服用"),
                "order_query", Set.of("订单", "单", "下单", "付款", "支付", "买了", "订单号"),
                "logistics_query", Set.of("物流", "快递", "到哪", "发货", "运单", "送达", "什么时候到"),
                "order_cancel", Set.of("取消", "退单", "不想要", "撤销")
        );

        static Set<String> keywordsOf(String tool) {
            return KEYWORDS.getOrDefault(tool, Set.of());
        }
    }
}