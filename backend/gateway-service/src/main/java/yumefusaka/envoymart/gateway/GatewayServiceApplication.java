package yumefusaka.envoymart.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import yumefusaka.envoymart.common.properties.JwtProperties;

@SpringBootApplication(scanBasePackages = "yumefusaka.envoymart")
@EnableDiscoveryClient
@EnableConfigurationProperties(JwtProperties.class)
public class GatewayServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayServiceApplication.class, args);
    }
}
