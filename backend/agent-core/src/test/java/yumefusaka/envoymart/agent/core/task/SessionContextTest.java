package yumefusaka.envoymart.agent.core.task;

import org.junit.jupiter.api.Test;
import yumefusaka.envoymart.agent.core.TaskStage;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 会话现场的契约。
 * <p>
 * 这里钉的是「切换时清什么、保留什么」——它是整个上下文隔离里唯一会导致
 * <b>跨话题串味</b>的地方，也是最容易被写成「切换 = 全部重置」的地方。
 */
class SessionContextTest {

    @Test
    void 切换会清掉子任务与待办但保留已完成步骤() {
        var ctx = SessionContext.empty("u1", "s1", 0L)
                .switchTo("帮我推荐蛋白粉", 1L)
                .advance("对比前三个商品", Map.of("k", "v"), 2L)
                .withPending(List.of("order_cancel"), 2L)
                .complete("product_search", 2L);

        var switched = ctx.switchTo("帮我查下订单", 3L);

        assertThat(switched.coreIntent()).isEqualTo("帮我查下订单");
        assertThat(switched.currentSubtask()).isNull();
        assertThat(switched.pendingTools())
                .as("上一件事等着确认的调用，绝不能被新话题继承——它会被当成「还没做完的事」重新提起")
                .isEmpty();
        assertThat(switched.completedSteps())
                .as("已完成步骤记的是「这个会话真做过什么」，是「别重复查」的依据，不该重置")
                .containsExactly("product_search");
        assertThat(switched.contextSnapshot()).isEmpty();
        assertThat(switched.stage()).isEqualTo(TaskStage.PLANNING);
    }

    @Test
    void 接续时核心意图保持不变() {
        var ctx = SessionContext.empty("u1", "s1", 0L).switchTo("帮我推荐蛋白粉", 1L);

        var advanced = ctx.advance("看第二个", Map.of("pick", 2), 2L);

        assertThat(advanced.coreIntent())
                .as("核心意图整个会话里必须指同一件事，否则「上文在办什么」没有稳定答案")
                .isEqualTo("帮我推荐蛋白粉");
        assertThat(advanced.currentSubtask()).isEqualTo("看第二个");
    }

    @Test
    void 已完成步骤去重且保序() {
        var ctx = SessionContext.empty("u1", "s1", 0L)
                .complete("a", 1L).complete("b", 1L).complete("a", 1L);

        assertThat(ctx.completedSteps()).containsExactly("a", "b");
    }

    @Test
    void 上下文快照容忍空值() {
        var ctx = SessionContext.empty("u1", "s1", 0L)
                .advance("x", new java.util.LinkedHashMap<>() {{
                    put("k", null);
                }}, 1L);

        assertThat(ctx.contextSnapshot()).containsKey("k");
    }
}
