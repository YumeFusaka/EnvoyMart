package yumefusaka.envoymart.common.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;

public final class JwtUtils {

    /** HS256 要求密钥至少 256 位（32 字节）。 */
    private static final int MIN_SECRET_BYTES = 32;

    private JwtUtils() {
    }

    public static String createToken(String signKey, long expire, Map<String, Object> claims) {
        return Jwts.builder()
                .claims(claims)
                .expiration(new Date(System.currentTimeMillis() + expire))
                .signWith(secretKey(signKey))
                .compact();
    }

    public static Claims parseToken(String signKey, String jwt) {
        return Jwts.parser()
                .verifyWith(secretKey(signKey))
                .build()
                .parseSignedClaims(jwt)
                .getPayload();
    }

    /**
     * 启动时校验密钥，让配置错误在启动阶段暴露，而不是等到第一次签发/校验 Token。
     * <p>
     * <b>密钥刻意不设默认值</b>：一个写在仓库里的默认密钥等于没有密钥——任何读过源码的人
     * 都能离线自签出合法 Token。缺失或过短时必须拒绝启动，而不是降级成一个人人皆知的值。
     */
    public static void validateSecretKey(String signKey) {
        secretKey(signKey);
    }

    private static SecretKey secretKey(String signKey) {
        byte[] keyBytes = signKey == null ? new byte[0] : signKey.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "JWT_SECRET 未配置或长度不足：HS256 要求至少 " + MIN_SECRET_BYTES
                            + " 字节，当前 " + keyBytes.length + " 字节。"
                            + "请通过环境变量 JWT_SECRET 提供一个随机密钥，例如 `openssl rand -base64 48`。");
        }
        return Keys.hmacShaKeyFor(keyBytes);
    }
}
