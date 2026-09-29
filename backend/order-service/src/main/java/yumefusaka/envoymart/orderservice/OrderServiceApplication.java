package yumefusaka.envoymart.orderservice;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "yumefusaka.envoymart")
@MapperScan("yumefusaka.envoymart.orderservice.mapper")
@EnableDiscoveryClient
@EnableFeignClients
// 超时关单任务需要它。漏了这个注解，@Scheduled 不会报错、也不会执行 ——
// 表现为「未支付订单一直占着库存」，而日志里什么都没有
@EnableScheduling
public class OrderServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}
