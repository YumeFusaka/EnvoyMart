package yumefusaka.envoymart.common.web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 请求标识的接线契约：进得来（MDC 里读得到）、出得去（响应头带得上）、<b>还得掉</b>。
 * <p>
 * 最后一条是重点，也是唯一不会自己暴露的一条：Tomcat 的请求线程是复用的，
 * 不清理的话下一个请求——可能是另一个用户的——会带着上一个请求的标识写日志，
 * 而每一行看起来都正常，只是把两条无关的轨迹错误地缝在了一起。
 */
class RequestIdFilterTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new Probe())
                .addFilters(new RequestIdFilter())
                .build();
    }

    @AfterEach
    void tearDown() {
        // 断言失败时也不给后面的用例留下脏值
        org.slf4j.MDC.clear();
    }

    @RestController
    static class Probe {

        @GetMapping("/ping")
        String ping() {
            return String.valueOf(RequestId.current());
        }

        @GetMapping("/boom")
        String boom() {
            throw new IllegalStateException("炸了");
        }
    }

    @Test
    void 请求头里的标识会进MDC并回写响应头() throws Exception {
        String seen = mockMvc.perform(get("/ping").header(RequestId.HEADER, "trace-me-123"))
                .andReturn().getResponse().getContentAsString();

        assertThat(seen).isEqualTo("trace-me-123");
        assertThat(mockMvc.perform(get("/ping").header(RequestId.HEADER, "trace-me-123"))
                .andReturn().getResponse().getHeader(RequestId.HEADER)).isEqualTo("trace-me-123");
    }

    @Test
    void 没带请求头时新发一个并同样回写() throws Exception {
        var response = mockMvc.perform(get("/ping")).andReturn().getResponse();

        assertThat(response.getContentAsString()).hasSize(16);
        assertThat(response.getHeader(RequestId.HEADER)).hasSize(16);
        assertThat(response.getHeader(RequestId.HEADER)).isEqualTo(response.getContentAsString());
    }

    @Test
    void 不合法的请求头被换掉_下游拿到的是新值() throws Exception {
        var response = mockMvc.perform(get("/ping").header(RequestId.HEADER, "bad\nvalue")).andReturn().getResponse();

        assertThat(response.getContentAsString()).hasSize(16);
        assertThat(response.getContentAsString()).doesNotContain("bad");
    }

    @Test
    void 请求结束后MDC被清空_下个请求不会继承上个请求的标识() throws Exception {
        mockMvc.perform(get("/ping").header(RequestId.HEADER, "first-request"));

        assertThat(RequestId.current())
                .as("同一条线程上的下一个请求不该看见上一个请求的标识")
                .isNull();
    }

    @Test
    void 链路里抛异常同样清空MDC() {
        assertThatThrownBy(() -> mockMvc.perform(get("/boom").header(RequestId.HEADER, "will-explode")))
                .isInstanceOf(Exception.class);

        assertThat(RequestId.current()).isNull();
    }
}
