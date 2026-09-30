package yumefusaka.envoymart.knowledgeservice.eval;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import yumefusaka.envoymart.agent.rag.RetrievalEvalRunner;

/**
 * 评测快照 —— 报告页读到的那份数据，以及「重新运行」替换掉的那份数据。
 * <p>
 * <b>为什么是内存里的一份快照，而不是一张表或一份 JSON 文件。</b>
 * 评测是全程本地计算、毫秒级的（90 篇 × 120 条），持久化只会引入「库里的数字和
 * 现在能跑出来的数字哪个对」这个新问题——而这里恰恰不能有这个问题：报告的全部意义
 * 就是「你不信的话，点一下重新运行，数字会一模一样地复现」。快照的唯一职责是让报告页
 * 不必为每次访问付一次计算，而不是「保存历史」。
 * <p>
 * 启动时跑一次（与 CI 门禁同一份夹具、同一套检索器构造，见 {@link RetrievalEvalRunner}），
 * 于是报告页打开即读——演示时不必等第一次计算。启动那次失败不拦启动（评测是只读的旁观者，
 * 不该有权把知识服务拖下来），{@link #current()} 会惰性补跑兜底。
 */
@Slf4j
@Component
public class EvalSnapshot implements ApplicationRunner {

    private final RetrievalEvalRunner runner = new RetrievalEvalRunner();

    /** 只赋值不原地修改，配合 volatile 即可安全发布；重跑是全量替换语义 */
    private volatile RetrievalEvalRunner.EvalRun snapshot;

    @Override
    public void run(ApplicationArguments args) {
        try {
            RetrievalEvalRunner.EvalRun run = rerun(RetrievalEvalRunner.TRIGGER_STARTUP);
            log.info("[Knowledge] 检索评测快照就绪：cases={} hitRate@3={} mrr@3={} ndcg@3={}",
                    run.corpus().cases(), run.overallAt3().hitRate(),
                    run.overallAt3().mrr(), run.overallAt3().ndcg());
        } catch (RuntimeException e) {
            log.error("[Knowledge] 启动评测失败 —— 报告页暂无快照，"
                    + "首次访问会现场重试一次，也可由管理台手动重跑", e);
        }
    }

    /**
     * 最近一次评测结果。从未跑成过（启动那次也失败了）就<b>现场跑一次</b>：
     * 报告页宁可慢第一个请求几百毫秒，也不该在能算出答案的时候显示「没有数据」。
     */
    public RetrievalEvalRunner.EvalRun current() {
        RetrievalEvalRunner.EvalRun current = snapshot;
        if (current == null) {
            current = rerun(RetrievalEvalRunner.TRIGGER_STARTUP);
        }
        return current;
    }

    /**
     * 重跑并替换快照。{@code synchronized}：并发触发（连点按钮、启动与首次访问撞车）时
     * 后来者排队等前一次算完，而不是两个线程各跑一遍、各自覆盖。
     */
    public synchronized RetrievalEvalRunner.EvalRun rerun(String trigger) {
        RetrievalEvalRunner.EvalRun run = runner.run(trigger);
        snapshot = run;
        return run;
    }
}
