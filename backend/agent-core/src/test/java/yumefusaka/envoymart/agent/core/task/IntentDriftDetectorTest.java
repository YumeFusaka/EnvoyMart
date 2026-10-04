package yumefusaka.envoymart.agent.core.task;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 跑偏检测的判据测试。
 * <p>
 * 这一层测的重点不是「能不能判对」，而是<b>它的误报边界在哪</b>——
 * 一个观测信号如果对正常的多步任务也频繁报警，很快就会没人看它。
 */
class IntentDriftDetectorTest {

    @Test
    void 无意图或无工具时不报警() {
        assertFalse(IntentDriftDetector.check(null, List.of("order_query")).drifted());
        assertFalse(IntentDriftDetector.check("查订单物流", List.of()).drifted());
        assertFalse(IntentDriftDetector.check("  ", List.of("order_query")).drifted());
    }

    @Test
    void 全部工具都落在意图内时不报警() {
        var verdict = IntentDriftDetector.check("查一下这个订单的物流到哪了",
                List.of("order_query", "logistics_query"));
        assertFalse(verdict.drifted());
        assertEquals(2, verdict.intentHits().size());
    }

    @Test
    void 一个相关工具都没有时报跑偏() {
        var verdict = IntentDriftDetector.check("查一下这个订单的物流到哪了",
                List.of("product_search", "knowledge_search"));
        assertTrue(verdict.drifted(), "全部工具与意图无关，是最硬的跑偏形态");
        assertTrue(verdict.detail().contains("无一处相关"));
    }

    @Test
    void 少量无关工具属于正常多步任务_不报警() {
        // 「查订单顺手看下物流」是正常形态，不该被标红——
        // 判据用比例而不是「出现即报」，理由见检测器注释
        var verdict = IntentDriftDetector.check("查一下订单什么时候到",
                List.of("order_query", "logistics_query", "product_search"));
        assertFalse(verdict.drifted());
    }

    @Test
    void 无关工具占大头时报警() {
        var verdict = IntentDriftDetector.check("取消这个订单",
                List.of("order_cancel", "product_search", "knowledge_search",
                        "interaction_check", "logistics_query"));
        assertTrue(verdict.drifted(), "相关 1 个、无关 4 个，超过比例的跑偏");
    }

    @Test
    void 两个相关两个无关仍在正常范围() {
        // 边界：比例判据是「无关 >= 3 且 > 相关*2」，2:2 不该触发
        var verdict = IntentDriftDetector.check("帮我看看这个订单和物流",
                List.of("order_query", "logistics_query", "product_search", "knowledge_search"));
        assertFalse(verdict.drifted());
    }

    @Test
    void 未知工具名不计入任何一侧() {
        // 新加的工具没登记关键词时，不能让它把结论带偏——
        // 既不因为「没命中」被算成无关，也不因为「不认识」被算成相关
        var verdict = IntentDriftDetector.check("查订单",
                List.of("order_query", "some_future_tool"));
        assertFalse(verdict.drifted());
        assertEquals(1, verdict.intentHits().size());
    }
}