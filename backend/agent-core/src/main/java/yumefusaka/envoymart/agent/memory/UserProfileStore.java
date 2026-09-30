package yumefusaka.envoymart.agent.memory;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 用户画像存储 —— 按 userId 隔离，可挂持久化。
 * <p>
 * <b>隔离单位是 userId 而不是 sessionId</b>：画像是"这个人是谁"，应当在会话之间延续；
 * 会话是"这次聊了什么"，跟着窗口走。把两者混成一个 key，会让"跨会话记忆"名存实亡，
 * 也会让一个用户读到另一个用户的画像。
 * <p>
 * <b>持久化</b>：不挂 {@link ProfileRepository} 时是纯进程内实现（重启即丢）。
 * 挂了之后读路径是「先查内存、未命中再回源」，写路径是「改内存 + 回写」——
 * 内存始终是热副本，避免每次对话都打一次 Redis。
 */
@Slf4j
public class UserProfileStore {

    private final ConcurrentHashMap<String, UserProfile> profiles = new ConcurrentHashMap<>();
    private final ProfileRepository repository;

    public UserProfileStore() {
        this(ProfileRepository.NOOP);
    }

    public UserProfileStore(ProfileRepository repository) {
        this.repository = repository == null ? ProfileRepository.NOOP : repository;
    }

    public UserProfile get(String userId) {
        if (userId == null || userId.isBlank()) {
            return new UserProfile(null);
        }
        UserProfile cached = profiles.get(userId);
        if (cached != null) {
            return cached;
        }
        return load(userId).orElseGet(() -> new UserProfile(userId));
    }

    /**
     * 回源并转入内存副本；回源失败返回空。
     * <p>
     * <b>失败不落缓存</b>，否则一次读取抖动会永久化：空画像进了缓存，之后每次 {@link #get}
     * 都命中它，再也不回源；而 Redis 里那份数据还在，只是再也没人读得到。
     * 返回空时调用方拿到的是一份「已知不完整」的画像，只能当临时值用。
     */
    private Optional<UserProfile> load(String userId) {
        UserProfile loaded = new UserProfile(userId);
        try {
            repository.load(userId)
                    .forEach(entry -> loaded.update(entry.getSlot(), entry.getValue(), entry.getConfidence()));
        } catch (Exception e) {
            log.warn("[UserProfile] 读取画像失败 userId={}: {}", userId, e.getMessage());
            return Optional.empty();
        }
        return Optional.of(profiles.merge(userId, loaded, (existing, ignored) -> existing));
    }

    /** 写入若干槽位取值。空列表时不做任何事，避免无谓地创建空画像。 */
    public void update(String userId, List<ProfileEntry> entries) {
        if (userId == null || userId.isBlank() || entries == null || entries.isEmpty()) {
            return;
        }
        UserProfile profile = profiles.get(userId);
        if (profile == null) {
            Optional<UserProfile> loaded = load(userId);
            if (loaded.isPresent()) {
                profile = loaded.get();
            } else {
                // 回源失败时不能回写：save 是覆盖写，拿一份已知不完整的画像当基准落盘，
                // 等于用本轮抽到的一两个槽位顶掉该用户在 Redis 里的其余全部槽位。
                // 而读取失败往往是持久的（值损坏、类型不对），一旦如此，每轮对话都会
                // 覆盖一次，画像被逐步削成一条。本轮更新只作用于临时副本，丢弃。
                log.warn("[UserProfile] userId={} 回源失败，跳过本轮 {} 个槽位的写入，避免覆盖已有画像",
                        userId, entries.size());
                return;
            }
        }
        for (ProfileEntry entry : entries) {
            profile.update(entry.getSlot(), entry.getValue(), entry.getConfidence());
        }
        persist(userId, profile);
        log.info("[UserProfile] userId={} 更新 {} 个槽位，当前共 {} 个", userId, entries.size(), profile.slots().size());
    }

    private void persist(String userId, UserProfile profile) {
        try {
            repository.save(userId, new ArrayList<>(profile.slots().values()));
        } catch (Exception e) {
            // 回写失败只影响"下次重启还在不在"，不该让本轮对话失败
            log.warn("[UserProfile] 回写画像失败 userId={}: {}", userId, e.getMessage());
        }
    }

    public void clear(String userId) {
        profiles.remove(userId);
        try {
            repository.save(userId, List.of());
        } catch (Exception e) {
            log.warn("[UserProfile] 清除持久化画像失败 userId={}: {}", userId, e.getMessage());
        }
    }
}
