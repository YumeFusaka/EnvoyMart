package yumefusaka.envoymart.common.web;

import org.junit.jupiter.api.Test;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 异常出口的业务码约定：<b>客户端写错的请求不能报成服务端故障</b>。
 * <p>
 * 这里只钉住一件事：{@code 4xx} 的那几种「请求本身有问题」必须如实回 4xx 业务码，
 * 而不是落到兜底分支的 500。500 在监控里是「服务端出事了」的信号，
 * 用它表达「你少传了个参数」会让告警失去意义，调用方也无从判断该不该重试。
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void 缺少必填参数回400而不是500() {
        var result = handler.handleMissingParam(
                new MissingServletRequestParameterException("items", "String"));

        assertThat(result.getCode())
                .as("少传参数是客户端问题，重试无用；报 500 会被读成服务端故障")
                .isEqualTo(400);
        assertThat(result.getMsg())
                .as("要把缺的是哪个参数说出来，否则调用方只能去翻服务端日志")
                .contains("items");
    }

    /**
     * 控制器里只要有一条 {@code GET /xx/{id}} 这样的宽路径，敲错的路径也会与它
     * 「路径匹配、方法不符」，于是拿到的是 method-not-supported 而不是 404。
     * 它原先落兜底被报成 500：调用方以为服务挂了，日志里还多一条 ERROR 栈。
     */
    @Test
    void 方法不支持回405而不是500() {
        var result = handler.handleMethodNotSupported(
                new HttpRequestMethodNotSupportedException("POST", List.of("GET")));

        assertThat(result.getCode())
                .as("方法写错是客户端问题；报 500 会和真正的服务端故障混在一起")
                .isEqualTo(405);
        assertThat(result.getMsg())
                .as("要说清是哪个方法不被接受")
                .contains("POST");
    }

    /**
     * 缺少请求头是缺参数的兄弟，但 {@code MissingRequestHeaderException} 不在
     * {@code MissingServletRequestParameterException} 的继承链上 —— 只补了后者，
     * 「少一个参数」是 400、「少一个头」是 500，而成因完全一样。
     */
    @Test
    void 缺少必填请求头回400而不是500() {
        var result = handler.handleMissingHeader(new MissingRequestHeaderException("X-User-Id", null));

        assertThat(result.getCode())
                .as("身份头由网关注入，缺它通常意味着绕过了网关 —— 那是调用方的问题")
                .isEqualTo(400);
        assertThat(result.getMsg()).contains("X-User-Id");
    }

    @Test
    void 业务校验失败回400() {
        var result = handler.handleBusiness(new IllegalArgumentException("收货地址不存在"));

        assertThat(result.getCode()).isEqualTo(400);
    }

    @Test
    void 状态冲突回409而不是400() {
        // 订单已取消时不能再取消：请求本身没写错，是当前状态不允许。
        // 400 该改参数、409 该刷新后重试，这个区分对调用方是有用的
        var result = handler.handleIllegalState(new IllegalStateException("订单已取消"));

        assertThat(result.getCode()).isEqualTo(409);
    }

    @Test
    void 非预期异常回500且不泄漏原始消息() {
        var result = handler.handleUnknown(
                new RuntimeException("Connect to http://127.0.0.1:9200 failed: Connection refused"));

        assertThat(result.getCode()).isEqualTo(500);
        assertThat(result.getMsg())
                .as("内网地址与部署细节只能进日志，不能回给调用方")
                .doesNotContain("127.0.0.1")
                .doesNotContain("9200");
    }
}
