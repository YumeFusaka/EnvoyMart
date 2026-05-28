package yumefusaka.envoymart.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import yumefusaka.envoymart.common.properties.JwtProperties;

@SpringBootApplication(scanBasePackages = "yumefusaka.envoymart")
@EnableConfigurationProperties(JwtProperties.class)
public class GatewayServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayServiceApplication.class, args);
    }
}
