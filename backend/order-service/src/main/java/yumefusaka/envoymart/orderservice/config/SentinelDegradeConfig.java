package yumefusaka.envoymart.orderservice.config;

import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.alibaba.csp.sentinel.slots.block.degrade.circuitbreaker.CircuitBreakerStrategy;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 熔断规则 —— 下游不可用时快速失败，而不是让调用方线程耗在超时上。
 * <p>
 * <b>与限流的区别</b>：网关那边的 {@code GatewayFlowRule} 是<b>限流</b>（控制进入的流量），
 * 这里是<b>熔断</b>（下游已经不可用时不再继续打它）。两者解决的不是一回事——
 * 熔断之前这个项目只有超时（connect 2s / read 5s），意味着下游挂掉后每个请求
 * 仍要干等 5 秒才失败，并发一上来调用方线程先被占满。
 * <p>
 * <b>规则写在代码里而不是控制台</b>：本地没有常驻的 Sentinel 控制台，
 * 规则放内存重启即丢；写在这里至少每次启动都是可预期的。
 * 生产上应当接到 Nacos 配置源做动态推送。
 * <p>
 * <b>熔断后不降级为"成功"</b>：库存扣减没有"降级成功"这个选项——
 * 扣不了就是不能下单。所以调用点捕获 {@code BlockException} 后抛业务异常快速失败，
 * 与"库存不足"一样让本次下单失败，只是错误信息不同（前者可重试，后者不可）。
 */
@Slf4j
@Configuration
public class SentinelDegradeConfig {

    /** 资源名，与 OrderDomainServiceImpl 里 SphU.entry 的取值必须一致 */
    public static final String RESOURCE_DEDUCT_STOCK = "product.deductStock";

    /** 慢调用阈值：2 秒。正常扣减是毫秒级，超过它说明下游已经在挣扎 */
    private static final long SLOW_RT_MILLIS = 2000;
    /** 统计窗口 */
    private static final int STAT_INTERVAL_MS = 10_000;
    /** 熔断时长：10 秒后放一个请求试探 */
    private static final int TIME_WINDOW_SECONDS = 10;
    /** 低于这个请求数不做统计——样本太少时比例没有意义 */
    private static final int MIN_REQUEST_AMOUNT = 5;

    @PostConstruct
    public void loadRules() {
        DegradeRule slowCall = new DegradeRule(RESOURCE_DEDUCT_STOCK)
                .setGrade(CircuitBreakerStrategy.SLOW_REQUEST_RATIO.getType())
                .setCount(SLOW_RT_MILLIS)
                .setSlowRatioThreshold(0.5)
                .setMinRequestAmount(MIN_REQUEST_AMOUNT)
                .setStatIntervalMs(STAT_INTERVAL_MS)
                .setTimeWindow(TIME_WINDOW_SECONDS);

        DegradeRule errorRatio = new DegradeRule(RESOURCE_DEDUCT_STOCK)
                .setGrade(CircuitBreakerStrategy.ERROR_RATIO.getType())
                .setCount(0.5)
                .setMinRequestAmount(MIN_REQUEST_AMOUNT)
                .setStatIntervalMs(STAT_INTERVAL_MS)
                .setTimeWindow(TIME_WINDOW_SECONDS);

        DegradeRuleManager.loadRules(List.of(slowCall, errorRatio));
        log.info("[Sentinel] 已加载熔断规则: 资源={} 慢调用阈值={}ms 异常比例=0.5 熔断={}s",
                RESOURCE_DEDUCT_STOCK, SLOW_RT_MILLIS, TIME_WINDOW_SECONDS);
    }
}
