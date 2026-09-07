package yumefusaka.envoymart.common.properties;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import yumefusaka.envoymart.common.util.JwtUtils;

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
    }
}
