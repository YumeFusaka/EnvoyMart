package yumefusaka.envoymart.agent.memory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 感知记忆 —— <b>本次交互的即时观测</b>，四类记忆里最后补齐的一块。
 * <p>
 * <b>它和另外三类补的不是同一个洞。</b>短期记忆是「这次聊了什么」（用户与助手说过的话），
 * 长期记忆是「这个用户是谁」（跨会话累积的结论），实体记忆是「他的画像槽位」。
 * 这三类都只承载<b>语言</b>。而用户可能给的是别的东西——上传的说明书图片、
 * 当前打开的商品页、选中的一批商品、页面上的筛选条件。这些是<b>观测</b>，
 * 不是对话内容，塞进短期记忆会被当成人说的话，塞进长期记忆会污染跨会话结论。
 * <p>
 * <b>生命周期刻意做成一轮。</b>感知观测描述的是「此刻他眼前的画面」，
 * 下一轮用户换了页面，上一轮的观测就已经不成立。把它做成跨轮保留，
 * 模型会拿着一个过期的页面上下文回答问题，而它读起来和新鲜的一样可信——
 * 这是比「没有观测」坏得多的情况。<b>所以这里是「本轮写、本轮读、下轮清」</b>，
 * 而不是又一个带容量的记忆池。
 * <p>
 * <b>为什么不复用 {@link MemoryItem}。</b>那个结构的字段（userId / sessionId / type）
 * 全是为「语言记忆」设计的：{@code content} 是一句话，{@code type} 的四个枚举
 * 也都是语言形态（消息/事实/摘要/偏好）。观测有来源与结构，硬塞进去只能靠拼字符串，
 * 而拼进去的字符串最终会被 {@link MemoryConsolidator} 当成用户说过的话去抽取事实。
 * <p>
 * <b>隔离维度是 sessionId。</b>同一会话内多轮共享，换会话即清空——
 * 用 userId 隔离会让 A 会话的观测出现在 B 会话里，而两者可能开着不同的商品页。
 */
public class PerceptualMemory {

    /** 单会话最多保留的观测条数。观测是短生命周期的，超出说明上游在无节制地写 */
    private static final int MAX_OBSERVATIONS = 20;

    /** 单条观测的正文上限。观测是「看到了什么」，不是「看到了全部原文」 */
    private static final int MAX_CONTENT_CHARS = 500;

    private final Map<String, List<Observation>> store = new ConcurrentHashMap<>();

    /** 写入序号。观测的先后由它唯一确定，不依赖时间戳精度 */
    private final java.util.concurrent.atomic.AtomicLong sequence = new java.util.concurrent.atomic.AtomicLong();

    private long nextSequence() {
        return sequence.incrementAndGet();
    }

    /**
     * 记一条本轮观测。
     * <p>
     * <b>同一来源的重复观测覆盖而不是追加。</b>用户在一个页面里点来点去时，
     * 「当前商品页」会在同一轮里被写很多次，而真正有意义的是<b>最后一次</b>的状态。
     * 追加的话模型会拿到一串互相矛盾的历史页面，还得自己判断哪个是现在。
     *
     * @param sessionId 会话 id
     * @param source    观测来源标识，如 {@code product_page}、{@code uploaded_image}。
     *                  <b>它是对外契约的一部分</b>，模型靠它判断这条观测是什么
     * @param content   观测内容摘要
     */
    public void observe(String sessionId, String source, String content) {
        if (sessionId == null || sessionId.isBlank() || source == null || source.isBlank()) {
            return;
        }
        String text = content == null ? "" : content.strip();
        if (text.isEmpty()) {
            return;
        }
        if (text.length() > MAX_CONTENT_CHARS) {
            text = text.substring(0, MAX_CONTENT_CHARS) + "…";
        }
        List<Observation> list = store.computeIfAbsent(sessionId, k -> new CopyOnWriteArrayList<>());
        synchronized (list) {
            list.removeIf(o -> o.source().equals(source));
            list.add(new Observation(source, text, Instant.now(), nextSequence()));
            while (list.size() > MAX_OBSERVATIONS) {
                list.remove(0);
            }
        }
    }

    /**
     * 读本会话当前的观测，<b>最近的在前面</b>。
     * <p>
     * 顺序有含义：模型从上往下读，最近发生的应该先被看到——用户在页面上刚做的事，
     * 比十分钟前那次更可能是他这句话的语境。
     */
    public List<Observation> current(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return List.of();
        }
        List<Observation> list = store.get(sessionId);
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        synchronized (list) {
            List<Observation> copy = new ArrayList<>(list);
            // 先比写入序号再比时间戳。**时间戳不能单独用**：同一毫秒内写入的两条观测
            // 时间戳相同，排序结果取决于集合的迭代顺序，而那是实现细节——
            // 「最近的在前」这条语义会被一个不该影响结果的东西决定
            copy.sort(Comparator.comparingLong(Observation::sequence).reversed());
            return List.copyOf(copy);
        }
    }

    /**
     * 清掉一个会话的全部观测 —— 一轮结束时调用。
     * <p>
     * <b>必须由链路显式调用，不能靠容量自然淘汰。</b>感知观测的失效条件是
     * 「这一轮结束了」，不是「池子满了」；靠容量淘汰意味着只要池子没满，
     * 一条已经过期的观测就会一直留在那里被注入。
     */
    public void clear(String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            store.remove(sessionId);
        }
    }

    /**
     * 一条观测。
     *
     * @param source 来源标识（对外契约）
     * @param content 内容摘要，已截断
     * @param at     观测时刻
     * @param sequence 写入序号，用于在同一毫秒内也给得出确定的先后
     */
    public record Observation(String source, String content, Instant at, long sequence) {
    }
}
