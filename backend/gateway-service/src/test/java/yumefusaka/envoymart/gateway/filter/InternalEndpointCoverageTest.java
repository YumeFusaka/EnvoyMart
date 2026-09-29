package yumefusaka.envoymart.gateway.filter;

import org.junit.jupiter.api.Test;
import yumefusaka.envoymart.gateway.ControllerSources;

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
 * 反向排除清单的覆盖守卫：<b>所有 {@code /internal} 接口都必须在网关登记</b>。
 * <p>
 * <b>为什么需要这条测试而不是靠纪律</b>：{@code /orders/internal/{id}/ship} 长期不在
 * {@link JwtGatewayFilter#INTERNAL_ONLY_PREFIXES} 里，而它的实现不读身份、不校验订单归属，
 * 代码注释写着「管理侧动作，网关已屏蔽，只有服务间或运维直连可达」——
 * 那唯一的一道防线其实<b>并不存在</b>。任何登录用户带自己的 Token 调它，
 * 就能把任意订单改成已发货并写入履约单与物流轨迹。这个洞不是写错了代码，
 * 是<b>加接口时漏了一次登记</b>，而漏登记不报错、不告警、没有任何症状。
 * <p>
 * 所以这里不去测某一条规则对不对（那是 {@link JwtGatewayFilterTest} 的事），
 * 而是把「源码里有、清单里没有」变成一次构建失败：{@link #everyInternalEndpointIsRegistered()}
 * 直接扫全部控制器的源码。
 * <p>
 * 扫源码而不是扫运行期映射的理由见 {@link yumefusaka.envoymart.gateway.ControllerSources}。
 */
class InternalEndpointCoverageTest {

    /** 内部接口的路径约定。新接口只要落在这个段下面就会被这条测试看到 */
    private static final String INTERNAL_SEGMENT = "/internal";

    private static final Pattern CLASS_MAPPING = Pattern.compile("@RequestMapping\\(\\s*\"([^\"]*)\"");
    private static final Pattern METHOD_MAPPING =
            Pattern.compile("@(?:Get|Post|Put|Delete|Patch)Mapping\\(\\s*\"([^\"]*)\"");

    @Test
    void everyInternalEndpointIsRegistered() {
        List<Path> controllers = ControllerSources.all();
        // 扫不到源码时必须失败而不是静默通过：一条永远绿的测试比没有测试更糟，
        // 它会让人以为这个洞已经被守住了
        assertThat(controllers)
                .as("没有找到任何控制器源码，源码扫描的路径推算错了——这条测试会变成永远通过的空壳")
                .hasSizeGreaterThan(5);

        Set<String> found = new LinkedHashSet<>();
        for (Path controller : controllers) {
            found.addAll(internalPrefixesOf(ControllerSources.read(controller)));
        }
        assertThat(found).as("一个内部接口都没扫到，正则多半写错了").isNotEmpty();

        List<String> uncovered = found.stream()
                .filter(prefix -> JwtGatewayFilter.INTERNAL_ONLY_PREFIXES.stream()
                        .noneMatch(prefix::startsWith))
                .toList();

        assertThat(uncovered)
                .as("""
                        以下内部接口没有在网关登记，等于对任何登录用户开放（写接口还会直接改数据）。
                        请在 JwtGatewayFilter.INTERNAL_ONLY_PREFIXES 里补上一行，并写清理由：%s""",
                        uncovered)
                .isEmpty();
    }

    /**
     * 从一个控制器源码里算出它的内部接口前缀。
     * <p>
     * 类级映射与方法级映射拼起来，再截到 {@code /internal} 那一段为止：
     * {@code @RequestMapping("/orders")} + {@code @PostMapping("/internal/{id}/ship")}
     * 得到 {@code /orders/internal/}。这样算出来的前缀天然覆盖该段下未来新增的所有方法，
     * 不需要每加一个方法就改一次清单。
     */
    private static Set<String> internalPrefixesOf(String source) {
        Set<String> prefixes = new LinkedHashSet<>();
        List<String> bases = matches(CLASS_MAPPING, source);
        List<String> methods = matches(METHOD_MAPPING, source);
        for (String base : bases.isEmpty() ? List.of("") : bases) {
            // 类级映射本身也可能就是内部路径（如 /knowledge/internal、/ai/internal/knowledge）
            prefixOf(base).ifPresent(prefixes::add);
            for (String method : methods) {
                prefixOf(base + method).ifPresent(prefixes::add);
            }
        }
        return prefixes;
    }

    /** {@code /orders/internal/{id}/ship} 到 {@code /orders/internal/}；不含内部段则返回空 */
    private static Optional<String> prefixOf(String fullPath) {
        int at = fullPath.indexOf(INTERNAL_SEGMENT);
        if (at < 0) {
            return Optional.empty();
        }
        // 段边界要对齐：/internalized 这类前缀不能被当成 /internal
        int after = at + INTERNAL_SEGMENT.length();
        if (after < fullPath.length() && fullPath.charAt(after) != '/') {
            return Optional.empty();
        }
        return Optional.of(fullPath.substring(0, after) + "/");
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
