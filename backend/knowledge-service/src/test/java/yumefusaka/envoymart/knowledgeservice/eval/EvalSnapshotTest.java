package yumefusaka.envoymart.knowledgeservice.eval;

import org.junit.jupiter.api.Test;
import yumefusaka.envoymart.agent.rag.RetrievalEvalRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 快照组件的三条路径：启动跑一次、手动重跑替换、从未跑成时惰性补跑。
 * <p>
 * 报告页的「重新运行」按钮与打开即读都落在它身上，行为错的表现是
 * 「点了没反应」或「显示的还是上一次的数字」——两条都不会报错，只会让人不信报告。
 */
class EvalSnapshotTest {

    @Test
    void 启动钩子产出完整快照且来源标记为启动() {
        EvalSnapshot snapshot = new EvalSnapshot();

        snapshot.run(null);

        RetrievalEvalRunner.EvalRun run = snapshot.current();
        assertThat(run.trigger()).isEqualTo(RetrievalEvalRunner.TRIGGER_STARTUP);
        assertThat(run.cases()).hasSize(120);
        assertThat(run.overallAt3().caseCount()).isEqualTo(120);
    }

    @Test
    void 手动重跑替换快照并标记来源为手动() {
        EvalSnapshot snapshot = new EvalSnapshot();
        snapshot.rerun(RetrievalEvalRunner.TRIGGER_STARTUP);

        RetrievalEvalRunner.EvalRun rerun = snapshot.rerun(RetrievalEvalRunner.TRIGGER_MANUAL);

        assertThat(rerun.trigger()).isEqualTo(RetrievalEvalRunner.TRIGGER_MANUAL);
        assertThat(snapshot.current().trigger())
                .as("重跑之后报告页读到的必须是新快照，而不是启动时那份")
                .isEqualTo(RetrievalEvalRunner.TRIGGER_MANUAL);
    }

    @Test
    void 从未跑过时读取会惰性补跑而不是返回空() {
        // 没有调用 run()，模拟启动那次失败后的状态
        EvalSnapshot snapshot = new EvalSnapshot();

        RetrievalEvalRunner.EvalRun run = snapshot.current();

        assertThat(run).isNotNull();
        assertThat(run.cases()).hasSize(120);
        assertThat(snapshot.current()).isSameAs(run);
    }
}
