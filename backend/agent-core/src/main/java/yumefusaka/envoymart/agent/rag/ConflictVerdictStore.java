package yumefusaka.envoymart.agent.rag;

import java.util.List;
import java.util.Optional;

/**
 * 冲突裁定的留存 —— 同一条矛盾第二次出现时，不必再判一次。
 * <p>
 * <b>要解决的问题是「每轮现算」。</b>冲突判定要额外调一次模型（见
 * {@link ConflictReporter#CHECK_PROMPT}），而用户反复问同一件事时，同一个矛盾会被
 * 反复发现、反复上报、反复占用一轮模型调用。用户拍板的原话是
 * <b>「正确的决策需要持久化」</b>——已经裁定过的结论不该随着对话轮次蒸发。
 * <p>
 * <b>本接口存在的第一价值是让「失效」这件事必须被显式实现。</b>做错的形态只有一种：
 * 把「上次判过」变成「这次不判」，于是资料改了之后，系统还在用一份过期的裁定 ——
 * 真实分歧被静默吞掉，而这正是整套冲突机制要防的东西。
 * 所以 {@link Fingerprint} 的组成里包含<b>参与冲突的每一段证据的正文哈希</b>：
 * 内容变了，指纹就变了，旧裁定自然对不上、必须重新判。
 * <p>
 * <b>为什么不按「文档版本号」判。</b>版本号靠人维护，改正文忘了改版本是常态；
 * 而正文哈希是算出来的，改一个字就变。用版本号当失效判据，等于把正确性押在
 * 「大家都会记得改版本号」这个不会成立的假设上。
 */
public interface ConflictVerdictStore {

    /**
     * 一条冲突裁定。
     *
     * @param fingerprint 冲突指纹，见 {@link #fingerprint}
     * @param detail      裁定说明（模型当轮给出的那段文字，含「以条目 N 为准」这类依据）
     * @param resolved    是否已定夺。{@code false} 表示待人工确认 —— 它同样要留存：
     *                    同一条分歧视而不见地问十次，答案还是「不确定」，
     *                    这里存下来的是「我们已经知道它定不了」，不必再让模型判第十一次
     * @param decidedAt   裁定时间（epoch millis）
     */
    record Verdict(String fingerprint, String detail, boolean resolved, long decidedAt) {
    }

    /** 查一条裁定。没有时返回空 —— 「没有」必须与「有一条第 N 次也要重判」分开 */
    Optional<Verdict> find(String fingerprint);

    /** 记下一条裁定。同指纹重复写入时以最后一次为准（模型对同一份材料的表述可能更清楚） */
    void save(Verdict verdict);

    /**
     * 冲突指纹 —— 由「涉及的证据正文」算出来，<b>不含轮次、不含时间</b>。
     * <p>
     * 输入是参与冲突的那几段证据的正文，顺序敏感（内容数组直接拼接）。用正文而不是
     * 切片 id：同一个 id 的切片在文档重切后会指向不同的内容，而引用是跨会话存在的。
     * <p>
     * 哈希算法选 SHA-256 的前 16 个十六进制位（64 bit）：指纹的用途是「同一条冲突
     * 认得出是同一条」，不是防碰撞攻击。截短之后存入 Redis 的键长度可控。
     */
    static String fingerprint(List<String> contents) {
        if (contents == null || contents.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String content : contents) {
            sb.append(content == null ? "" : content.trim()).append('\u0000');
        }
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必备算法，走不到这里；真走到了也不能让对话崩掉 ——
            // 退化成内容本身当键（长、但同内容仍同键），正确性不受影响
            return sb.toString();
        }
    }
}