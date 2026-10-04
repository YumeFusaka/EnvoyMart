package yumefusaka.envoymart.paymentservice;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "yumefusaka.envoymart")
@MapperScan("yumefusaka.envoymart.paymentservice.mapper")
@EnableDiscoveryClient
@EnableFeignClients
// 发件箱投递器靠它扫描待发送的行。漏了它 @Scheduled 不报错也不执行 ——
// 表现为「事件全部堆在 event_outbox 里没人发」，而日志里什么都没有
@EnableScheduling
public class PaymentServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(PaymentServiceApplication.class, args);
    }
}
