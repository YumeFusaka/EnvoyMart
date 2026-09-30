package yumefusaka.envoymart.common.web;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 请求标识的取值规则。
 * <p>
 * 这个类的失效形态是<b>静默的</b>：一个带换行的标识被原样接受，日志里就凭空多出一行
 * 看起来完全正常的记录（日志注入）；一个超长的标识被接受，每一行日志都被撑开。
 * 两者都不会报错，所以规则必须由测试钉住。
 */
class RequestIdTest {

    @Test
    void 客户端给的合法标识原样保留() {
        assertThat(RequestId.resolve("abc-123_XY.z")).isEqualTo("abc-123_XY.z");
    }

    @Test
    void 恰好64字符的标识算合法() {
        String boundary = "a".repeat(64);
        assertThat(RequestId.resolve(boundary)).isEqualTo(boundary);
    }

    @Test
    void 超长标识被换掉而不是截断() {
        // 截断会造出一个与任何客户端都对不上的标识，还可能在截断处与别人的标识撞上
        String tooLong = "a".repeat(65);
        String resolved = RequestId.resolve(tooLong);
        assertThat(resolved).isNotEqualTo(tooLong).hasSize(16);
    }

    @Test
    void 带换行的标识被拒绝_否则可以往日志里插伪行() {
        String injected = "abc\n2026-01-01 [ERROR] 数据库连接失败";
        assertThat(RequestId.resolve(injected)).hasSize(16);
    }

    @Test
    void 带空格与中文的标识被拒绝() {
        assertThat(RequestId.resolve("abc def")).hasSize(16);
        assertThat(RequestId.resolve("请求一")).hasSize(16);
    }

    @Test
    void 缺失的标识会新发一个() {
        assertThat(RequestId.resolve(null)).hasSize(16);
        assertThat(RequestId.resolve("")).hasSize(16);
    }

    @Test
    void 新发的标识互不相同() {
        Set<String> generated = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            generated.add(RequestId.generate());
        }
        assertThat(generated).hasSize(100);
        assertThat(generated).allMatch(id -> id.matches("[0-9a-f]{16}"));
    }

    @Test
    void current取的是MDC里的值_没有请求上下文时为空() {
        assertThat(RequestId.current()).isNull();
        MDC.put(RequestId.MDC_KEY, "deadbeefdeadbeef");
        try {
            assertThat(RequestId.current()).isEqualTo("deadbeefdeadbeef");
        } finally {
            MDC.remove(RequestId.MDC_KEY);
        }
        assertThat(RequestId.current()).isNull();
    }

    @Test
    void 换到别的线程后仍读得到提交线程的标识() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            MDC.put(RequestId.MDC_KEY, "carried-over");
            AtomicReference<String> seen = new AtomicReference<>();
            // 池化线程本身不带任何上下文，标识只能来自继承——
            // 一次问答里工具是在别的线程上执行的，断在这里就等于整段链路没有标识
            pool.submit(RequestId.inherit(() -> seen.set(RequestId.current()))).get();
            assertThat(seen.get()).isEqualTo("carried-over");
        } finally {
            pool.shutdownNow();
            MDC.remove(RequestId.MDC_KEY);
        }
    }

    @Test
    void 执行完把目标线程原来的上下文还回去() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            // 池化线程先有自己的上下文（上一次任务留下的或它自己的业务）
            pool.submit(() -> MDC.put(RequestId.MDC_KEY, "pool-thread-own")).get();

            MDC.put(RequestId.MDC_KEY, "another-request");
            AtomicReference<String> inside = new AtomicReference<>();
            pool.submit(RequestId.inherit(() -> inside.set(RequestId.current()))).get();
            MDC.remove(RequestId.MDC_KEY);

            assertThat(inside.get()).isEqualTo("another-request");

            AtomicReference<String> after = new AtomicReference<>();
            pool.submit(() -> after.set(RequestId.current())).get();
            assertThat(after.get())
                    .as("继承执行不能把目标线程原有的上下文冲掉——池化线程在任务之间是复用的")
                    .isEqualTo("pool-thread-own");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void 任务抛异常同样把上下文还回去() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            MDC.put(RequestId.MDC_KEY, "will-throw");
            assertThatThrownBy(() -> pool.submit(RequestId.inherit(() -> {
                throw new IllegalStateException("炸了");
            })).get()).isInstanceOf(ExecutionException.class);
            MDC.remove(RequestId.MDC_KEY);

            AtomicReference<String> after = new AtomicReference<>();
            pool.submit(() -> after.set(RequestId.current())).get();
            assertThat(after.get()).isNull();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void 提交线程没有上下文时任务里也是空的_不继承别人的() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            pool.submit(() -> MDC.put(RequestId.MDC_KEY, "stale-from-previous-task")).get();

            AtomicReference<String> seen = new AtomicReference<>("还没被赋过值");
            pool.submit(RequestId.inherit(() -> seen.set(RequestId.current()))).get();

            assertThat(seen.get())
                    .as("发起方不在请求里（定时任务、启动期）时，任务不该看到上一个任务留下的标识")
                    .isNull();
        } finally {
            pool.shutdownNow();
        }
    }
}
