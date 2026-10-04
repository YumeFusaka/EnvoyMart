package yumefusaka.envoymart.aiservice.security;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MCP 侧旁路护栏：配额、振荡、未验证确认的观测。
 * <p>
 * 这几个边界都是「错了不会被发现」的类型——配额写松了没人报错，振荡判松了只是花钱，
 * 未验证确认不记录则永远查不出「谁在裸填 true」。所以用可注入的时钟把窗口钉死，
 * 让每条边界都有确定性断言，而不是靠「跑一会儿看看」。
 */
class McpGuardTest {

    /**
     * 可控时钟。
     * <p>
     * 起点刻意取一个<b>边界之外的真实</b>值（第 12345 毫秒），而不是 0——
     * {@code PrincipalState} 初始化窗口起点时用的是 {@code System.currentTimeMillis()}，
     * 若把假时钟停在 0，第一次 {@code check} 会算出「已经跨过窗口」而白白重置一次，
     * 窗口边界的断言就随之失真。用一个正常的正数，假时钟与真实实现才同构。
     */
    private final AtomicLong now = new AtomicLong(12_345L);

    private McpCallGuard guard(int quotaPerMinute) {
        return new McpCallGuard(quotaPerMinute, 60_000L, now::get);
    }

    private Map<String, Object> args(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            map.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return map;
    }

    @Test
    void 超过每分钟配额被拒且拒绝前放行者正好是配额数() {
        McpCallGuard guard = guard(3);

        assertThat(guard.check("u1001", "order_query", args("orderNo", "A1"))).isEqualTo(McpCallGuard.Verdict.ALLOWED);
        assertThat(guard.check("u1001", "order_query", args("orderNo", "A2"))).isEqualTo(McpCallGuard.Verdict.ALLOWED);
        assertThat(guard.check("u1001", "order_query", args("orderNo", "A3"))).isEqualTo(McpCallGuard.Verdict.ALLOWED);

        assertThat(guard.check("u1001", "order_query", args("orderNo", "A4")))
                .as("第 4 次越过配额必须被拒")
                .isEqualTo(McpCallGuard.Verdict.QUOTA_EXCEEDED);
        assertThat(guard.quotaRejectionCount()).isEqualTo(1);
    }

    @Test
    void 窗口滑过之后配额重新计数() {
        McpCallGuard guard = guard(2);

        guard.check("u1001", "product_search", args("keyword", "钙片"));
        guard.check("u1001", "product_search", args("keyword", "鱼油"));
        assertThat(guard.check("u1001", "product_search", args("keyword", "维C")))
                .isEqualTo(McpCallGuard.Verdict.QUOTA_EXCEEDED);

        // 窗口是「固定窗口」，跨过 60 秒即重置。
        // 起点是窗口初始化时的时钟读数（12_345），所以要推进到 12_345 + 60_000。
        now.set(12_345L + 60_000L);

        assertThat(guard.check("u1001", "product_search", args("keyword", "维C")))
                .as("新窗口的第一条不该被上一窗口的用量连累")
                .isEqualTo(McpCallGuard.Verdict.ALLOWED);
    }

    @Test
    void 未超限的不同参数调用正常放行() {
        McpCallGuard guard = guard(10);

        assertThat(guard.check("u1001", "product_search", args("keyword", "乳清蛋白粉")))
                .isEqualTo(McpCallGuard.Verdict.ALLOWED);
        assertThat(guard.check("u1001", "product_search", args("keyword", "维生素C")))
                .isEqualTo(McpCallGuard.Verdict.ALLOWED);
        assertThat(guard.check("u1001", "product_search", args("keyword", "叶酸")))
                .isEqualTo(McpCallGuard.Verdict.ALLOWED);

        assertThat(guard.quotaRejectionCount()).isZero();
        assertThat(guard.oscillationRejectionCount()).isZero();
    }

    @Test
    void 两个调用来回震荡被拒且不等到配额用尽() {
        McpCallGuard guard = guard(100);

        assertThat(guard.check("u1001", "order_query", args("orderNo", "A1"))).isEqualTo(McpCallGuard.Verdict.ALLOWED);
        assertThat(guard.check("u1001", "logistics_query", args("orderNo", "A1"))).isEqualTo(McpCallGuard.Verdict.ALLOWED);
        assertThat(guard.check("u1001", "order_query", args("orderNo", "A1"))).isEqualTo(McpCallGuard.Verdict.ALLOWED);

        assertThat(guard.check("u1001", "logistics_query", args("orderNo", "A1")))
                .as("A→B→A→B 代表两个方向都试过又回到原点，该在配额远未用尽时就被识别")
                .isEqualTo(McpCallGuard.Verdict.OSCILLATING);
        assertThat(guard.oscillationRejectionCount()).isEqualTo(1);
        assertThat(guard.quotaRejectionCount()).isZero();
    }

