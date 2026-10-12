package yumefusaka.envoymart.common.web;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class InternalTransactionHeaderTest {
    private static final String TOKEN = "internal-transaction-test-token-at-least-32-bytes";

    @Test
    void 不带身份的事务头也必须有内部调用凭证() throws Exception {
        for (String suppliedToken : new String[]{"", "forged"}) {
            var request = new MockHttpServletRequest();
            request.addHeader("TX_XID", "172.18.0.5:8091:123456");
            request.addHeader(InternalAuth.TOKEN_HEADER, suppliedToken);
            var response = new MockHttpServletResponse();
            var forwarded = new AtomicBoolean();
            new InternalCallFilter(TOKEN).doFilter(request, response, (incoming, outgoing) -> forwarded.set(true));
            assertThat(forwarded).isFalse();
            assertThat(response.getStatus()).isEqualTo(401);
        }
    }

    @Test
    void 有内部凭证的事务上下文保留给参与方() throws Exception {
        var request = new MockHttpServletRequest();
        request.addHeader("TX_XID", "172.18.0.5:8091:123456");
        request.addHeader(InternalAuth.TOKEN_HEADER, TOKEN);
        var forwarded = new AtomicBoolean();
        new InternalCallFilter(TOKEN).doFilter(request, new MockHttpServletResponse(), (incoming, outgoing) -> {
            assertThat(((jakarta.servlet.http.HttpServletRequest) incoming).getHeader("TX_XID"))
                    .isEqualTo("172.18.0.5:8091:123456");
            forwarded.set(true);
        });
        assertThat(forwarded).isTrue();
    }

    @Test
    void 普通公开请求不需要内部凭证() throws Exception {
        var forwarded = new AtomicBoolean();
        new InternalCallFilter(TOKEN).doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(),
                (incoming, outgoing) -> forwarded.set(true));
        assertThat(forwarded).isTrue();
    }
}
