package yumefusaka.envoymart.aiservice.rag;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.Test;
import yumefusaka.envoymart.agent.rag.DocumentChunk;
import yumefusaka.envoymart.agent.rag.EmbeddingService;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 向量库的元数据往返 —— 切片上能带多少信息，取决于存进去时写了几个字段。
 * <p>
 * 这一跳的失效是静默的：写的时候少一个字段，读回来就是 null，没有任何一处会报错。
 * 上层拿到一条「类型未知、时间未知」的记忆，只能当默认值用——而默认值不会说
 * 「我不知道」，它说的是「这是一条此刻发生的普通事件」。
 * <p>
 * 这里用最小桩顶替真实 Milvus。它<b>测不到 Milvus 自己的序列化</b>（元数据在桩里以对象形态
 * 保留，从未离开 JVM），能守住的是另一半：{@code metadataOf} 写了哪些键、
 * {@code toChunk} 读了哪些键——一边改了名字另一边没跟上，在这里就会现形。
 * 真实往返由部署后的端到端验收负责（重启服务，看记忆条目的类型与时间还在不在）。
 */
class MilvusVectorStoreTest {

    /** 固定向量。这里验的是元数据往返，不是检索质量 */
    private static final EmbeddingService FIXED_VECTOR = new EmbeddingService() {
        @Override
        public float[] embed(String text) {
            return new float[]{1f, 0f, 0f};
        }

        @Override
        public List<float[]> embedBatch(List<String> texts) {
            return texts.stream().map(this::embed).toList();
        }

        @Override
        public int dimension() {
            return 3;
        }
    };

    /**
     * 只收文本段的极简向量库。存进去什么样、取出来什么样，不做任何加工——
     * 这样一条断言失败时就只有一个解释：写和读用的键对不上。
     */
    private static final class ListBackedStore implements EmbeddingStore<TextSegment> {
        private final List<TextSegment> segments = new ArrayList<>();

        @Override
        public String add(Embedding embedding) {
            throw new UnsupportedOperationException("桩只支持带文本段的写入");
        }

        @Override
        public void add(String id, Embedding embedding) {
            throw new UnsupportedOperationException("桩只支持带文本段的写入");
        }

        @Override
        public String add(Embedding embedding, TextSegment embedded) {
            segments.add(embedded);
            return UUID.randomUUID().toString();
        }

        @Override
        public List<String> addAll(List<Embedding> embeddings) {
            throw new UnsupportedOperationException("桩只支持带文本段的写入");
        }

        @Override
        public List<String> addAll(List<Embedding> embeddings, List<TextSegment> embedded) {
            List<String> ids = new ArrayList<>(embedded.size());
            for (TextSegment segment : embedded) {
                segments.add(segment);
                ids.add(UUID.randomUUID().toString());
            }
            return ids;
        }

        @Override
        public EmbeddingSearchResult<TextSegment> search(EmbeddingSearchRequest request) {
            List<EmbeddingMatch<TextSegment>> matches = segments.stream()
                    .limit(request.maxResults())
                    .map(segment -> new EmbeddingMatch<>(1.0, UUID.randomUUID().toString(),
                            new Embedding(new float[]{1f, 0f, 0f}), segment))
                    .toList();
            return new EmbeddingSearchResult<>(matches);
        }
    }

    private MilvusVectorStore store() {
        return new MilvusVectorStore(new ListBackedStore(), FIXED_VECTOR);
    }

    @Test
    void 记忆条目的类型与时间戳能原样读回来() {
        MilvusVectorStore store = store();
        long writtenAt = 1767323045000L;
        store.indexBatch(List.of(DocumentChunk.builder()
                .chunkId("m1").docId("u1001").content("用户是学生党")
                .type("PREFERENCE").timestamp(writtenAt)
                .build()));

        DocumentChunk back = store.search("预算", 1).get(0);

        assertThat(back.getType()).isEqualTo("PREFERENCE");
        assertThat(back.getTimestamp())
                .as("读回来的时间若变成 null，上层会兜底成「此刻」——所有历史条目就此失去真实时间")
                .isEqualTo(writtenAt);
    }


