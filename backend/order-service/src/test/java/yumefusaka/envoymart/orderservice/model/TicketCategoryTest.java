package yumefusaka.envoymart.orderservice.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TicketCategoryTest {

    @Test
    void 非法取值按参数错误拒绝而不是空指针() {
        assertThatThrownBy(() -> TicketCategory.parse(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知的工单分类");

        assertThatThrownBy(() -> TicketCategory.parse("LOGISTICS"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("LOGISTICS");
    }
}
