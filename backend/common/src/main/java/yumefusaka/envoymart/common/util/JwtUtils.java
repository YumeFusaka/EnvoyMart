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

    private static SecretKey secretKey(String signKey) {
        byte[] keyBytes = signKey == null ? new byte[0] : signKey.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalArgumentException(
                    "jwt.secret-key 至少需要 " + MIN_SECRET_BYTES + " 字节，当前 " + keyBytes.length + " 字节");
        }
        return Keys.hmacShaKeyFor(keyBytes);
    }
}