    /**
     * 带 docId 过滤的检索必须把过滤条件下推给底层，而不是取回来自己筛。
     * <p>
     * 这是情节记忆按 userId 隔离的关键一跳。桩在这里扮演「支持过滤」的 Milvus：
     * 它检查收到的请求里真的带了 filter，并且 filter 真的按 docId 生效——
     * 若实现只把参数吞掉、照旧全量返回，这条会红。
     */
    @Test
    void 带docId的检索把过滤下推到底层() {
        FilterRecordingStore delegate = new FilterRecordingStore();
        MilvusVectorStore store = new MilvusVectorStore(delegate, FIXED_VECTOR);

        store.indexBatch(List.of(
                DocumentChunk.builder().chunkId("a").docId("u1001").content("我的地址").build(),
                DocumentChunk.builder().chunkId("b").docId("u1002").content("别人的地址").build()));

        List<DocumentChunk> mine = store.search("地址", 5, "u1001");

        assertThat(delegate.lastRequestFiltered)
                .as("过滤没有下推——请求里不带 filter，等于取回来再筛，过取窗口一满就漏召回")
                .isTrue();
        assertThat(mine)
                .isNotEmpty()
                .allSatisfy(c -> assertThat(c.getDocId()).isEqualTo("u1001"));
        assertThat(mine).noneSatisfy(c -> assertThat(c.getDocId()).isEqualTo("u1002"));
    }

    /** 支持查询过滤的最小向量库：只有请求带 filter 时才按 docId 收窄 */
    private static final class FilterRecordingStore implements EmbeddingStore<TextSegment> {
        private final List<TextSegment> segments = new ArrayList<>();
        boolean lastRequestFiltered = false;

        @Override
        public String add(Embedding embedding) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void add(String id, Embedding embedding) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String add(Embedding embedding, TextSegment embedded) {
            segments.add(embedded);
            return UUID.randomUUID().toString();
        }

        @Override
        public List<String> addAll(List<Embedding> embeddings) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<String> addAll(List<Embedding> embeddings, List<TextSegment> embedded) {
            List<String> ids = new ArrayList<>(embedded.size());
            for (TextSegment segment : embedded) {
                segments.add(segment);
                ids.add(UUID.randomUUID().toString());
            }
            return ids;
        }

        @Override
        public EmbeddingSearchResult<TextSegment> search(EmbeddingSearchRequest request) {
            lastRequestFiltered = request.filter() != null;
            List<TextSegment> pool = segments;
            if (request.filter() != null) {
                // 按 docId 等值过滤模拟 Milvus 行为，证明「传入的过滤器真的能生效」
                pool = segments.stream()
                        .filter(seg -> request.filter().test(seg.metadata()))
                        .toList();
            }
            List<EmbeddingMatch<TextSegment>> matches = pool.stream()
                    .limit(request.maxResults())
                    .map(seg -> new EmbeddingMatch<>(1.0, UUID.randomUUID().toString(),
                            new Embedding(new float[]{1f, 0f, 0f}), seg))
                    .toList();
            return new EmbeddingSearchResult<>(matches);
        }
    }
    /**
     * 历史数据里没有这两个键。

     * <p>
     * 升级时向量库里已经躺着几万条没有 type/timestamp 的切片，读它们不能报错——
     * 一次检索里只要有一条抛异常，整轮对话就退化成兜底话术。缺键按「不知道」处理，
     * 而不是按「出错」处理。
     */
    @Test
    void 老数据缺少这两个键时读回来是空的而不是报错() {
        MilvusVectorStore store = store();
        store.indexBatch(List.of(DocumentChunk.builder()
                .chunkId("old1").docId("KB-0001").content("七天无理由退货")
                .build()));

        DocumentChunk back = store.search("退货", 1).get(0);

        assertThat(back.getType()).isNull();
        assertThat(back.getTimestamp()).isNull();
    }
}
