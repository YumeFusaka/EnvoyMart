package yumefusaka.envoymart.promotionservice;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "yumefusaka.envoymart")
@MapperScan("yumefusaka.envoymart.promotionservice.mapper")
@EnableDiscoveryClient
// 优惠券过期任务需要它。漏了这个注解 @Scheduled 既不报错也不执行
@EnableScheduling
public class PromotionServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(PromotionServiceApplication.class, args);
    }
}
