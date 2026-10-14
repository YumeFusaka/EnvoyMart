package yumefusaka.envoymart.agent.memory;

import java.util.List;
import java.util.Optional;

/**
 * 记忆存储接口 —— 不区分短期/长期，由实现决定持久化策略。
 */
public interface Memory {

    void add(MemoryItem item);

    List<MemoryItem> recent(String sessionId, int limit);

    /**
     * 语义召回：按与 query 的相关性返回<b>该用户</b>的记忆条目。
     * <p>
     * userId 是必填而非可选：记忆检索和知识检索的性质不同——知识库是"找相关内容"，
     * 记忆是"找对这个用户成立的事实"。少了这个维度，检索出来的东西属于谁都不知道。
     * <p>
     * 默认不支持（返回空），由接入向量库的实现覆写。
     */
    default List<MemoryItem> recall(String userId, String query, int limit) {
        return List.of();
    }

    /**
     * 丢弃窗口里最后一轮（用户消息 + 它对应的助手答复）。
     * <p>
     * <b>为什么需要它，而不是「再 add 一次」。</b>「重新生成」在界面上是**原地替换**，
     * 落历史的 {@code chat:hist:} 也是就地改写；但会话窗口是另一条写入路径、每轮无条件追加，
     * 于是同一个问题在窗口里留下**两条带不同答案的记录**。模型看到的上下文里，
     * 两个「自己」说了相反的话——实测因此产出一段**编造的忏悔**：
     * 「我上一轮的回答有问题，我并没有真正检索到这些条款」（而知识库确实有那些条款）。
     * <p>
     * <b>为什么是「丢」而不是「替换成新的一轮」。</b>调用时机在<b>生成之前</b>——
     * 新答复那一刻还不存在。只追加不丢弃时，模型读到的历史最后两条正是
     * 「用户问了这句话 → 我答过一版」，于是它把本轮当成「同一个问题又问一次」，
     * 转而点评自己上一版。丢掉之后，本轮对模型就是一次干净的首问，
     * 新答复在生成结束后按正常路径追加，窗口形状与 {@code chat:hist:} 一致。
     * <p>
     * <b>默认不支持</b>：没有丢弃能力的载体（内存实现、测试替身）行为不变，
     * 与从前逐位一致；有能力的载体（Redis）覆写为「弹出头部两条」。
     * @return true 表示确实丢掉了完整一轮；false 表示窗口里凑不齐一轮（调用方无需处理）
     */
    default boolean dropLastTurn(String sessionId) {
        return false;
    }

    Optional<MemoryItem> findById(String id);

    void clear(String userId);
}
