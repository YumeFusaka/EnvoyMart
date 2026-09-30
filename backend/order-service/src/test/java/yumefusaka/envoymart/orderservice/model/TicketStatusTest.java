package yumefusaka.envoymart.orderservice.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 工单状态机的边界。
 * <p>
 * 这张表是整个工单域唯一说得清"下一步能去哪"的地方，写错一格不会有任何症状，
 * 只会让人把已经关闭的工单改回去。所以这里逐格钉死，尤其是那几条<b>不该存在</b>的边。
 */
class TicketStatusTest {

    @Test
    void 关闭是终态且不可重开() {
        assertThat(TicketStatus.CLOSED.isTerminal()).isTrue();
        for (TicketStatus target : TicketStatus.values()) {
            assertThat(TicketStatus.CLOSED.canTransitTo(target))
                    .as("已关闭的工单还能流转到 %s —— 超时自动关闭会造一批 CLOSED，"
                            + "放开就等于让用户半年后重开一条上下文早已散尽的工单", target)
                    .isFalse();
        }
    }

    @Test
    void 只有已解决的工单能回到处理中() {
        assertThat(TicketStatus.RESOLVED.canTransitTo(TicketStatus.PROCESSING)).isTrue();
        assertThat(TicketStatus.OPEN.canTransitTo(TicketStatus.PROCESSING)).isTrue();
        // 待处理是"还没人碰过"，从它回到处理中不是重开，只是接手
        assertThat(TicketStatus.PROCESSING.canTransitTo(TicketStatus.OPEN))
                .as("处理中退回待处理等于把工单扔回队列，客服刚接手就失去归属")
                .isFalse();
    }

    @Test
    void 待处理可以直接解决() {
        // "请帮我取消订单"，客服办完直接标记解决。强制先回复一条只是形式主义
        assertThat(TicketStatus.OPEN.canTransitTo(TicketStatus.RESOLVED)).isTrue();
    }

    @Test
    void 已关闭的工单不能标记解决() {
        assertThat(TicketStatus.CLOSED.canTransitTo(TicketStatus.RESOLVED)).isFalse();
    }

    @Test
    void 非法取值按参数错误拒绝而不是空指针() {
        // Enum.valueOf(null) 抛的是 NPE —— 不接住就会穿过 IllegalArgumentException
        // 的处理器落进兜底，把"客户端的参数错"报成 500「服务暂时不可用」
        assertThatThrownBy(() -> TicketStatus.parse(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知的工单状态");

        assertThatThrownBy(() -> TicketStatus.parse("PENDING"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PENDING");
    }

    @Test
    void 每个状态都有面向用户的中文名() {
        for (TicketStatus status : TicketStatus.values()) {
            assertThat(status.text()).as("状态 %s 在页面上没有可读的名字", status).isNotBlank();
        }
    }
}
