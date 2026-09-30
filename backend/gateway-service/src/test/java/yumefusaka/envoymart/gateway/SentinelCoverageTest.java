package yumefusaka.envoymart.gateway;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 限流覆盖守卫：<b>路由配了，Sentinel 就必须有规则</b>。
 * <p>
 * <b>为什么需要它</b>：规则不会凭空产生，是 {@code GatewaySentinelConfig} 里一行行
 * {@code rules.add(route(...))} 加出来的。加路由时漏加规则不报错、不告警、没有任何症状 ——
 * 那条路由只是从此不限流了，而 Sentinel 控制台上也看不出少了什么（没配规则的资源
 * 根本不会出现在那里）。实测漏了五条：类目树、售后、工单、优惠券、知识检索。
 * <p>
 * 与 {@link RouteCoverageTest} 是同一族的第三次登记：那个守「控制器写了路由就得有」，
 * 这个守「路由写了规则就得有」。共同特征是<b>加东西时漏了一次登记</b>，靠人记不可靠，
 * 所以让构建失败。
 * <p>
 * 反向也守：规则指向不存在的 route id 是<b>死配置</b>，Sentinel 收下它、
 * 永远不触发，看起来像限流生效了。改路由 id 时最容易留下这种东西。
 */
class SentinelCoverageTest {

    /** routes 段里的 {@code - id: xxx}；同一个 YAML 里其它列表用的是 uri/name，不会误命中 */
    private static final Pattern ROUTE_ID = Pattern.compile("^\\s*-\\s*id:\\s*(\\S+)", Pattern.MULTILINE);
    /** {@code rules.add(route("xxx", 5))}；只认 route( 开头的调用，别的方法名不算规则 */
    private static final Pattern FLOW_RULE = Pattern.compile("route\\(\"([^\"]+)\"");

    @Test
    void 每条路由都有对应的限流规则() {
        Set<String> routes = routeIds();
        Set<String> rules = flowRuleIds();
        // 扫不到就失败，而不是静默通过：一条永远绿的测试比没有测试更糟
        assertThat(routes).as("一条路由 id 都没解析出来，正则或 YAML 结构变了").hasSizeGreaterThan(4);
        assertThat(rules).as("一条规则都没解析出来，正则或规则类的写法变了").hasSizeGreaterThan(4);

        assertThat(routes)
                .as("""
                        以下路由没有 Sentinel 规则——它们不会被限流，而这个缺失既不报错也不告警，
                        Sentinel 控制台上也看不出来（没配规则的资源根本不出现）。
                        请在 GatewaySentinelConfig.loadRules() 里补上：%s""",
                        routes.stream().filter(route -> !rules.contains(route)).toList())
                .allSatisfy(route -> assertThat(rules).contains(route));
    }

    @Test
    void 没有指向不存在路由的死规则() {
        Set<String> routes = routeIds();
        Set<String> orphans = new LinkedHashSet<>(flowRuleIds());
        orphans.removeAll(routes);

        assertThat(orphans)
                .as("""
                        以下规则指向不存在的 route id——Sentinel 会照单收下且永不触发，
                        看起来像限流生效了。改路由 id 时最容易留下这种东西：%s""", orphans)
                .isEmpty();
    }

    private static Set<String> routeIds() {
        return matchGroup(ROUTE_ID, ControllerSources.gatewayRoutes());
    }

    private static Set<String> flowRuleIds() {
        return matchGroup(FLOW_RULE, ControllerSources.gatewaySentinelConfig());
    }

    private static Set<String> matchGroup(Pattern pattern, String source) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = pattern.matcher(source);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }
}
