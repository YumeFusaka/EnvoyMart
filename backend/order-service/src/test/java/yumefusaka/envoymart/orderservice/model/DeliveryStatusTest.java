package yumefusaka.envoymart.orderservice.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 物流状态的取值边界。
 * <p>
 * 这张表此前只活在一句 Javadoc 注释里，由发货与签收两处各写一个字符串字面量。
 * 这里钉的是两件事：<b>非法取值必须变成 400 而不是 500</b>，
 * 以及 <b>每个状态都得有一句不填说明时能用的默认文案</b>——
 * 缺了它，补录接口在客服不写说明时会落一条空轨迹，用户看到的是一个时间点加一片空白。
 */
class DeliveryStatusTest {

    @Test
    void 非法取值按参数错误拒绝而不是空指针() {
        // Enum.valueOf(null) 抛的是 NPE —— 不接住就会穿过 IllegalArgumentException
        // 的处理器落进兜底，把"客户端的参数错"报成 500「服务暂时不可用」
        assertThatThrownBy(() -> DeliveryStatus.parse(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知的物流状态");

        assertThatThrownBy(() -> DeliveryStatus.parse("SHIPPING"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SHIPPING");
    }

    @Test
    void 报错信息里带上全部合法取值() {
        // 这个接口只有客服在用，看到 400 时他手上应该直接有正确答案，而不是去翻代码
        assertThatThrownBy(() -> DeliveryStatus.parse("picked_up"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PICKED_UP")
                .hasMessageContaining("IN_TRANSIT")
                .hasMessageContaining("DELIVERING");
    }

    @Test
    void 大小写必须一致() {
        // 容错（忽略大小写、去空格）会让前端字典与后端契约的分叉一直藏着，
        // 直到某天多出一个谁也没写过的节点
        assertThatThrownBy(() -> DeliveryStatus.parse("Picked_Up"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(DeliveryStatus.parse("PICKED_UP")).isEqualTo(DeliveryStatus.PICKED_UP);
    }

    @Test
    void 每个状态都有不填说明时能用的默认文案() {
        for (DeliveryStatus status : DeliveryStatus.values()) {
            assertThat(status.defaultDescription())
                    .as("状态 %s 没有默认说明，客服不填就会落一条只有时间点的空轨迹", status)
                    .isNotBlank();
        }
    }

    @Test
    void 发货与签收两句文案归枚举管() {
        // 这两句话原先硬编码在 OrderDomainServiceImpl 的两个方法里。
        // 钉住它们是为了让"文案跟着状态走"这件事有个明确的去处——
        // 之前加一个状态要在全仓库搜字符串，漏一处不会有任何编译提示
        assertThat(DeliveryStatus.PICKED_UP.defaultDescription()).isEqualTo("包裹已由承运商揽收");
        assertThat(DeliveryStatus.SIGNED.defaultDescription()).isEqualTo("包裹已签收");
    }
}
