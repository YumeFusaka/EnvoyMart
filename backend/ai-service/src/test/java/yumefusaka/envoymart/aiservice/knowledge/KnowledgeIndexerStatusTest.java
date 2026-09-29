package yumefusaka.envoymart.aiservice.knowledge;

import org.junit.jupiter.api.Test;
import yumefusaka.envoymart.agent.rag.Document;
import yumefusaka.envoymart.agent.rag.HybridRetriever;
import yumefusaka.envoymart.agent.rag.TextSplitter;
import yumefusaka.envoymart.agent.rag.VectorStore;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 后台重建的状态机 —— 钉住两件在管理台上必须成立的事。
 * <p>
 * 起因是一次实测：{@code POST /ai/internal/knowledge/reindex} 同步跑完整次重建（实测七十几秒），
 * HTTP 客户端先超时，而重建其实<b>成功完成了</b>。调用方看到的是失败、系统状态却是新的。
 * 修法是把「已开始」与「跑完了没有」拆成两个接口，于是状态机本身成了要守住的东西：
 * <ul>
 *   <li>失败了必须留痕。只把 {@code running} 翻回 false 的话，「跑完了什么都没发生」
 *       与「跑起来就崩了」在调用方看来一模一样；</li>
 *   <li>正在跑的时候再发起不能排队。让第二个请求挂在锁上几十秒，
 *       比直接告诉它「现在就在跑」糟糕得多。</li>
 * </ul>
 */
class KnowledgeIndexerStatusTest {

    private static final Duration WAIT_LIMIT = Duration.ofSeconds(10);

    /** 语料拉取直接抛异常，用来把「重建失败」这条路走出来 */
    @Test
    void 重建失败时状态里留下原因而不是静静回到未运行() {
        KnowledgeCorpus corpus = mock(KnowledgeCorpus.class);
        when(corpus.reload()).thenThrow(new IllegalStateException("knowledge-service 不可达"));
        KnowledgeIndexer indexer = newIndexer(corpus);

        indexer.rebuildAsync();

        KnowledgeIndexer.Status after = 等待结束(indexer);
        assertThat(after.running()).as("失败之后 running 必须归位，否则重建接口从此一直说在跑").isFalse();
        assertThat(after.error()).as("失败原因必须留在状态里").contains("knowledge-service 不可达");
        assertThat(after.finishedAt()).as("失败也要有结束时间，否则没法判断这是刚才的事还是昨天的事").isNotNull();
        assertThat(after.result()).as("从没成功过时结果为空").isNull();
    }

    /**
     * 正在重建时再发起一次：立刻返回、不排队，且底层只被拉了一次语料。
     * <p>
     * 「只拉一次」是关键——不挡的话第二个线程会排在 {@code synchronized} 上，
     * 等第一个跑完再从头重建一遍，白白多花七十秒，还会把刚建好的索引再清空一次。
     */
    @Test
    void 重建进行中再发起应立即返回且不重复执行() throws Exception {
        CountDownLatch 卡住 = new CountDownLatch(1);
        CountDownLatch 已进入 = new CountDownLatch(1);
        AtomicInteger 拉取次数 = new AtomicInteger();
        KnowledgeCorpus corpus = mock(KnowledgeCorpus.class);
        when(corpus.reload()).thenAnswer(invocation -> {
            拉取次数.incrementAndGet();
            已进入.countDown();
            卡住.await(10, TimeUnit.SECONDS);
            return List.<Document>of();
        });
        KnowledgeIndexer indexer = newIndexer(corpus);

        KnowledgeIndexer.Status first = indexer.rebuildAsync();
        assertThat(已进入.await(10, TimeUnit.SECONDS)).as("第一次重建应当真的跑起来").isTrue();
        assertThat(first.running()).as("发起之后立刻就该是「在跑」，不能等线程调度").isTrue();

        long startedAt = System.nanoTime();
        KnowledgeIndexer.Status second = indexer.rebuildAsync();
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

        assertThat(second.running()).as("第二次发起时应当被告知正在跑").isTrue();
        assertThat(elapsedMs).as("第二次发起不能等待第一次跑完（实测用了 %d ms）", elapsedMs).isLessThan(1_000);

        卡住.countDown();
        等待结束(indexer);
        assertThat(拉取次数.get()).as("重复发起不能让底层多跑一遍").isEqualTo(1);
    }

    /** 空语料是合法结果，不是失败：库里就是没有文档时，重建成功且结果里是 0 */
    @Test
    void 空语料重建成功且结果里是零篇() {
        KnowledgeCorpus corpus = mock(KnowledgeCorpus.class);
        when(corpus.reload()).thenReturn(List.of());
        KnowledgeIndexer indexer = newIndexer(corpus);

        indexer.rebuildAsync();

        KnowledgeIndexer.Status after = 等待结束(indexer);
        assertThat(after.error()).as("空语料不该被当成失败").isNull();
        assertThat(after.result()).isNotNull();
        assertThat(after.result().documentCount()).isZero();
        assertThat(after.result().chunkCount()).isZero();
    }

    private static KnowledgeIndexer newIndexer(KnowledgeCorpus corpus) {
        return new KnowledgeIndexer(corpus,
                mock(VectorStore.class),
                mock(TextSplitter.class),
                mock(HybridRetriever.class),
                mock(KnowledgeGraphBuilder.class),
                false);
    }

    /**
     * 等后台线程跑完。<b>轮询而不是 join</b>：{@code rebuildAsync} 故意不交出线程句柄——
     * 交出句柄就会诱使调用方去 join，那就退回成同步等待了。
     */
    private static KnowledgeIndexer.Status 等待结束(KnowledgeIndexer indexer) {
        Instant deadline = Instant.now().plus(WAIT_LIMIT);
        while (Instant.now().isBefore(deadline)) {
            KnowledgeIndexer.Status status = indexer.status();
            if (!status.running()) {
                return status;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待后台重建时被中断", e);
            }
        }
        throw new AssertionError("等了 " + WAIT_LIMIT + " 后台重建仍未结束，状态：" + indexer.status());
    }
}
