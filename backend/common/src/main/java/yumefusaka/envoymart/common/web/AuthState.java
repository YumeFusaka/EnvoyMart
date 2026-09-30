package yumefusaka.envoymart.common.web;

/**
 * 认证态的线上格式 —— 写入方（auth-service）与读取方（网关）共用同一份约定。
 * <p>
 * 放在 {@code common} 而不是各写一遍：键名或分隔符两边写得不一样，<b>不会有任何编译错误</b>，
 * 表现是「禁用了但没生效」——一个必须靠人肉在测试里发现的故障。这类跨进程约定要么只有一份，
 * 要么就得有一份能自动对上的检查，这里选前者。
 * <p>
 * 值形如 {@code "0|USER"}：状态位 + 角色。角色要一起存，是因为「改角色」与「禁用」是
 * 同一类问题——JWT 签出去之后服务端就管不着了，两者都必须靠这条外部记录才能立即生效。
 * <p>
 * <b>解析不出来时一律按「没有这条记录」处理</b>（{@link #isDisabled} 返回 false、
 * {@link #roleOf} 返回 null），让网关退回「按 Token 里的角色放行」的旧行为。
 * 这是刻意的：格式错乱是程序缺陷，而把全体用户挡在门外是事故——两者的严重程度不对等。
 */
public final class AuthState {

    /** Redis 哈希的键名，field = userId，value = {@link #format} 的产物 */
    public static final String KEY = "envoymart:auth-state";

    private static final char SEPARATOR = '|';
    private static final String DISABLED = "0";

    private AuthState() {
    }

    public static String format(int status, String role) {
        return status + String.valueOf(SEPARATOR) + (role == null ? "" : role);
    }

    /** 状态位是不是「禁用」。解析不出来时返回 false（放行） */
    public static boolean isDisabled(String state) {
        String status = statusOf(state);
        return DISABLED.equals(status);
    }

    /**
     * 记录里的角色。解析不出来时返回 {@code null}，调用方应退回 Token 里的角色。
     */
    public static String roleOf(String state) {
        if (state == null) {
            return null;
        }
        int at = state.indexOf(SEPARATOR);
        if (at < 0) {
            return null;
        }
        String role = state.substring(at + 1).trim();
        return role.isEmpty() ? null : role;
    }

    private static String statusOf(String state) {
        if (state == null) {
            return null;
        }
        int at = state.indexOf(SEPARATOR);
        return at < 0 ? null : state.substring(0, at).trim();
    }
}
