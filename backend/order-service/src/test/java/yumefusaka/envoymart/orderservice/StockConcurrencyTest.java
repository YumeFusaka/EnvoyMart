package yumefusaka.envoymart.orderservice;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 防超卖的并发实测 —— 30 个用户同时抢 10 件库存，只应有 10 单成功。
 * <p>
 * <b>这是一个端到端测试</b>：它不打桩，直接通过 HTTP 打真实的 order-service 与
 * product-service，因此需要完整环境（两个服务 + MySQL + Redis + {@code INTERNAL_TOKEN}）。
 * 用显式开关 {@code RUN_STOCK_CONCURRENCY_TEST=true} 控制，不进 CI——
 * 与 {@code RetrievalComparisonTest} 同样的理由：它验证的是"真实环境下会怎样"，
 * 而那种结论无法在不具备该环境的构建机上复现。
 * <p>
 * <b>为什么必须真并发</b>：这个缺陷只在竞态窗口里出现。串行调用时，
 * 「读库存 → 判断够不够 → 扣减」每一步都正确，测出来永远是绿的——
 * 只有让 30 个请求真正同时抵达，才能证明防超卖真的生效。
 * <p>
 * <b>⚠️ 跑之前先关掉 SkyWalking：</b>{@code ENVOYMART_SKYWALKING=off ./run-local.sh}。
 * javaagent 给每个方法插桩，单次请求的耗时会明显变长，而本测试的请求要先等
 * Redisson 锁（3 秒）——耗时一涨，后面排队的请求就等不到锁了。实测**成功数会从
 * 稳定的 10 掉到 3**，看上去像防超卖坏了，其实只是观测开销。
 * 只改测试环境、不动业务代码，是这里唯一正确的处理。
 * <p>
 * 运行：
 * <pre>
 * RUN_STOCK_CONCURRENCY_TEST=true INTERNAL_TOKEN=xxx \
 *   mvn -pl order-service test -Dtest=StockConcurrencyTest
 * </pre>
 * 可选环境变量：{@code ORDER_SERVICE_URL}（默认 127.0.0.1:9003）、
 * {@code PRODUCT_SERVICE_URL}（默认 127.0.0.1:9002）、
 * {@code STOCK_TEST_PRODUCT_ID}（默认 1）。
 */
@EnabledIfEnvironmentVariable(named = "RUN_STOCK_CONCURRENCY_TEST", matches = "true")
class StockConcurrencyTest {

    private static final String ORDER_URL = env("ORDER_SERVICE_URL", "http://127.0.0.1:9003");
    private static final String PRODUCT_URL = env("PRODUCT_SERVICE_URL", "http://127.0.0.1:9002");
    private static final String INTERNAL_TOKEN = System.getenv("INTERNAL_TOKEN");
    private static final long PRODUCT_ID = Long.parseLong(env("STOCK_TEST_PRODUCT_ID", "1"));

    /** 并发用户数 —— 明显大于目标库存，才制造得出"抢" */
    private static final int CONCURRENCY = 30;
    /** 目标库存 —— 测试会把商品库存调到这个值 */
    private static final int TARGET_STOCK = 10;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    @BeforeAll
    static void 校验环境() {
        assertThat(INTERNAL_TOKEN)
                .as("需要 INTERNAL_TOKEN：order-service 的接口要求服务间凭证，缺失时一律 401")
                .isNotBlank();
    }

