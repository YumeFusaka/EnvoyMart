package yumefusaka.envoymart.authservice;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import yumefusaka.envoymart.common.properties.JwtProperties;

@SpringBootApplication(scanBasePackages = "yumefusaka.envoymart")
@MapperScan("yumefusaka.envoymart.authservice.mapper")
@EnableConfigurationProperties(JwtProperties.class)
public class AuthServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuthServiceApplication.class, args);
    }
}
