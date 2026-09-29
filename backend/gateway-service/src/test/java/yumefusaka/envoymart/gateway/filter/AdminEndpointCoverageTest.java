package yumefusaka.envoymart.gateway.filter;

import org.junit.jupiter.api.Test;
import yumefusaka.envoymart.gateway.ControllerSources;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 管理接口的覆盖守卫：<b>路径里带 {@code /admin/} 的控制器必须标注 {@code @RequireAdmin}</b>。
 * <p>
 * 与 {@link InternalEndpointCoverageTest} 是同一个失败模式的两面——那边的洞是「接口加了、
 * 网关清单忘了登记」，这边的洞是「管理接口加了、{@code @RequireAdmin} 忘了标」。
 * 两者都不报错、不告警、没有任何症状，唯一的区别是被谁发现：前者被渗透测试发现，
 * 后者被用户在浏览器里发现。
 * <p>
 * <b>为什么值得一条测试</b>：{@code AdminGuardInterceptor} 只对标注了的处理器生效，
 * 它的保护范围<b>等于注解的覆盖面</b>。少标一个类，那个类就完全公开——
 * 而「公开」在这里的表现是一切正常，接口照常返回数据，没有任何迹象说明它本不该被这个用户看到。
 * 实测过：守卫与链路全部就位、测试全绿，而<b>一个真实接口都没标</b>，
 * 也就是说整套东西当时什么都没保护。这条测试就是那次留下的。
 */
class AdminEndpointCoverageTest {

    private static final String ADMIN_SEGMENT = "/admin";

    private static final Pattern CLASS_MAPPING = Pattern.compile("@RequestMapping\\(\\s*\"([^\"]*)\"");
    private static final Pattern METHOD_MAPPING =
            Pattern.compile("@(?:Get|Post|Put|Delete|Patch)Mapping\\(\\s*\"([^\"]*)\"");

    @Test
    void 路径含admin的控制器必须标注RequireAdmin() {
        List<Path> controllers = ControllerSources.all();
        assertThat(controllers)
                .as("没有找到任何控制器源码，源码扫描的路径推算错了——这条测试会变成永远通过的空壳")
                .hasSizeGreaterThan(5);

        List<String> unguarded = new ArrayList<>();
        Set<String> adminPaths = new LinkedHashSet<>();
        for (Path controller : controllers) {
            String source = ControllerSources.read(controller);
            List<String> paths = adminPathsOf(source);
            if (paths.isEmpty()) {
                continue;
            }
            adminPaths.addAll(paths);
            // 类级或任一方法级标了都算数：守卫两种都认
            if (!source.contains("@RequireAdmin")) {
                unguarded.add(controller.getFileName() + " → " + paths);
            }
        }

        assertThat(adminPaths)
                .as("一个 /admin 路径都没扫到。管理接口全被删了，或正则写错了——"
                        + "如果是前者，这条测试与它守的东西一起删掉")
                .isNotEmpty();
        assertThat(unguarded)
                .as("""
                        以下管理接口没有标 @RequireAdmin，等于对任何登录用户开放。
                        请给控制器类（或每个方法）加上 @RequireAdmin：%s""",
                        unguarded)
                .isEmpty();
    }

    /** 类级映射与方法级映射拼起来，挑出路径里含 {@code /admin} 段的那些 */
    private static List<String> adminPathsOf(String source) {
        List<String> bases = matches(CLASS_MAPPING, source);
        List<String> methods = matches(METHOD_MAPPING, source);
        List<String> out = new ArrayList<>();
        for (String base : bases.isEmpty() ? List.of("") : bases) {
            if (hasAdminSegment(base)) {
                out.add(base);
            }
            for (String method : methods) {
                String full = base + method;
                if (hasAdminSegment(full)) {
                    out.add(full);
                }
            }
        }
        return out;
    }

    /** 段边界要对齐：{@code /administrator} 不能被当成 {@code /admin} */
    private static boolean hasAdminSegment(String path) {
        int at = path.indexOf(ADMIN_SEGMENT);
        if (at < 0) {
            return false;
        }
        int after = at + ADMIN_SEGMENT.length();
        return after == path.length() || path.charAt(after) == '/';
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