    @Test
    void 三十个用户并发抢十件库存_只应有十单成功且库存归零() throws Exception {
        int before = readStock();
        System.out.printf("[并发压测] 商品 %d 当前库存=%d，目标库存=%d%n", PRODUCT_ID, before, TARGET_STOCK);
        adjustStockTo(TARGET_STOCK);
        assertThat(readStock()).as("库存调整失败，后续断言无意义").isEqualTo(TARGET_STOCK);

        // 每个用户购物车里放 1 件同款商品 —— 购物车按 userId 隔离，互不干扰
        List<String> users = new ArrayList<>(CONCURRENCY);
        for (int i = 0; i < CONCURRENCY; i++) {
            String userId = "stock-test-" + i;
            users.add(userId);
            addToCart(userId, PRODUCT_ID, 1);
        }

        // 同步屏障：所有线程就位后同时发起，避免"谁先启动谁先抢"退化成串行
        CyclicBarrier barrier = new CyclicBarrier(CONCURRENCY);
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>(CONCURRENCY);

        long startedAt = System.nanoTime();
        for (String userId : users) {
            Callable<Void> task = () -> {
                barrier.await();
                if (checkout(userId)) {
                    success.incrementAndGet();
                } else {
                    rejected.incrementAndGet();
                }
                return null;
            };
            futures.add(pool.submit(task));
        }
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

        int finalStock = readStock();
        System.out.printf("[并发压测] 并发=%d 目标库存=%d 成功=%d 拒绝=%d 最终库存=%d 耗时=%dms%n",
                CONCURRENCY, TARGET_STOCK, success.get(), rejected.get(), finalStock, elapsedMs);

        assertThat(success.get())
                .as("成功订单数必须恰好等于库存数——多了是超卖，少了是少卖")
                .isEqualTo(TARGET_STOCK);
        assertThat(rejected.get())
                .as("其余请求应被明确拒绝（库存不足），而不是静默失败")
                .isEqualTo(CONCURRENCY - TARGET_STOCK);
        assertThat(finalStock)
                .as("库存必须归零，绝不能为负")
                .isZero();
    }

    // ==================== 与环境交互 ====================

    /** 用内部接口把库存调到目标值：多了就扣，少了就补 */
    private static void adjustStockTo(int target) throws Exception {
        int current = readStock();
        while (current > target) {
            call(PRODUCT_URL + "/products/stock/deduct",
                    "{\"productId\":" + PRODUCT_ID + ",\"quantity\":1}", null);
            current--;
        }
        while (current < target) {
            call(PRODUCT_URL + "/products/stock/restore",
                    "{\"productId\":" + PRODUCT_ID + ",\"quantity\":1}", null);
            current++;
        }
    }

    private static int readStock() throws Exception {
        String body = call(PRODUCT_URL + "/products/" + PRODUCT_ID, null, null);
        Matcher m = Pattern.compile("\"stock\"\\s*:\\s*(\\d+)").matcher(body);
        assertThat(m.find()).as("响应里找不到 stock 字段：" + body).isTrue();
        return Integer.parseInt(m.group(1));
    }

    private static void addToCart(String userId, long productId, int quantity) throws Exception {
        boolean ok = call(ORDER_URL + "/cart/items",
                "{\"productId\":" + productId + ",\"quantity\":" + quantity + "}", userId) != null;
        assertThat(ok).as("加购失败，用户=" + userId).isTrue();
    }

    /**
     * 下单；{@code true} 表示业务成功。
     * <p>
     * 本项目用 {@code HTTP 200 + body.code} 表达业务错误——只看状态码会把"库存不足"
     * 当成下单成功，这个测试就永远测不出超卖。
     */
    private static boolean checkout(String userId) throws Exception {
        String body = "{\"recipientName\":\"压测\",\"recipientPhone\":\"13800000000\","
                + "\"address\":\"压测地址\"}";
        return call(ORDER_URL + "/orders/checkout", body, userId) != null;
    }

    /**
     * 发一次请求。
     *
     * @param userId 非空时带 {@code X-User-Id}；为 null 时是服务间调用
     * @return 业务成功时返回响应体，否则返回 null
     */
    private static String call(String url, String body, String userId) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .header("X-Internal-Token", INTERNAL_TOKEN);
        if (userId != null) {
            b.header("X-User-Id", userId);
        }
        if (body == null) {
            b.GET();
        } else {
            b.header("Content-Type", "application/json; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        }

        HttpResponse<String> resp = HTTP.send(b.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200 || !resp.body().contains("\"code\":200")) {
            return null;
        }
        return resp.body();
    }

    private static String env(String key, String fallback) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? fallback : v;
    }
}
