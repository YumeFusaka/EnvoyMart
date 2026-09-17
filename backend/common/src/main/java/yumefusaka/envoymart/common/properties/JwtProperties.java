package yumefusaka.envoymart.common.properties;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import yumefusaka.envoymart.common.util.JwtUtils;

import java.nio.charset.StandardCharsets;

@Slf4j
@Data
@ConfigurationProperties(prefix = "jwt")
public class JwtProperties {

    private String secretKey;
    private long ttl;
    private String tokenName;

    /** 启动即校验，避免密钥没配却在第一次登录时才报错。 */
    @PostConstruct
    void validate() {
        JwtUtils.validateSecretKey(secretKey);
        // 打指纹而不是密钥本身：签发方（auth）与校验方（gateway）必须在启动日志里
        // 显示同一个指纹。不同 shell 各自随机生成一次密钥时，症状是"登录成功但接口全 401"，
        // 极难定位——指纹对一眼就能排除这一类。
        log.info("[JWT] 密钥指纹={} 长度={}字节", JwtUtils.fingerprint(secretKey),
                secretKey == null ? 0 : secretKey.getBytes(StandardCharsets.UTF_8).length);
    }
}
