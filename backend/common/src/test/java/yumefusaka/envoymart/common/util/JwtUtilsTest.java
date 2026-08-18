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
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("至少需要 32 字节");
    }
}
