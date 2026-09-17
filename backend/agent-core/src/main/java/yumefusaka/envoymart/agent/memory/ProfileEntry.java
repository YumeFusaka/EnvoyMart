package yumefusaka.envoymart.agent.memory;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 画像槽位的取值 —— 一个槽位同时只有一个"当前值"，历史值留在 {@link #previousValue}。
 * <p>
 * 这是"新老信息如何分辨"的落点：有了槽位才谈得上"同一个事实的两版"。
 * 没有 key 的纯文本存储里，「预算 100」和「预算 300」是两条无从比较的独立字符串，
 * 只能都留着，最终两条互相矛盾的记忆同时注入。
 */
@Data
@Builder
public class ProfileEntry {

    private ProfileSlot slot;

    /** 当前生效的值 */
    private String value;

    /** 上一次的值，留作审计；不参与注入 */
    private String previousValue;

    /** 最近一次更新时刻 —— 注入时据此判断新鲜度 */
    @Builder.Default
    private Instant updatedAt = Instant.now();

    /**
     * 置信度 0~1。
     * <p>
     * 用来区分"用户明确陈述的偏好"和"从一句话里猜出来的"。
     * 单次提及不给高置信度，避免把噪声写进画像后被反复强化。
     */
    @Builder.Default
    private double confidence = 0.5;
}
