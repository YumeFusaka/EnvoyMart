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
        // 把 Seata 的类加载从首单里挪出来。
        //
        // 实测首次下单要 5.3 秒，其中约 2.6 秒花在 Seata 首次执行时懒加载的一串类
        // （InsertExecutor / Protostuff 序列化器 / undo log 解析器）上——同一进程里第二次下单
        // 就只剩几百毫秒。这 2.6 秒被调用方（ai-service）按 5 秒读超时卡住，于是
        // 「订单创建成功、客户端却读超时」，被当成失败告知用户。
        //
        // 预热用 Class.forName 触发静态初始化，而不是构造任何实例：这里不需要 Seata 的
        // 运行时对象，只需要把这批类先加载好。失败一律吞掉——预热是优化不是依赖，
        // 某个类名在新版 Seata 里改了，代价应该是「首单又慢了」而不是「服务起不来」。
        warmUpSeata();
        SpringApplication.run(OrderServiceApplication.class, args);
    }

    /**
     * 触发 Seata 关键类的静态初始化，把首次类加载从首单挪到启动期。
     * <p>
     * 类名来自线上首单的调用栈（哪个类在 2.6 秒里被加载），不是从文档抄的。
     * 对不上时静默跳过，不影响启动。
     */
    private static void warmUpSeata() {
        String[] classes = {
                "io.seata.rm.datasource.exec.InsertExecutor",
                "io.seata.rm.datasource.undo.parser.ProtostuffUndoLogParser",
                "io.seata.rm.datasource.undo.parser.JacksonUndoLogParser",
                "io.seata.rm.datasource.undo.parser.FastjsonUndoLogParser",
                "io.seata.spring.annotation.GlobalTransactionalInterceptor",
                "io.seata.tm.api.TransactionalTemplate",
        };
        for (String name : classes) {
            try {
                Class.forName(name);
            } catch (Throwable ignored) {
                // 见方法注释：类名漂移只该让首单慢回去，不该让服务起不来
            }
        }
    }
}
