package yumefusaka.envoymart.aiservice.tool;

import org.junit.jupiter.api.Test;
import yumefusaka.envoymart.agent.tool.ToolResult;
import yumefusaka.envoymart.common.result.Result;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 下游调用出口的判据 —— <b>「查无此物」「这次没做成」「操作被拒」必须是三件不同的事。</b>
 * <p>
 * 这三者在线上全长一样：HTTP 200，体里一个 code，data 为 null。工具读到的都是同一个 null，
 * 于是只能按最常见的那种解释——「没有找到」。下游搜索服务抖一下，用户就被告知商品下架了。
 * <p>
 * 本类钉住的就是这条分界：读的 5xx 重试后抛出、读的 4xx 交回工具措辞、写的 5xx 一次都不重发。
 * <p>
 * <b>本类验不了什么</b>：真实 Feign 链路上这些判据还成不成立（连接、序列化、超时怎么汇到这里）。
 * 那条路由 {@code verify-downstream-retry.mjs} 覆盖——它真的停掉下游中间件再发问。
 */
class DownstreamTest {

    private static Result<String> code(int code, String data) {
        return code == 200 ? Result.success(data) : Result.error(code, "下游说：" + code);
    }

    /** 按脚本依次返回的桩，同时记下被调用了几次 */
    private static final class Script implements Supplier<Result<String>> {
        private final Result<String>[] results;
        private final AtomicInteger calls = new AtomicInteger();
        private RuntimeException boom;

        @SafeVarargs
        Script(Result<String>... results) {
            this.results = results;
        }

        @Override
        public Result<String> get() {
            int n = calls.getAndIncrement();
            if (boom != null) {
                throw boom;
            }
            return results[Math.min(n, results.length - 1)];
        }

        int calls() {
            return calls.get();
        }
    }

    // ==================== 读 ====================

    @Test
    void 读成功时一次就返回不重试() {
        Script script = new Script(code(200, "维生素D"));

        assertThat(Downstream.read("商品服务", script)).isEqualTo("维生素D");
        assertThat(script.calls()).isEqualTo(1);
    }

    @Test
    void 读遇到一次500会重试并拿到结果() {
        Script script = new Script(code(500, null), code(200, "维生素D"));

        assertThat(Downstream.read("商品服务", script))
                .as("下游抖一下不该变成用户看到的一句「没有这个商品」")
                .isEqualTo("维生素D");
        assertThat(script.calls()).isEqualTo(2);
    }

    @Test
    void 读连续500重试用尽后抛出且标成瞬时() {
        Script script = new Script(code(500, null));

        assertThatThrownBy(() -> Downstream.read("商品服务", script))
                .isInstanceOf(Downstream.DownstreamException.class)
                .hasMessageContaining("暂时不可用")
                .as("文案必须挡住「没有」——模型的默认读法是把失败读成否定")
                .hasMessageContaining("不要据此说「没有」");
        assertThat(script.calls())
                .as("总共三次尝试：首次 + 两次重试")
                .isEqualTo(3);
    }

    @Test
    void 读遇到业务码400返回null交给工具措辞() {
        Script script = new Script(code(400, null));

        // 「订单不存在」是结论不是故障：重试无用，措辞归工具（「没有找到订单 X」）
        assertThat(Downstream.read("订单服务", script)).isNull();
        assertThat(script.calls()).isEqualTo(1);
    }

    @Test
    void 读遇到连接失败包成瞬时失败() {
        Script script = new Script(code(200, "永远不会走到"));
        script.boom = new IllegalStateException("Connection refused: 内网地址不该外露");

        // 连不上与「下游回了 5xx」是两种处境，文案也该是两句：
        // 前者是压根没通上，后者是通上了、它说这次没做成
        assertThatThrownBy(() -> Downstream.read("订单服务", script))
                .isInstanceOf(Downstream.DownstreamException.class)
                .hasMessageContaining("连不上")
                .hasMessageContaining("不要据此说「没有」")
                .as("内网地址与异常细节只进日志，不进模型上下文")
                .hasMessageNotContaining("127.0.0.1")
                .hasMessageNotContaining("Connection refused");
        assertThat(script.calls())
                .as("传输层失败重发没有意义——Feign 那一层已经按安全判据试过了")
                .isEqualTo(1);
    }

    // ==================== 写 ====================

    @Test
    void 写遇到500绝不重发() {
        Script script = new Script(code(500, null));

        assertThatThrownBy(() -> Downstream.mutate("订单服务", script))
                .isInstanceOf(Downstream.DownstreamException.class)
                .as("应答丢了不代表业务没生效，说「没做成」同样是编事实")
                .hasMessageContaining("没有得到结果确认");
        assertThat(script.calls())
                .as("重发一次就是第二次取消订单")
                .isEqualTo(1);
    }

