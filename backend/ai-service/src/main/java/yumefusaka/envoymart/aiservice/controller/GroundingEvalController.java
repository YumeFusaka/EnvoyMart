package yumefusaka.envoymart.aiservice.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import yumefusaka.envoymart.aiservice.eval.GroundingLiveEvalService;
import yumefusaka.envoymart.aiservice.eval.ProductionRetrievalEvalService;
import yumefusaka.envoymart.common.result.Result;
import yumefusaka.envoymart.common.web.RequireAdmin;

/**
 * 回答质量报告接口 —— 幻觉率 / 引用准确率 / 拒答准确率 / 多跳命中率的现场证据。
 * <p>
 * 页面只返回真实 Agent 在当前知识库和知识图谱上的实测快照。
 * <p>
 * <b>读报告公开、触发真跑要管理员</b>，与检索评测报告同一条理由：数字是质量声明，
 * 谁都该能自己核对，报告里没有任何用户数据；而真跑要花 token 并占用模型配额，
 * 属于管理动作。真跑接口在 {@code /admin} 段下，先过网关的 admin 强制登录，
 * 再过 {@code @RequireAdmin} 的角色判定。
 */
@RestController
@RequestMapping("/ai")
public class GroundingEvalController {

    /** 最近一次真实链路快照；尚未生成时是 IDLE。 */
    public record Report(GroundingLiveEvalService.LiveRun live) {
    }

    private final GroundingLiveEvalService liveEval;
    private final ProductionRetrievalEvalService productionRetrievalEval;

    public GroundingEvalController(GroundingLiveEvalService liveEval,
                                   ProductionRetrievalEvalService productionRetrievalEval) {
        this.liveEval = liveEval;
        this.productionRetrievalEval = productionRetrievalEval;
    }

    /**
     * 生产链路检索评测报告：报告页只展示用户实际使用的链路快照。
     */
    @GetMapping("/eval/retrieval/report")
    public Result<ProductionRetrievalEvalService.Report> retrievalReport() {
        return Result.success(productionRetrievalEval.current());
    }

    /** 管理员手动触发一次真实生产语料检索评测；页面不会自动调用。 */
    @RequireAdmin
    @PostMapping("/admin/eval/retrieval/run")
    public Result<ProductionRetrievalEvalService.Report> runRetrieval() {
        return Result.success(productionRetrievalEval.start());
    }

    @GetMapping("/eval/grounding/report")
    public Result<Report> report() {
        return Result.success(new Report(liveEval.current()));
    }

    /**
     * 仅供管理员明确点击后生成新的真实快照，页面刷新不会触发。
     */
    @RequireAdmin
    @PostMapping("/admin/eval/grounding/run")
    public Result<GroundingLiveEvalService.LiveRun> capture() {
        return Result.success(liveEval.start());
    }

}
