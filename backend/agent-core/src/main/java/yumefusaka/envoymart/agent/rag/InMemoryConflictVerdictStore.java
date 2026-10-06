package yumefusaka.envoymart.agent.rag;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 冲突裁定的进程内实现 —— 没有配置持久化时用。
 * <p>
 * <b>它的价值是「有胜过无」，而不是「够用」。</b>进程内留存只能挡住同一次运行里的重复判定：
 * 重启之后同一场对话的历史裁定全部蒸发，而冲突裁定恰恰是那种「上周判过、这周还问」的东西。
 * 所以生产路径应当挂上 Redis 实现（见 ai-service 的 {@code RedisConflictVerdictStore}），
 * 这个类只做降级——Redis 不可用时宁可退回进程内，也不要让整轮对话失败。
 * <p>
 * <b>无上限增长在这里是可以接受的</b>：键是内容哈希，同一条冲突反复出现只会覆盖同一个键，
 * 而真正无限增长的是「语料里有多少对互斥的说法」，它与语料规模同阶。
 * Redis 那一侧给了 TTL，这里没有——进程内存随进程一起消失，本来就没有长期占用的风险。
 */
public class InMemoryConflictVerdictStore implements ConflictVerdictStore {

    private final Map<String, Verdict> store = new ConcurrentHashMap<>();

    @Override
    public Optional<Verdict> find(String fingerprint) {
        if (fingerprint == null || fingerprint.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(store.get(fingerprint));
    }

    @Override
    public void save(Verdict verdict) {
        if (verdict == null || verdict.fingerprint() == null || verdict.fingerprint().isBlank()) {
            return;
        }
        store.put(verdict.fingerprint(), verdict);
    }
}