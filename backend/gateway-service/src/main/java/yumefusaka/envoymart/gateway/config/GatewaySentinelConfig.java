package yumefusaka.envoymart.gateway.config;

import com.alibaba.csp.sentinel.adapter.gateway.common.SentinelGatewayConstants;
import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayFlowRule;
import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayRuleManager;
import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

import java.util.HashSet;
import java.util.Set;

/**
 * 网关限流规则。
 * <p>
 * Sentinel 的 {@code SentinelGatewayFilter} 由 starter 自动装配，但规则本身不会凭空产生，
 * 必须在启动时显式加载，否则过滤器形同虚设。
 * <p>
 * 阈值按「一次请求的代价」分档，而不是按接口数量平均分配：
 * <ul>
 *   <li><b>ai-service 最严</b>——一次对话会触发多轮模型调用与工具执行，成本比普通读接口高出一到两个数量级；</li>
 *   <li><b>写接口次之</b>——订单、支付限流是为了保护下游的库存锁与支付渠道，不只是保护自己；</li>
 *   <li><b>读接口最宽</b>——商品、评价有 Redis 与 ES 兜着，放行成本低。</li>
 * </ul>
 * 此处是启动默认值，运行时可在 Sentinel 控制台直接调整。被拦下的请求由
 * {@link GatewayErrorHandler} 写成 429 与可读提示，不会暴露堆栈。
 * <p>
 * <b>只用路由级规则，不用参数级（{@code GatewayParamFlowItem}）。</b>
 * 按来源 IP 的配额留给上游 WAF 或业务侧做，网关这层只做路由总量。
 * <p>
 * 有一处反直觉、排查时容易带偏：这套适配器对<b>路由级</b>规则抛出的也是
 * {@code ParamFlowException}，而不是字面上更对得上的 {@code FlowException}。
 * {@code sentinel-block.log} 里逐条可查，资源名就是 route id。两者都是
 * {@code BlockException} 的子类，按父类兜住即可，不要按异常名去判断规则类型。
 */
@Slf4j
@Configuration
public class GatewaySentinelConfig {

    @PostConstruct
    public void loadRules() {
        Set<GatewayFlowRule> rules = new HashSet<>();
        rules.add(route("ai-service", 5));
        rules.add(route("order-service-orders", 20));
        rules.add(route("payment-service", 10));
        rules.add(route("auth-service", 20));
        rules.add(route("order-service-cart", 50));
        rules.add(route("review-service", 50));
        rules.add(route("product-service", 100));

        GatewayRuleManager.loadRules(rules);
        log.info("[Sentinel] 网关限流规则已加载 {} 条: {}", rules.size(),
                rules.stream()
                        .map(r -> r.getResource() + "=" + r.getCount() + "rps")
                        .sorted()
                        .toList());
    }

    /** 按路由 ID 限流：RESOURCE_MODE_ROUTE_ID 表示资源名即 application.yml 里的 route id。 */
    private GatewayFlowRule route(String routeId, double qps) {
        return new GatewayFlowRule(routeId)
                .setResourceMode(SentinelGatewayConstants.RESOURCE_MODE_ROUTE_ID)
                .setGrade(RuleConstant.FLOW_GRADE_QPS)
                .setControlBehavior(RuleConstant.CONTROL_BEHAVIOR_DEFAULT)
                .setCount(qps)
                .setIntervalSec(1);
    }
}
