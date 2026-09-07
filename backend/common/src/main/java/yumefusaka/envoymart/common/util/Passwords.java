package yumefusaka.envoymart.common.util;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 口令哈希 —— BCrypt，自带随机盐，同一口令每次哈希结果不同。
 * <p>
 * 只依赖 {@code spring-security-crypto}，不引入完整 Spring Security 的过滤器链。
 */
public final class Passwords {

    /**
     * 用于「用户不存在」分支的占位哈希，让该分支也走一次同等开销的比对。
     * <p>
     * 不这么做的话，「用户不存在」会立即返回、「口令错误」要等一次 BCrypt 计算，
     * 攻击者据此就能枚举出哪些用户名真实存在。
     */
    private static final String DUMMY_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();

    private Passwords() {
    }

    public static String hash(String rawPassword) {
        return ENCODER.encode(rawPassword);
    }

    public static boolean matches(String rawPassword, String encodedPassword) {
        if (rawPassword == null || encodedPassword == null) {
            return false;
        }
        try {
            return ENCODER.matches(rawPassword, encodedPassword);
        } catch (IllegalArgumentException e) {
            // 库里存了非 BCrypt 格式的值（如历史明文），一律视为不匹配，不向上抛
            return false;
        }
    }

    /** 用户不存在时调用，消耗与真实比对相当的时间，消除用户名枚举的时间侧信道。 */
    public static void wasteTimeLikeVerification(String rawPassword) {
        matches(rawPassword == null ? "" : rawPassword, DUMMY_HASH);
    }
}