    @Test
    void 写遇到业务拒绝时抛出下游原话() {
        Script script = new Script(Result.error(409, "订单已取消，不能重复取消"));

        assertThatThrownBy(() -> Downstream.mutate("订单服务", script))
                .hasMessage("订单已取消，不能重复取消");
        assertThat(script.calls()).isEqualTo(1);
    }

    @Test
    void 写成功时返回数据() {
        Script script = new Script(code(200, "已取消"));

        assertThat(Downstream.mutate("订单服务", script)).isEqualTo("已取消");
        assertThat(script.calls()).isEqualTo(1);
    }

    // ==================== 失败 → ToolResult ====================

    @Test
    void 瞬时失败在结果里被标出来() {
        ToolResult result = Downstream.failure("商品检索",
                new Downstream.DownstreamException(500, "商品服务暂时不可用。", true));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.isTransientFailure())
                .as("日志与上层要靠这个字段分清「重试有意义」和「重试无用」")
                .isTrue();
    }

    @Test
    void 业务拒绝不算瞬时() {
        ToolResult result = Downstream.failure("订单取消",
                new Downstream.DownstreamException(409, "订单已取消，不能重复取消", false));

        assertThat(result.isTransientFailure()).isFalse();
        assertThat(result.getErrorMessage()).isEqualTo("订单已取消，不能重复取消");
    }

    @Test
    void 未知异常不回传原始消息() {
        ToolResult result = Downstream.failure("知识库检索",
                new RuntimeException("Connect to http://127.0.0.1:9200 failed"));

        assertThat(result.getErrorMessage())
                .contains("未能完成")
                .contains("RuntimeException")
                .as("内网地址会被模型原样转述给用户")
                .doesNotContain("127.0.0.1");
    }

    @Test
    void 业务异常照传原话并补上那句禁止推断() {
        ToolResult result = Downstream.failure("知识库检索",
                new IllegalStateException("milvus timeout"), "知识库没有相关内容");

        assertThat(result.getErrorMessage())
                .contains("未能完成")
                .contains("milvus timeout")
                .as("失败最容易被读成否定，而它听着像个结论")
                .contains("不要据此说「知识库没有相关内容」");
    }

    @Test
    void 写操作读超时说结果未知而不是连不上() {
        Script script = new Script(code(200, "永远不会走到"));
        // Feign 的真实形状：RetryableException 包着 SocketTimeoutException
        script.boom = new RuntimeException("Read timed out executing POST /orders/checkout",
                new java.net.SocketTimeoutException("Read timed out"));

        assertThatThrownBy(() -> Downstream.mutate("订单服务", script))
                .isInstanceOf(Downstream.DownstreamException.class)
                .as("超时意味着「请求到了、下游也许已经做了」，与「连不上（一定没做）」是两回事")
                .hasMessageContaining("无法确认")
                .hasMessageContaining("先查询当前状态")
                .hasMessageContaining("不要直接重复操作")
                .as("绝不能出现「连不上」——实测就是这句把一笔已成功的订单说成了失败")
                .hasMessageNotContaining("连不上");
    }

    @Test
    void 连不上与超时按cause链区分而不是按最外层类名() {
        // 两种失败的**最外层类名相同**（都是 RetryableException），只有 cause 链不同。
        // 按类名判会把它们混成一种，而这两种对写操作的含义正好相反
        Script refused = new Script(code(200, "x"));
        refused.boom = new RuntimeException("ConnectException", new java.net.ConnectException("Connection refused"));
        Script timeout = new Script(code(200, "x"));
        timeout.boom = new RuntimeException("SocketTimeoutException", new java.net.SocketTimeoutException("Read timed out"));

        assertThatThrownBy(() -> Downstream.mutate("订单服务", refused))
                .hasMessageContaining("连不上");
        assertThatThrownBy(() -> Downstream.mutate("订单服务", timeout))
                .hasMessageContaining("无法确认");
    }

    @Test
    void 读超时也走未知语义() {
        Script script = new Script(code(200, "x"));
        script.boom = new RuntimeException("Read timed out", new java.net.SocketTimeoutException("Read timed out"));

        // 读操作超时同样「不知道成没成」——但读是幂等的，重发安全，
        // 所以文案里保留「不要据此说「没有」」这一句，另一句「不要重复操作」只对写有意义
        assertThatThrownBy(() -> Downstream.read("订单服务", script))
                .hasMessageContaining("无法确认")
                .hasMessageContaining("不要据此说「没有」");
    }

    @Test
    void 超时异常标为瞬时() {
        ToolResult result = Downstream.failure("下单",
                Downstream.transportFailure("订单服务",
                        new RuntimeException("Read timed out", new java.net.SocketTimeoutException("Read timed out"))));

        assertThat(result.isTransientFailure())
                .as("结果未知属于「过一会儿再试有意义」，与「参数错」不同")
                .isTrue();
    }
}
