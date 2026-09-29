package yumefusaka.envoymart.gateway;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 路由覆盖守卫：<b>控制器写了，网关就必须有路由</b>。
 * <p>
 * <b>为什么需要它</b>：{@code CategoryController} 的 {@code /categories/tree} 与 {@code /brands}
 * 两个接口一直存在于源码里，控制器注释甚至写着「公开读，不需要登录（见网关的 PUBLIC_RULES）」——
 * 但网关的 routes 里从来没有这两个前缀，PUBLIC_RULES 里也没有。前端每次进商城都拿 404，
 * 分类导航与品牌筛选长期是空的。
 * <p>
 * 这个洞和 {@link yumefusaka.envoymart.gateway.filter.InternalEndpointCoverageTest} 守的是同一类问题，
 * 只是方向相反：那个是「接口存在于源码、却没被拦」，这个是「接口存在于源码、却根本到不了」。
 * 两者共同的特征是<b>加东西时漏了一次登记</b>，而漏登记不报错、不告警、没有症状——
 * 页面照样打开，只是某块区域永远空着。靠人记不可靠，所以让构建失败。
 * <p>
 * 与那条测试共用 {@link ControllerSources} 的源码扫描：扫源码而不是运行期映射的理由见该类注释。
 */
class RouteCoverageTest {

    private static final Pattern CLASS_MAPPING = Pattern.compile("@RequestMapping\\(\\s*\"([^\"]*)\"");
    private static final Pattern METHOD_MAPPING =
            Pattern.compile("@(?:Get|Post|Put|Delete|Patch)Mapping\\(\\s*\"?([^\")]*)\"?\\)");
    /**
     * 网关 routes 里的 Path 断言。一个 Path 内用逗号分隔的多个模式是「或」的关系，要拆开。
     * <p>
     * 行首锚定到 {@code - Path=}（只允许前置空白）：<b>被注释掉的路由不算路由</b>。
     * 不锚定的话 {@code #   - Path=/xxx/**} 也会被当成已配置，
     * 于是一条停用的路由能让守卫测试继续变绿——实测就是这样，
     * 把新加的路由注释掉后这条测试仍然通过。
     */
    private static final Pattern PATH_PREDICATE = Pattern.compile("^\\s*-\\s*Path=([^\\n]+)", Pattern.MULTILINE);

    @Test
    void everyControllerPrefixHasAGatewayRoute() {
        Set<String> prefixes = controllerPrefixes();
        // 扫不到就失败，而不是静默通过：一条永远绿的测试比没有测试更糟
        assertThat(prefixes)
                .as("一个控制器前缀都没扫到，正则或源码路径推算错了——这条测试会变成永远通过的空壳")
                .hasSizeGreaterThan(4);

        Set<String> routed = routedPrefixes();
        assertThat(routed).as("一条路由都没解析出来，YAML 解析多半错了").hasSizeGreaterThan(4);

        List<String> uncovered = prefixes.stream()
                .filter(prefix -> routed.stream().noneMatch(prefix::startsWith))
                .toList();

        assertThat(uncovered)
                .as("""
                        以下前缀在控制器源码里有接口，但网关没有配路由——请求会以 404 结束，
                        前端那块功能静默失效（不报错、不影响其它链路，所以很难被发现）。
                        请在 gateway-service 的 spring.cloud.gateway.server.webflux.routes 里补上：%s""",
                        uncovered)
                .isEmpty();
    }

    /**
     * 全部控制器的<b>顶层路径段</b>。
     * <p>
     * 取到第一段而不是完整路径：网关的路由是按前缀配的（{@code /products/**}），
     * 拿完整路径去比会把「同一个前缀下的新方法」全部误报成没路由，
     * 而实际上一个前缀有一条路由就够。一个前缀一条路由，正是本项目所有路由的形态。
     */
    private static Set<String> controllerPrefixes() {
        Set<String> prefixes = new LinkedHashSet<>();
        for (Path controller : ControllerSources.all()) {
            String source = ControllerSources.read(controller);
            List<String> bases = matches(CLASS_MAPPING, source);
            List<String> methods = matches(METHOD_MAPPING, source);
            for (String base : bases.isEmpty() ? List.of("") : bases) {
                topSegment(base).ifPresent(prefixes::add);
                for (String method : methods) {
                    topSegment(base + (method.startsWith("/") ? method : "/" + method))
                            .ifPresent(prefixes::add);
                }
            }
        }
        return prefixes;
    }

    /** {@code /products/{id}} 到 {@code /products/}；根路径或空串返回空（首页不需要路由声明） */
    private static Optional<String> topSegment(String fullPath) {
        String path = fullPath.trim();
        if (path.isEmpty() || path.equals("/")) {
            return Optional.empty();
        }
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        int slash = path.indexOf('/', 1);
        String segment = slash < 0 ? path : path.substring(0, slash);
        return segment.length() > 1 ? Optional.of(segment + "/") : Optional.empty();
    }

    /** 网关 routes 里声明过的全部路径前缀，逗号分隔的拆成多条 */
    private static Set<String> routedPrefixes() {
        Set<String> prefixes = new LinkedHashSet<>();
        Matcher m = PATH_PREDICATE.matcher(ControllerSources.gatewayRoutes());
        while (m.find()) {
            for (String raw : m.group(1).split(",")) {
                String pattern = raw.trim();
                if (pattern.isEmpty()) {
                    continue;
                }
                // /products/** -> /products/ ；/categories/** -> /categories/
                int star = pattern.indexOf('*');
                String prefix = star < 0 ? pattern : pattern.substring(0, star);
                if (!prefix.startsWith("/")) {
                    prefix = "/" + prefix;
                }
                if (!prefix.endsWith("/")) {
                    prefix = prefix + "/";
                }
                prefixes.add(prefix);
            }
        }
        return prefixes;
    }

    private static List<String> matches(Pattern pattern, String source) {
        List<String> out = new ArrayList<>();
        Matcher m = pattern.matcher(source);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }
}
