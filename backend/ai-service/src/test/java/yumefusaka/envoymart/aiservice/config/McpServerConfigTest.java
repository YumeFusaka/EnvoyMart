package yumefusaka.envoymart.aiservice.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MCP 高危工具确认信号的解析边界。
 * <p>
 * 这个解析的失败方向必须是「未确认」：把没确认当确认放行一次取消/退款，
 * 比把确认当没确认多问一遍严重得多。
 */
class McpServerConfigTest {

    @Test
    void 只有明确为真的取值算已确认() {
        assertThat(McpServerConfig.parseConfirmed(Boolean.TRUE)).isTrue();
        assertThat(McpServerConfig.parseConfirmed("true")).isTrue();
        assertThat(McpServerConfig.parseConfirmed("TRUE")).isTrue();
    }

    @Test
    void 其余一切取值都算未确认() {
        assertThat(McpServerConfig.parseConfirmed(null)).isFalse();
        assertThat(McpServerConfig.parseConfirmed(Boolean.FALSE)).isFalse();
        assertThat(McpServerConfig.parseConfirmed("false")).isFalse();
        assertThat(McpServerConfig.parseConfirmed("yes")).isFalse();
        assertThat(McpServerConfig.parseConfirmed(1)).isFalse();
    }
}
