package yumefusaka.envoymart.agent.memory;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 感知记忆的行为锁定。
 * <p>
 * 这一块最容易在维护里被"顺手改坏"的两处：把同源观测做成追加（模型拿到一串
 * 互相矛盾的历史页面），以及把清理做成容量淘汰（过期的观测一直留着被注入）。
 */
class PerceptualMemoryTest {

    @Test
    void 同源观测覆盖而不是追加() {
        PerceptualMemory memory = new PerceptualMemory();
        memory.observe("s1", "product_page", "商品：维生素C 咀嚼片");
        memory.observe("s1", "product_page", "商品：深海鱼油软胶囊");

        assertThat(memory.current("s1"))
                .as("用户在页面上点来点去时，有意义的是最后一次的状态。"
                        + "追加会让模型拿到一串互相矛盾的页面，还得自己判断哪个是现在")
                .hasSize(1);
        assertThat(memory.current("s1").get(0).content()).contains("深海鱼油");
    }

    @Test
    void 不同来源各自保留且最近的在前() {
        PerceptualMemory memory = new PerceptualMemory();
        memory.observe("s1", "product_page", "深海鱼油");
        memory.observe("s1", "selected_items", "spu1, spu2");

        assertThat(memory.current("s1")).hasSize(2);
        assertThat(memory.current("s1").get(0).source())
                .as("最近发生的应该先被模型看到——它更可能是用户这句话的语境")
                .isEqualTo("selected_items");
    }

    @Test
    void 会话之间互不可见() {
        PerceptualMemory memory = new PerceptualMemory();
        memory.observe("s1", "product_page", "深海鱼油");

        assertThat(memory.current("s2"))
                .as("用 userId 隔离会让 A 会话的观测出现在 B 会话里，而两者可能开着不同的页面")
                .isEmpty();
    }

    @Test
    void 清理必须显式发生_容量不是失效条件() {
        PerceptualMemory memory = new PerceptualMemory();
        memory.observe("s1", "product_page", "深海鱼油");

        memory.clear("s1");

        assertThat(memory.current("s1"))
                .as("观测的失效条件是「这一轮结束了」，不是「池子满了」")
                .isEmpty();
    }

    @Test
    void 超长内容截断且报告来源() {
        PerceptualMemory memory = new PerceptualMemory();
        memory.observe("s1", "uploaded_doc", "说".repeat(900));

        assertThat(memory.current("s1").get(0).content().length())
                .as("观测是「看到了什么」，不是「看到了全部原文」")
                .isLessThanOrEqualTo(501);
    }
}
