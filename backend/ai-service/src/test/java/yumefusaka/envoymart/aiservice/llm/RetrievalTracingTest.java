package yumefusaka.envoymart.aiservice.llm;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import yumefusaka.envoymart.agent.rag.DocumentChunk;
import yumefusaka.envoymart.agent.rag.QueryExpansions;
import yumefusaka.envoymart.agent.rag.Reranker;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 检索侧（重排 / 扩写）trace 装饰器的埋点行为（U13）。
 * <p>
 * 这两条链路都是"静默降级"——重排失败维持原序、扩写失败退回原句，从检索结果上
 * 与"根本没配"完全一样。span 上的 attributes 是把"这一次到底有没有生效"变得可查的唯一手段。
 */
class RetrievalTracingTest {

    static final class CapturingHandler implements ObservationHandler<Observation.Context> {
        final List<Observation.Context> completed = new ArrayList<>();

        @Override
        public void onStop(Observation.Context context) {
            completed.add(context);
        }

        @Override
        public boolean supportsContext(Observation.Context context) {
            return true;
        }
    }

    @Test
    void 重排应留下带候选数的观测() {
        CapturingHandler handler = new CapturingHandler();
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(handler);

        Reranker delegate = (query, candidates, topK) -> candidates.stream().limit(topK).toList();
        new TracingReranker(delegate, registry)
                .rerank("查询", List.of(chunk("a"), chunk("b"), chunk("c")), 2);

        Observation.Context context = handler.completed.stream()
                .filter(c -> "model.rerank".equals(c.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("没有创建 model.rerank 观测"));
        assertThat(context.getHighCardinalityKeyValue("candidates").getValue()).isEqualTo("3");
        assertThat(context.getHighCardinalityKeyValue("topK").getValue()).isEqualTo("2");
    }

    @Test
    void 扩写应留下带产出规模的观测() {
        CapturingHandler handler = new CapturingHandler();
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(handler);

        new TracingQueryExpander(
                query -> new QueryExpansions("一段假想的资料原文", List.of("说法一", "说法二")), registry)
                .expand("太贵了");

        Observation.Context context = handler.completed.stream()
                .filter(c -> "model.expand".equals(c.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("没有创建 model.expand 观测"));
        assertThat(context.getHighCardinalityKeyValue("hypotheticalChars").getValue()).isEqualTo("9");
        assertThat(context.getHighCardinalityKeyValue("angles").getValue()).isEqualTo("2");
    }

    @Test
    void 未注入registry时装饰器不改变行为() {
        Reranker delegate = (query, candidates, topK) -> candidates.stream().limit(topK).toList();
        assertThat(new TracingReranker(delegate, null)
                .rerank("q", List.of(chunk("a"), chunk("b")), 1)).hasSize(1);
        assertThat(new TracingQueryExpander(q -> QueryExpansions.none(), null)
                .expand("q").isEmpty()).isTrue();
    }

    private DocumentChunk chunk(String id) {
        return DocumentChunk.builder().chunkId(id).content(id).build();
    }
}