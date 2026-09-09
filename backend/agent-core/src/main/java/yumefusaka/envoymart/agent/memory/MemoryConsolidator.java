package yumefusaka.envoymart.agent.memory;

import java.util.List;

/**
 * 记忆固化器 —— 从短期记忆里提取有价值的信息。
 * <p>
 * 输出分两轨，对应两种存储形态与两种检索方式：
 * <ul>
 *   <li>{@link ConsolidationResult#profileEntries()}——能装进固定槽位的画像事实，覆盖式更新、全量注入；</li>
 *   <li>{@link ConsolidationResult#episodes()}——装不进槽位的事件与细节，追加、按需语义召回。</li>
 * </ul>
 * 分轨的理由是二者对存储形态的要求相反：画像要"全量注入"因此必须有界（固定槽位），
 * 情节要"什么都能记"因此必须自由（文本 + 向量）。混在一轨里只能二选一，两个目标都达不到。
 */
public interface MemoryConsolidator {

    ConsolidationResult extract(String userId, List<MemoryItem> recentMessages);

    record ConsolidationResult(List<ProfileEntry> profileEntries, List<MemoryItem> episodes) {

        public static ConsolidationResult empty() {
            return new ConsolidationResult(List.of(), List.of());
        }

        public boolean isEmpty() {
            return profileEntries.isEmpty() && episodes.isEmpty();
        }
    }
}
