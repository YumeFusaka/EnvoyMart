package yumefusaka.envoymart.common.util;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtUtilsTest {

    private static final String SECRET = "envoymart-test-secret-key-at-least-32-bytes";

    @Test
    void shouldCreateAndParseToken() {
        String token = JwtUtils.createToken(SECRET, 60_000, Map.of("id", "u1001"));
        Claims claims = JwtUtils.parseToken(SECRET, token);
        assertThat(claims.get("id")).isEqualTo("u1001");
    }

    @Test
    void shouldRejectTokenSignedWithAnotherKey() {
        String token = JwtUtils.createToken(SECRET, 60_000, Map.of("id", "u1001"));
        assertThatThrownBy(() -> JwtUtils.parseToken("another-secret-key-at-least-32-bytes-long", token))
                .isInstanceOf(io.jsonwebtoken.JwtException.class);
    }

    @Test
    void shouldRejectTooShortSecret() {
        assertThatThrownBy(() -> JwtUtils.createToken("yumefusaka", 60_000, Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET")
                .hasMessageContaining("32 字节");
    }

    /**
     * 密钥缺失必须在签发 Token 之前就被拦下。
     * <p>
     * 回归防线：曾经配置里带一个仓库公开的默认密钥，任何读过源码的人都能离线自签出合法 Token。
     */
    @Test
    void shouldRejectMissingSecret() {
        assertThatThrownBy(() -> JwtUtils.validateSecretKey(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");
        assertThatThrownBy(() -> JwtUtils.validateSecretKey(""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");
    }

    @Test
    void shouldAcceptValidSecret() {
        JwtUtils.validateSecretKey(SECRET);
    }
}
