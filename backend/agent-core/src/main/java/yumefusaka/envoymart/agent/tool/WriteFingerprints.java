package yumefusaka.envoymart.agent.tool;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 写操作指纹 —— 「同一次写操作」的规范化表示，用于跨批次判重。
 * <p>
 * <b>为什么必须是一个共用函数，而不是两端各拼一串。</b>指纹有两端：一端是
 * 「已经执行过的写操作」，另一端是「本轮即将生成的待确认批次」。两端各写一份拼法
 * 会产生一种最难查的失效——两串永远不相等、判重永远不命中，而日志、单测、界面全都正常。
 * 拼法收在这里、两端引用同一个函数，等价类才只有一个定义。
 * <p>
 * <b>只对带目标编号的写操作有意义。</b>判的是「同一个操作目标」，所以只取
 * {@link #IDENTITY_PARAMETERS}（{@code skuId} / {@code orderId} / {@code cartItemId} /
 * {@code orderItemId} / {@code couponId}）。一个都没有时返回<b>空串</b>，表示
 * 「这次操作没有可判重的目标」——下单、开工单这类写操作的参数是收货信息与描述文本，
 * 拿整份参数比会把「加了新商品后用同样的地址再下一单」误判成重复。
 * <b>空串一律不参与判重</b>（见 {@link #isRepeat}）：判据的边界宁可窄，
 * 因为误拦会挡住用户合法的第二次操作。
 * <p>
 * 数量之类的非目标参数刻意<b>不</b>参与：「同一件商品再加一份」在用户眼里
 * 就是重复，数量从 1 改成 2 不改变这件事。
 * <p>
 * 数值按十进制规范化（{@code 39} / {@code 39L} / {@code "39"} 视为同一个值）：
 * 同一个 SKU 可能经 JSON 解析、模型字符串、业务 DTO 三条路过来，
 * 不归一就会漏掉真正的那一次重复。
 */
public final class WriteFingerprints {

    /**
     * 标识「操作目标」的参数名 —— 命中即只用它们参与指纹。
     * <p>
     * 显式清单而不是「所有以 Id 结尾的参数」这类规则：规则会把 {@code userId}、
     * {@code requestId} 这类与目标无关的编号卷进来；而漏掉一个真正的目标参数，
     * 代价是判重静默失效。
     */
    public static final List<String> IDENTITY_PARAMETERS =
            List.of("skuId", "orderId", "cartItemId", "orderItemId", "couponId");

    private WriteFingerprints() {
    }

    /** 一次写操作（工具名 + 入参）的指纹；没有目标编号时返回空串。 */
    public static String of(String tool, Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return "";
        }
        Map<String, Object> identity = new LinkedHashMap<>();
        for (String name : IDENTITY_PARAMETERS) {
            Object value = arguments.get(name);
            if (present(value)) {
                identity.put(name, value);
            }
        }
        if (identity.isEmpty()) {
            return "";
        }
        StringBuilder fingerprint = new StringBuilder(tool == null ? "" : tool);
        identity.forEach((name, value) -> fingerprint.append('|').append(name)
                .append('=').append(normalize(value)));
        return fingerprint.toString();
    }

    /** 一条待确认载荷的指纹；调用方记录「已经执行过什么」时用它。 */
    public static String of(PendingAction action) {
        return action == null ? "" : of(action.tool(), action.arguments());
    }

    /** 一批载荷的指纹，已剔除「没有目标编号」的空指纹。null 与空列表都表示「没有历史」。 */
    public static List<String> ofAll(List<PendingAction> actions) {
        if (actions == null) {
            return List.of();
        }
        return actions.stream().map(WriteFingerprints::of).filter(one -> !one.isEmpty()).toList();
    }

    /**
     * 这次调用是否与已经执行过的那一次是同一个操作。
     * <p>
     * 判据收在这一处：{@code fingerprint} 为空（没有目标编号）时一律返回 false，
     * 已经执行过的那批里混进空串也不会误命中。
     */
    public static boolean isRepeat(String fingerprint, Collection<String> executed) {
        return fingerprint != null && !fingerprint.isEmpty()
                && executed != null && executed.contains(fingerprint);
    }

    private static boolean present(Object value) {
        if (value == null) {
            return false;
        }
        return !(value instanceof String text) || !text.isBlank();
    }

    /**
     * 值的规范化。数值走十进制规范化；字符串只在「看起来就是数字」时折成数字——
     * 参与指纹的参数都是编号，{@code "39"} 与 {@code 39} 指同一个 SKU。
     */
    private static String normalize(Object value) {
        if (value instanceof Number number) {
            return decimal(number.toString());
        }
        String text = String.valueOf(value).trim();
        return text.matches("[+-]?\\d+(\\.\\d+)?") ? decimal(text) : text;
    }

    private static String decimal(String raw) {
        try {
            return new BigDecimal(raw).stripTrailingZeros().toPlainString();
        } catch (NumberFormatException e) {
            return raw;
        }
    }
}
