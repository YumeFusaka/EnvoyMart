package yumefusaka.envoymart.agent.memory;

import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 用户画像存储 —— 按 userId 隔离。
 * <p>
 * 目前是进程内实现。做成具体类而非接口：当前只有一个实现，等到真需要持久化
 * （跨实例共享、重启不丢）时再抽接口也不迟，过早抽接口只是多一层没人换的实现。
 * <p>
 * <b>隔离单位是 userId 而不是 sessionId</b>：画像是"这个人是谁"，应当在会话之间延续；
 * 会话是"这次聊了什么"，跟着窗口走。把两者混成一个 key，会让"跨会话记忆"名存实亡，
 * 也会让一个用户读到另一个用户的画像。
 */
@Slf4j
public class UserProfileStore {

    private final ConcurrentHashMap<String, UserProfile> profiles = new ConcurrentHashMap<>();

    public UserProfile get(String userId) {
        if (userId == null || userId.isBlank()) {
            return new UserProfile(null);
        }
        return profiles.computeIfAbsent(userId, UserProfile::new);
    }

    /** 写入若干槽位取值。空列表时不做任何事，避免无谓地创建空画像。 */
    public void update(String userId, List<ProfileEntry> entries) {
        if (userId == null || userId.isBlank() || entries == null || entries.isEmpty()) {
            return;
        }
        UserProfile profile = get(userId);
        for (ProfileEntry entry : entries) {
            profile.update(entry.getSlot(), entry.getValue(), entry.getConfidence());
        }
        log.info("[UserProfile] userId={} 更新 {} 个槽位，当前共 {} 个", userId, entries.size(), profile.slots().size());
    }

    public void clear(String userId) {
        profiles.remove(userId);
    }
}
