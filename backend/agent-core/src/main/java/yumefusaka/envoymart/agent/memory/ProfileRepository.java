package yumefusaka.envoymart.agent.memory;

import java.util.List;

/**
 * 画像的持久化契约。
 * <p>
 * <b>为什么需要它</b>：画像与情节是长期记忆的两轨，但持久化能力一度不对称——
 * 情节写进向量库（持久），画像只在进程内存里（重启即丢）。后果是用户会看到
 * 「我记得你上次抱怨过物流慢」却说不出「你的预算多少」，两轨对不上。
 * <p>
 * 只传 {@code List<ProfileEntry>} 而不是 {@code Map<ProfileSlot, ...>}：
 * 枚举做 JSON 的 key 会退化成字符串，读回来还要再映射一次；列表则天然可序列化。
 */
public interface ProfileRepository {

    /** 读该用户的全部槽位；无记录时返回空列表。 */
    List<ProfileEntry> load(String userId);

    /** 覆盖写该用户的全部槽位。 */
    void save(String userId, List<ProfileEntry> entries);

    /** 未接入持久化时的空实现——退化为纯内存，与从前行为一致。 */
    ProfileRepository NOOP = new ProfileRepository() {
        @Override
        public List<ProfileEntry> load(String userId) {
            return List.of();
        }

        @Override
        public void save(String userId, List<ProfileEntry> entries) {
            // 不持久化
        }
    };
}
