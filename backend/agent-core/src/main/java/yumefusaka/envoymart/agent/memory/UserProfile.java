package yumefusaka.envoymart.agent.memory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 一个用户的画像 —— 固定槽位、覆盖式更新、有界。
 * <p>
 * 与情节记忆的分工：画像回答"这个人是谁"，始终全量注入；
 * 情节回答"他经历过什么"，按需召回。前者可靠但粗糙，后者丰富但需要检索。
 * <p>
 * 并发：以实例为单位加锁。单个用户的画像写入频率极低（每轮对话至多一次），
 * 锁竞争可以忽略，换来的是复合操作（读旧值 → 写新值 → 搬 previous）的原子性。
 */
public class UserProfile {

    /** 低于该置信度的槽位不注入 —— 宁缺毋滥，注入的每一条都在消耗上下文且增加污染面 */
    private static final double INJECTION_CONFIDENCE_FLOOR = 0.5;

    /** 时间敏感槽位的有效期；超过则不再注入（但不删除，保留可查） */
    private static final Duration TIME_SENSITIVE_TTL = Duration.ofDays(90);

    private final String userId;
    private final Map<ProfileSlot, ProfileEntry> slots = new EnumMap<>(ProfileSlot.class);

    public UserProfile(String userId) {
        this.userId = userId;
    }

    public String getUserId() {
        return userId;
    }

    /**
     * 写入一个槽位取值。
     * <p>
     * 三种情况分开处理，这就是"新老分辨"的全部逻辑：
     * <ul>
     *   <li><b>值相同</b>——不是新事实，是同一事实的又一次证据：只刷新时间与置信度，不产生新条目；</li>
     *   <li><b>值不同</b>——同一槽位的新值：旧值移入 previous 供审计，新值生效；</li>
     *   <li><b>槽位为空</b>——首次写入。</li>
     * </ul>
     * 冲突在写入时就消解掉了，不会留到注入时让模型面对两条互相矛盾的记忆。
     */
    public synchronized void update(ProfileSlot slot, String value, double confidence) {
        if (slot == null || value == null || value.isBlank()) {
            return;
        }
        String normalized = value.trim();
        ProfileEntry existing = slots.get(slot);

        if (existing == null) {
            slots.put(slot, ProfileEntry.builder()
                    .slot(slot).value(normalized).confidence(confidence).build());
            return;
        }
        if (existing.getValue().equals(normalized)) {
            // 同一事实被再次提到：提高置信度（有界），刷新时间
            existing.setConfidence(Math.min(1.0, existing.getConfidence() + 0.1));
            existing.setUpdatedAt(Instant.now());
            return;
        }
        existing.setPreviousValue(existing.getValue());
        existing.setValue(normalized);
        existing.setConfidence(confidence);
        existing.setUpdatedAt(Instant.now());
    }

    /**
     * 当前应当注入的条目。
     * <p>
     * 过滤规则体现"过时处理"：低置信度不注入、时间敏感的槽位超期不注入。
     * <b>过滤只影响注入，不影响存储</b>——旧值仍可查，只是不再影响模型的判断。
     */
    public synchronized List<ProfileEntry> injectionEntries() {
        List<ProfileEntry> result = new ArrayList<>();
        Instant now = Instant.now();
        for (ProfileEntry entry : slots.values()) {
            if (entry.getConfidence() < INJECTION_CONFIDENCE_FLOOR) {
                continue;
            }
            if (entry.getSlot().timeSensitive()
                    && Duration.between(entry.getUpdatedAt(), now).compareTo(TIME_SENSITIVE_TTL) > 0) {
                continue;
            }
            result.add(entry);
        }
        return result;
    }

    public synchronized Map<ProfileSlot, ProfileEntry> slots() {
        return new EnumMap<>(slots);
    }

    public synchronized boolean isEmpty() {
        return slots.isEmpty();
    }
}
