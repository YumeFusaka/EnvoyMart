package yumefusaka.envoymart.gateway;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
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
