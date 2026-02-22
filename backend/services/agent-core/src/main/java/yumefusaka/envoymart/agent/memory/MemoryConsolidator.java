package yumefusaka.envoymart.agent.memory;

import java.util.List;

/**
 * 记忆固化器 —— 从短期记忆中提取有价值的信息存入长期记忆。
 * <p>
 * 典型策略：对话结束后将用户偏好、关键事实抽取为 FACT/SUMMARY 类型条目。
 */
public interface MemoryConsolidator {

    List<MemoryItem> extract(String sessionId, List<MemoryItem> recentMessages);
}
