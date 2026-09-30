package yumefusaka.envoymart.agent.tool;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 高危操作的确认令牌 —— <b>把「用户批准了什么」变成一份服务端签过名的载荷。</b>
 * <p>
 * <b>它替掉的是请求级布尔 {@code approved=true}。</b>那个布尔有两个洞，worst case 都落在
 * 不可撤销的操作上：
 * <ol>
 *   <li><b>不绑定动作。</b>置位即放行本轮全部高危步骤。用户看到的是「取消 12 号单」的卡片，
 *       放行的却是模型这一轮重新规划出来的任何高危调用——卡上那句描述与真正执行的东西
 *       之间没有任何强制关系，全靠模型自觉。</li>
 *   <li><b>不绑定会话。</b>重入轮要执行什么，靠模型从短期记忆里把上一轮的意图回忆出来。
 *       对话历史滑出窗口、用户换了设备，卡片还在，点下去退化成「请告诉我要做什么」。</li>
 * </ol>
 * 令牌把这两件事一次解决：载荷里写着<b>确切要执行哪些调用</b>，签名保证它没被改过，
 * 载荷里的 {@code u}/{@code s} 保证它只对签发它的那个用户、那个会话有效。
 * 重入时服务端执行的是签名里的载荷，<b>模型不参与</b>——不给它机会把「取消 12 号单」
 * 执行成别的什么。
 * <p>
 * <b>格式</b>：{@code base64url(JSON) + "." + hex(HMAC-SHA256(secret, base64url(JSON)))}，
 * 与支付回调验签（{@code PaymentCallbackVerifier}）同一套思路，只是这里的载荷是结构化的。
 *
 * <pre>
 * {"u":"1001","s":"sess-abc","e":1767225600,"a":[{"t":"order_cancel","p":{"orderId":12}}]}
 * </pre>
 *
 * <b>载荷是明文不是密文</b>，这是有意的：它只包含用户自己刚在卡片上看到过的东西
 * （要取消哪一单），没有任何服务端秘密。签名保证的是「没被篡改」，不是「没被看见」。
 * <p>
 * <b>不落库、不查表。</b>状态全在令牌里，多实例部署下不需要共享存储——
 * 代价是密钥必须各实例一致（{@code AGENT_APPROVAL_SECRET}），未配置时退化为
 * 进程级随机密钥：单机开发照常可用，多实例下互相验不过，这一点会在启动日志里写明。
 * <p>
 * <b>不是一次性的</b>：同一个令牌在有效期内可以重复提交，执行两次。
 * 这不会造成重复扣款之类的后果——高危工具的语义本身就该是幂等的（取消已取消的订单
 * 会返回状态不允许），而这个代价换来的是不需要任何服务端状态与过期清理。
 */
@Slf4j
public class ApprovalTokens {

    /** 确认窗口。取十分钟：够用户读完卡片再点，又短到不该被长期留存 */
    public static final long DEFAULT_TTL_SECONDS = 600;

    /**
     * 载荷长度上限（字符）。令牌是<b>客户端原样回传</b>的输入，先按长度挡一道，
     * 再进 JSON 解析——解析任意长度的外部字符串是不必要的攻击面。
     * 真实载荷只有几十到几百字符（计划里需要确认的步骤本就该是个位数）。
     */
    private static final int MAX_TOKEN_CHARS = 8192;

    /** 一个令牌里最多绑几个动作。高危操作一次确认放行一整批本就该是例外而非常态 */
    private static final int MAX_ACTIONS = 16;

    private static final String HMAC_SHA256 = "HmacSHA256";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final byte[] secret;
    private final long ttlSeconds;

    /**
     * @param secret 签名密钥。<b>为空时生成一枚进程级随机密钥</b>并告警——
     *               开发与演示环境不该为了跑通确认链路先配一个环境变量，
     *               而随机密钥在单进程内完全够用（签发与校验是同一个实例）。
     *               多实例部署必须显式配置：那样两边各自随机，用户点确认会拿到「已失效」。
     */
    public ApprovalTokens(String secret) {
        this(secret, DEFAULT_TTL_SECONDS);
    }

    public ApprovalTokens(String secret, long ttlSeconds) {
        if (secret == null || secret.isBlank()) {
            byte[] generated = new byte[32];
            new SecureRandom().nextBytes(generated);
            log.warn("[Approval] 未配置审批签名密钥（AGENT_APPROVAL_SECRET），已生成进程级临时密钥。"
                    + "单机开发无影响；多实例部署下各实例互相验不过确认令牌，重启后旧令牌也会失效。");
            this.secret = generated;
        } else {
            this.secret = secret.getBytes(StandardCharsets.UTF_8);
        }
        this.ttlSeconds = ttlSeconds > 0 ? ttlSeconds : DEFAULT_TTL_SECONDS;
    }

