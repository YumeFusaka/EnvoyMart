package yumefusaka.envoymart.agent.llm;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 账本要守的两件事：<b>同一条链路上的每一笔都得记上</b>，以及
 * <b>这一轮的账不能流到下一轮</b>。
 * <p>
 * 后者尤其要命：账本活在线程池的线程上，SSE 那条路是 {@code streamExecutor.submit} 起来的。
 * 漏一次解绑，下一个请求就会带着上一个请求的余额开盘——数字偏大、不报错，
 * 而且越是"上一个请求花得多"，这一个错得越离谱。
 */
class TokenLedgerTest {

    @Test
    void 同一轮的多笔调用累加在一起() {
        try (TokenLedger.Scope scope = TokenLedger.begin()) {
            TokenLedger.record("qwen-plus", 100, 20);
            TokenLedger.record("qwen-plus", 300, 50);

            TokenLedger.Snapshot snapshot = scope.snapshot();
            assertThat(snapshot.promptTokens()).isEqualTo(400);
            assertThat(snapshot.completionTokens()).isEqualTo(70);
            assertThat(snapshot.totalTokens()).isEqualTo(470);
            assertThat(snapshot.models()).singleElement()
                    .satisfies(usage -> assertThat(usage.model()).isEqualTo("qwen-plus"));
        }
    }

    @Test
    void 不同模型分开记账() {
        try (TokenLedger.Scope scope = TokenLedger.begin()) {
            TokenLedger.record("qwen-plus", 100, 20);
            TokenLedger.record("gte-rerank-v2", 2000, 0);

            TokenLedger.Snapshot snapshot = scope.snapshot();
            assertThat(snapshot.totalTokens()).isEqualTo(2120);
            assertThat(snapshot.models()).extracting(TokenLedger.ModelUsage::model)
                    .as("拆开记是为了算钱：重排与对话的单价差一个数量级，合成一笔就再也拆不回来")
                    .containsExactly("gte-rerank-v2", "qwen-plus");
        }
    }

    @Test
    void 关闭之后账本不再接受记账() {
        try (TokenLedger.Scope scope = TokenLedger.begin()) {
            TokenLedger.record("qwen-plus", 100, 20);
            assertThat(scope.snapshot().totalTokens()).isEqualTo(120);
        }

        // 模拟线程被线程池回收后接到下一个请求：此时不该还认得上一轮的账
        assertThat(TokenLedger.current()).isEmpty();
        TokenLedger.record("qwen-plus", 999, 999);

        try (TokenLedger.Scope next = TokenLedger.begin()) {
            assertThat(next.snapshot().totalTokens())
                    .as("上一轮的余额流到了下一轮 —— 用户看到的本轮成本会凭空多出一截")
                    .isZero();
        }
    }

    @Test
    void 嵌套时内层关闭还原外层() {
        try (TokenLedger.Scope outer = TokenLedger.begin()) {
            TokenLedger.record("qwen-plus", 100, 0);

            try (TokenLedger.Scope inner = TokenLedger.begin()) {
                TokenLedger.record("qwen-plus", 7, 0);
                assertThat(inner.snapshot().totalTokens()).isEqualTo(7);
            }

            // 内层结完账，外层还得接着记：图谱构建这类内部调用会自己开一次账
            TokenLedger.record("qwen-plus", 50, 0);
            assertThat(outer.snapshot().totalTokens())
                    .as("内层把外层顶掉了，外层的账在这里断成两截")
                    .isEqualTo(150);
        }
    }

    @Test
    void 没开账时记账是空操作() {
        TokenLedger.record("qwen-plus", 100, 20);

        assertThat(TokenLedger.current()).isEmpty();
    }

    @Test
    void 用量为零的调用不建账目() {
        try (TokenLedger.Scope scope = TokenLedger.begin()) {
            TokenLedger.record("qwen-plus", 0, 0);

            assertThat(scope.snapshot().isEmpty())
                    .as("零用量要能被识别成「这一轮压根没花钱」，否则界面会显示一个 0 tokens 的用量块")
                    .isTrue();
            assertThat(scope.snapshot().models()).isEmpty();
        }
    }

    /**
     * 账本是按线程绑的，所以「开了账的那个线程」才是记账线程。
     * 这条钉的是它的失效形状：别的线程记的账不会汇进来——这正是端到端验收里
     * 「账本总额 == 该轮日志各次调用之和」那条断言存在的理由，因为一旦有人把某次模型调用
     * 挪到别的线程上，这里不会有任何异常，只有数字偏小。
     */
    @Test
    void 其它线程记的账不会汇进来() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (TokenLedger.Scope scope = TokenLedger.begin()) {
            TokenLedger.record("qwen-plus", 100, 0);

            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<Boolean> sawLedger = new AtomicReference<>();
            pool.submit(() -> {
                sawLedger.set(TokenLedger.current().isPresent());
                TokenLedger.record("qwen-plus", 500, 0);
                done.countDown();
            });
            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();

            assertThat(sawLedger.get()).as("另一个线程不该看见这个账本").isFalse();
            assertThat(scope.snapshot().totalTokens()).isEqualTo(100);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 上面那条管的是「没包装就不汇」，这条管的是「<b>包装了就得汇</b>」——
     * agent 的并行计划步骤（agentExecutor）走的就是这条路：检索与查询扩写都在
     * 那条虚拟线程上发生，不显式带账本过去，它们就静默不计入（实测漏过约 1900 tokens/问）。
     */
    @Test
    void 包装过的任务把账本带进另一个线程() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (TokenLedger.Scope scope = TokenLedger.begin()) {
            TokenLedger.record("qwen-plus", 100, 0);

            CountDownLatch done = new CountDownLatch(1);
            pool.submit(TokenLedger.inheriting(() -> {
                TokenLedger.record("gte-rerank-v2", 500, 0);
                done.countDown();
            }));
            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();

            assertThat(scope.snapshot().totalTokens())
                    .as("包装过的任务记的账没有汇回来 —— 并行步骤里的模型调用会静默漏账")
                    .isEqualTo(600);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 目标线程可能正拿着自己那一轮的账（池化线程在任务之间复用）。
     * 外来任务把账本带进来执行完，必须<b>还原</b>而不是清掉——
     * 顶掉与留下同样糟：这个线程上原本那一轮的账会断在这里。
     */
    @Test
    void 包装执行完还原目标线程原有账本() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            // 目标线程先给自己开一个账并一直持着，模拟「这个线程正在处理别的对话」
            TokenLedger.Scope own = pool.submit(TokenLedger::begin).get();

            try (TokenLedger.Scope outer = TokenLedger.begin()) {
                TokenLedger.record("qwen-plus", 100, 0);
                pool.submit(TokenLedger.inheriting(() -> TokenLedger.record("qwen-plus", 50, 0))).get();
                assertThat(outer.snapshot().totalTokens()).isEqualTo(150);
            }

            assertThat(pool.submit(own::snapshot).get().totalTokens())
                    .as("外来任务把目标线程自己的账本顶掉了 —— 这个线程上原本那一轮的账断在这里")
                    .isZero();
        } finally {
            pool.shutdownNow();
        }
    }
}
