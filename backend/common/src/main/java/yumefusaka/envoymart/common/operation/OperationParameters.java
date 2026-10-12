package yumefusaka.envoymart.common.operation;

import tools.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** 固定版本的参数指纹；对象键排序，列表顺序保留，DTO 已负责缺省和类型归一。 */
public final class OperationParameters {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private OperationParameters() { }

    public static String hash(String userId, String toolName, Map<String, Object> parameters) {
        return sha256(MAPPER.writeValueAsString(canonical(Map.of("version", 1, "userId", userId,
                "toolName", toolName, "parameters", parameters))));
    }

    public static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("运行环境缺少参数指纹算法", e);
        }
    }

    private static Object canonical(Object value) {
        if (value instanceof Map<?, ?> source) {
            Map<String, Object> sorted = new TreeMap<>();
            source.forEach((key, item) -> sorted.put(String.valueOf(key), canonical(item)));
            return sorted;
        }
        if (value instanceof List<?> list) return list.stream().map(OperationParameters::canonical).toList();
        return value;
    }
}