    /** 签发：把这一批待确认的操作封成令牌，随响应下发给客户端 */
    public String issue(String userId, String sessionId, List<PendingAction> actions) {
        if (actions == null || actions.isEmpty()) {
            throw new IllegalArgumentException("没有待确认的操作，不该签发确认令牌");
        }
        List<Map<String, Object>> encoded = new ArrayList<>(actions.size());
        for (PendingAction action : actions) {
            encoded.add(Map.of("t", action.tool(), "p", action.arguments()));
        }
        Map<String, Object> payload = Map.of(
                "u", userId == null ? "" : userId,
                "s", sessionId == null ? "" : sessionId,
                "e", System.currentTimeMillis() / 1000 + ttlSeconds,
                "a", encoded);
        String body = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(MAPPER.writeValueAsBytes(payload));
        return body + "." + sign(body);
    }

    /**
     * 校验并取回载荷。<b>任何一项不成立都返回空</b>，调用方据此拒绝执行——
     * 失败原因只进日志，不进给用户的文案（对用户而言「无效」与「过期」要做的事是同一件：
     * 重新发起一次）。
     *
     * @return 签名里的操作列表；签名不符、已过期、用户或会话对不上、载荷损坏时为空
     */
    public Optional<List<PendingAction>> verify(String token, String userId, String sessionId) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        if (token.length() > MAX_TOKEN_CHARS) {
            log.warn("[Approval] 确认令牌超长（{} 字符），拒绝", token.length());
            return Optional.empty();
        }
        int dot = token.indexOf('.');
        if (dot <= 0 || dot == token.length() - 1) {
            log.warn("[Approval] 确认令牌格式不合法，拒绝");
            return Optional.empty();
        }
        String body = token.substring(0, dot);
        String signature = token.substring(dot + 1);
        // 定长比较：逐字符短路比较会让攻击者按字节猜出正确签名
        if (!MessageDigest.isEqual(sign(body).getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8))) {
            // 这一行是安全信号，不是噪音：签名对不上意味着有人在改载荷（或换了密钥）
            log.warn("[Approval] 确认令牌签名不符，拒绝执行");
            return Optional.empty();
        }

        Map<String, Object> payload;
        try {
            payload = MAPPER.readValue(Base64.getUrlDecoder().decode(body), new TypeReference<>() {
            });
        } catch (Exception e) {
            log.warn("[Approval] 确认令牌载荷无法解析，拒绝：{}", e.toString());
            return Optional.empty();
        }

        long expiresAt = asLong(payload.get("e"));
        if (expiresAt <= 0) {
            log.warn("[Approval] 确认令牌缺少有效期，拒绝");
            return Optional.empty();
        }
        long now = System.currentTimeMillis() / 1000;
        if (now > expiresAt) {
            log.info("[Approval] 确认令牌已过期 {} 秒，拒绝", now - expiresAt);
            return Optional.empty();
        }
        if (!equalsNullSafe(userId, payload.get("u")) || !equalsNullSafe(sessionId, payload.get("s"))) {
            // 令牌是发给某个用户、某个会话的。允许跨用户使用，等于拿到令牌的人就能替别人取消订单
            log.warn("[Approval] 确认令牌不属于当前用户或会话，拒绝");
            return Optional.empty();
        }

        List<PendingAction> actions = decodeActions(payload.get("a"));
        if (actions.isEmpty()) {
            log.warn("[Approval] 确认令牌里没有可执行的操作，拒绝");
            return Optional.empty();
        }
        return Optional.of(actions);
    }

    private List<PendingAction> decodeActions(Object raw) {
        if (!(raw instanceof List<?> list) || list.isEmpty() || list.size() > MAX_ACTIONS) {
            return List.of();
        }
        List<PendingAction> actions = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                return List.of();
            }
            Object tool = map.get("t");
            if (!(tool instanceof String name) || name.isBlank()) {
                return List.of();
            }
            actions.add(PendingAction.of(name, asArguments(map.get("p"))));
        }
        return actions;
    }

    /** 参数原样透传：它的结构由签发的那个 {@link PendingAction} 决定，这里只做类型归一 */
    private Map<String, Object> asArguments(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> arguments = new java.util.LinkedHashMap<>();
        map.forEach((key, value) -> arguments.put(String.valueOf(key), value));
        return arguments;
    }

    private String sign(String body) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(secret, HMAC_SHA256));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            // 算法缺失或密钥非法是配置问题，不是请求问题——不能静默降级成一个能被伪造的令牌
            throw new IllegalStateException("无法计算确认令牌签名", e);
        }
    }

    private static boolean equalsNullSafe(String expected, Object actual) {
        return (expected == null ? "" : expected).equals(actual == null ? "" : String.valueOf(actual));
    }

    private static long asLong(Object raw) {
        if (raw instanceof Number number) {
            return number.longValue();
        }
        try {
            return raw == null ? 0 : Long.parseLong(String.valueOf(raw));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