    @Test
    void 三个调用依次推进不算震荡() {
        McpCallGuard guard = guard(100);

        assertThat(guard.check("u1001", "order_query", args("orderNo", "A1"))).isEqualTo(McpCallGuard.Verdict.ALLOWED);
        assertThat(guard.check("u1001", "logistics_query", args("orderNo", "A1"))).isEqualTo(McpCallGuard.Verdict.ALLOWED);
        assertThat(guard.check("u1001", "product_search", args("keyword", "钙片")))
                .as("同一次任务问几种不同工具是正常流程，不能拦")
                .isEqualTo(McpCallGuard.Verdict.ALLOWED);
        assertThat(guard.check("u1001", "product_search", args("keyword", "鱼油")))
                .as("A、B、C、C 不是交替形态")
                .isEqualTo(McpCallGuard.Verdict.ALLOWED);
    }

    @Test
    void 参数写法不同但语义相同的重复调用被视为同一步() {
        // 标点是 MCP 客户端序列化时最常见的抖动（逗号、空格、全角/半角），
        // 分词会把它当边界丢掉，于是两种写法归到同一步——真正的原地打转不能靠
        // 「换个标点」绕过。
        assertThat(McpCallGuard.signature("product_search", args("keyword", "乳清蛋白粉")))
                .isEqualTo(McpCallGuard.signature("product_search", args("keyword", "乳清，蛋白粉")));
        // 二元组分词会把两处重叠的「蛋白白粉」也算进交集，所以刻意选一组
        // 完全不共享二元组的关键词来断言「不同的查询不会撞成同一个签名」。
        assertThat(McpCallGuard.signature("product_search", args("keyword", "乳清蛋白粉")))
                .isNotEqualTo(McpCallGuard.signature("product_search", args("keyword", "叶黄素")));
        // 但语义不同的查询不能撞到一起
        assertThat(McpCallGuard.signature("product_search", args("keyword", "乳清蛋白粉")))
                .isNotEqualTo(McpCallGuard.signature("product_search", args("keyword", "维C")));
    }

    @Test
    void 授权校验失败只计数不改变配额放行结果() {
        McpCallGuard guard = guard(10);

        // 升级后它统计的是「被拒掉的高危调用」：授权判定在 callHandler 里，
        // 与这里的时间窗/振荡相互独立——同一次调用可能先被判授权拒绝，
        // 也可能先被配额拒绝，两条判据各自记账、互不覆盖
        guard.recordUnverifiedConfirmation("u1001", "order_cancel");
        guard.recordUnverifiedConfirmation("api-key", "order_cancel");

        assertThat(guard.unverifiedConfirmationCount())
                .as("被拒计数是「有没有人在反复试探高危入口」的唯一证据，不能只写日志")
                .isEqualTo(2);
        assertThat(guard.check("u1001", "order_cancel", args("orderNo", "A1")))
                .as("授权拒绝不消费护栏的裁决——护栏只管时间窗与振荡")
                .isEqualTo(McpCallGuard.Verdict.ALLOWED);
    }

    @Test
    void 不同身份的配额互不影响() {
        McpCallGuard guard = guard(1);

        assertThat(guard.check("u1001", "order_query", args("orderNo", "A1"))).isEqualTo(McpCallGuard.Verdict.ALLOWED);
        assertThat(guard.check("u1001", "order_query", args("orderNo", "A2")))
                .isEqualTo(McpCallGuard.Verdict.QUOTA_EXCEEDED);

        assertThat(guard.check("u2002", "order_query", args("orderNo", "A1")))
                .as("一个用户刷爆配额，不能让另一个用户跟着被拒")
                .isEqualTo(McpCallGuard.Verdict.ALLOWED);
        assertThat(guard.check(McpAuthFilter.API_KEY_PRINCIPAL, "product_search", args("keyword", "钙片")))
                .as("API Key 是独立主体，与 JWT 用户分开计数")
                .isEqualTo(McpCallGuard.Verdict.ALLOWED);
        assertThat(guard.check("u1001", "order_query", args("orderNo", "A1")))
                .as("u1001 自己的配额没有被别人重置")
                .isEqualTo(McpCallGuard.Verdict.QUOTA_EXCEEDED);
    }

    @Test
    void 身份缺失时归入匿名桶而不是跳过护栏() {
        McpCallGuard guard = guard(1);

        assertThat(guard.check(null, "order_query", args("orderNo", "A1"))).isEqualTo(McpCallGuard.Verdict.ALLOWED);
        assertThat(guard.check(null, "order_query", args("orderNo", "A2")))
                .as("没有身份不等于没有配额，否则漏掉身份校验就顺带绕过限流")
                .isEqualTo(McpCallGuard.Verdict.QUOTA_EXCEEDED);
        assertThat(guard.check("u1001", "order_query", args("orderNo", "A1")))
                .as("匿名桶不能连累真实用户")
                .isEqualTo(McpCallGuard.Verdict.ALLOWED);
    }

    @Test
    void 配额必须为正数() {
        assertThatThrownBy(() -> new McpCallGuard(0, 60_000L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new McpCallGuard(1, 0L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
