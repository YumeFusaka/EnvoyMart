package yumefusaka.envoymart.gateway;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 跨模块的控制器源码扫描 —— 给「约定必须被守住」那类测试共用。
 * <p>
 * 为什么扫源码而不是扫运行期映射：这些控制器分属六个 Maven 模块，
 * gateway-service 的测试类路径上根本没有它们——要扫运行期就得把六个服务全拖进测试依赖。
 * 源码扫描认的是同一份事实（{@code @*Mapping} 与注解），代价小得多。
 */
public final class ControllerSources {

    private ControllerSources() {
    }

    /** 全部 {@code *Controller.java} 的源码文件。目录按 {@code *-service} 找，新增服务会自动纳入 */
    public static List<Path> all() {
        Path backend = backendRoot();
        try (Stream<Path> services = Files.list(backend)) {
            List<Path> out = new ArrayList<>();
            for (Path service : services.filter(Files::isDirectory).toList()) {
                Path src = service.resolve("src/main/java");
                if (!Files.isDirectory(src)) {
                    continue;
                }
                try (Stream<Path> files = Files.walk(src)) {
                    files.filter(p -> p.getFileName().toString().endsWith("Controller.java"))
                            .forEach(out::add);
                }
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 网关路由配置的源码文本。
     * <p>
     * 读文本而不是拿 {@code Environment} 反序列化：测试要校验的正是「这份 YAML 里到底写了哪些
     * 前缀」，解析成对象再回头看就绕了一圈，而且 profile 合并后的结果会掩盖「哪个 profile 写的」。
     */
    public static String gatewayRoutes() {
        Path routes = backendRoot().resolve("gateway-service/src/main/resources/application.yml");
        if (!Files.isRegularFile(routes)) {
            throw new IllegalStateException("网关配置文件不在预期位置：" + routes);
        }
        return read(routes);
    }

    /** 类级映射：只认第一个参数是字面量的写法，{@code @RequestMapping(method = ...)} 不在此列 */
    private static final Pattern CLASS_MAPPING = Pattern.compile("@RequestMapping\\(\\s*\"([^\"]*)\"");
    private static final Pattern METHOD_MAPPING =
            Pattern.compile("@(?:Get|Post|Put|Delete|Patch)Mapping\\(\\s*\"?([^\")]*)\"?\\)");
    private static final String ADMIN_SEGMENT = "/admin";

    /**
     * 全部控制器里出现过的 {@code /admin} 路径，<b>截到 {@code /admin} 那一段为止</b>。
     * <p>
     * 截断而不是取完整路径：网关与 {@code AdminGuardInterceptor} 的判据都是「路径段等于 admin」，
     * 一条 {@code /products/admin} 就覆盖了该段下未来新增的所有方法，
     * 不需要每加一个方法就回去改一次清单 —— 而「回去改一次清单」正是这类守卫会失效的原因。
     * <p>
     * 段边界要对齐：{@code /knowledge/documents/admin-guide} 这类把 admin 当普通词用的公开资源
     * 不能被算进来（网关的判据同样如此）。
     */
    public static Set<String> adminPaths() {
        Set<String> out = new LinkedHashSet<>();
        for (Path controller : all()) {
            String source = read(controller);
            List<String> bases = matches(CLASS_MAPPING, source);
            List<String> methods = matches(METHOD_MAPPING, source);
            for (String base : bases.isEmpty() ? List.of("") : bases) {
                truncatedAtAdmin(base).ifPresent(out::add);
                for (String method : methods) {
                    truncatedAtAdmin(base + (method.startsWith("/") ? method : "/" + method))
                            .ifPresent(out::add);
                }
            }
        }
        return out;
    }

    /** {@code /tickets/admin/tickets} 到 {@code /tickets/admin}；不含 admin 段的返回空 */
    private static Optional<String> truncatedAtAdmin(String fullPath) {
        int at = fullPath.indexOf(ADMIN_SEGMENT);
        if (at < 0) {
            return Optional.empty();
        }
        int after = at + ADMIN_SEGMENT.length();
        if (after < fullPath.length() && fullPath.charAt(after) != '/') {
            return Optional.empty();
        }
        return Optional.of(fullPath.substring(0, after));
    }

    private static List<String> matches(Pattern pattern, String source) {
        List<String> out = new ArrayList<>();
        Matcher m = pattern.matcher(source);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    /**
     * 网关限流规则类的源码文本。
     * <p>
     * 规则用 Java 声明在 {@code GatewaySentinelConfig.loadRules()} 里，扫源码的理由同 {@link #all()}：
     * 测试要校验的就是「这个文件里到底登记了哪些 route id」。
     */
    public static String gatewaySentinelConfig() {
        Path config = backendRoot().resolve(
                "gateway-service/src/main/java/yumefusaka/envoymart/gateway/config/GatewaySentinelConfig.java");
        if (!Files.isRegularFile(config)) {
            throw new IllegalStateException("网关限流规则文件不在预期位置：" + config);
        }
        return read(config);
    }

    /**
     * 从 surefire 的工作目录往上找到 backend 根。
     * <p>
     * surefire 的工作目录是模块目录（{@code backend/gateway-service}），所以父目录就是根。
     * 顺手校验它长得像 backend——推算错了要当场报错，不能让它去扫一个不相干目录然后报「零个控制器」
     */
    private static Path backendRoot() {
        Path module = Paths.get("").toAbsolutePath();
        Path backend = module.getParent();
        if (backend == null || !Files.isDirectory(backend.resolve("gateway-service"))
                || !Files.isDirectory(backend.resolve("order-service"))) {
            throw new IllegalStateException("推算出的 backend 根目录不像 backend：" + backend
                    + "（当前工作目录 " + module + "）");
        }
        return backend;
    }
}
