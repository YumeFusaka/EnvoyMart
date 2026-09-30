package yumefusaka.envoymart.knowledgeservice.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import yumefusaka.envoymart.agent.rag.RetrievalEvalRunner;
import yumefusaka.envoymart.common.result.Result;
import yumefusaka.envoymart.common.web.RequireAdmin;
import yumefusaka.envoymart.knowledgeservice.eval.EvalSnapshot;

/**
 * 检索评测报告接口 —— 「检索质量 0.633」这句话的现场证据。
 * <p>
 * <b>为什么读报告公开、重跑要管理员。</b>报告页的存在理由是让质量声明可被独立核对，
 * 与知识库文档公开读是同一件事——数字和语料一样，本来就该给任何人看，报告里也没有
 * 一条用户数据。而重跑会替换全站共享的那份快照：它是别人正在看的页面背后的数据，
 * 属于「改共享状态」的管理动作，因此挂在 {@code /admin} 段下由 {@code @RequireAdmin} 把关。
 * <p>
 * 两类证据在这里汇合时必须分开陈述（见 {@link RetrievalEvalRunner} 的说明）：
 * 本接口的现场重跑只覆盖<b>确定性可复现的关键词路底线</b>；接入真实向量与重排的
 * 对照数字需要模型调用（非确定性、要 API key），只能作为历史记录展示，不可能现场重跑。
 */
@RestController
@RequestMapping("/knowledge")
public class EvalController {

    private final EvalSnapshot snapshot;

    public EvalController(EvalSnapshot snapshot) {
        this.snapshot = snapshot;
    }

    /** 最近一次评测快照（服务启动时生成；启动失败则本次现场补跑）。 */
    @GetMapping("/eval/report")
    public Result<RetrievalEvalRunner.EvalRun> report() {
        return Result.success(snapshot.current());
    }

    /** 现场重跑（毫秒级），并替换快照供后续访问读取。 */
    @RequireAdmin
    @PostMapping("/admin/eval/run")
    public Result<RetrievalEvalRunner.EvalRun> rerun() {
        return Result.success(snapshot.rerun(RetrievalEvalRunner.TRIGGER_MANUAL));
    }
}
