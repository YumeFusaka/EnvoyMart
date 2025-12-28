package yumefusaka.envoymart.common.util;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JwtUtilsTest {

    @Test
    void shouldCreateAndParseToken() {
        String token = JwtUtils.createToken("secret", 60_000, Map.of("id", "u1001"));
        Claims claims = JwtUtils.parseToken("secret", token);
        assertThat(claims.get("id")).isEqualTo("u1001");
    }
}
