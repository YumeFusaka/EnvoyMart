package yumefusaka.envoymart.agent.memory;

import java.util.List;
import java.util.Optional;

/**
 * 用户画像的槽位。
 * <p>
 * <b>刻意用固定枚举而不是自由 key-value</b>：画像这一轨的设计目标是「不检索、全量注入」，
 * 而全量注入要求它必须有界。开放 key 会让条目随时间无限增长，最终撑爆上下文——
 * 是"全量注入"这个需求本身决定了槽位必须固定。
 * <p>
 * 自由的、装不进槽位的内容走情节记忆那一轨，按需语义召回。
 */
public enum ProfileSlot {

    /** 身份角色，如"学生党" */
    IDENTITY("身份角色", false),

    /** 预算区间 */
    BUDGET("预算区间", true),

    /** 偏好品类 */
    PREFERRED_CATEGORY("偏好品类", false),

    /** 偏好品牌 */
    PREFERRED_BRAND("偏好品牌", false),

    /** 收货地 */
    SHIPPING_ADDRESS("收货地", true),

    /** 服务偏好，如"要开发票""只走顺丰" */
    SERVICE_PREFERENCE("服务偏好", false);

    private final String label;
    private final boolean timeSensitive;

    ProfileSlot(String label, boolean timeSensitive) {
        this.label = label;
        this.timeSensitive = timeSensitive;
    }

    public String label() {
        return label;
    }

    /**
     * 是否随时间快速失效。
     * <p>
     * 衰减速率必须按槽位区分：「是学生党」可能三年不变，「当前预算」三个月就废了。
     * 用同一个衰减率会把长期有效的事实和短期情境一起误伤。
     */
    public boolean timeSensitive() {
        return timeSensitive;
    }

    public static Optional<ProfileSlot> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(raw.trim().toUpperCase()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** 供抽取提示词使用：把可选槽位与语义一起告诉模型，避免它自创 key。 */
    public static List<ProfileSlot> all() {
        return List.of(values());
    }
}
