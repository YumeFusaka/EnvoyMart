package yumefusaka.envoymart.aiservice.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import yumefusaka.envoymart.agent.rag.GroundingEvalRunner;
import yumefusaka.envoymart.aiservice.eval.GroundingLiveEvalService;
import yumefusaka.envoymart.aiservice.eval.ProductionRetrievalEvalService;
import yumefusaka.envoymart.common.result.Result;
import yumefusaka.envoymart.common.web.RequireAdmin;

/**
 * 回答质量报告接口 —— 幻觉率 / 引用准确率 / 拒答准确率 / 多跳命中率的现场证据。
 * <p>
 * 一次响应里并排两条链路，<b>来源必须分别标出来</b>（响应体里就是两个字段）：
 * <ul>
 *   <li>{@code offline}：把夹具里的冻结输出喂给判定链重放。纯函数、毫秒级、零成本，
 *       所以每次读都现算，没有快照也没有缓存——它测的是「判定逻辑有没有退化」；
 *       数字跟着夹具的采集时间走，那个时间戳就在响应里。</li>
 *   <li>{@code live}：管理员触发的一次真实重跑。测的是「现在这套模型 + 知识库 + 图谱
 *       表现如何」，跑一次两分钟、花真钱，所以只在有人点按钮时跑，读的永远是上一次的结果。</li>
 * </ul>
 * 两组数字同尺同算法（同一个 {@code GroundingEvaluator}），可以直接并排；但把 offline
 * 的数字说成"当前质量"就是拿旧快照冒充新结论——报告页上两者各有标题与时间。
 * <p>
 * <b>读报告公开、触发真跑要管理员</b>，与检索评测报告同一条理由：数字是质量声明，
 * 谁都该能自己核对，报告里没有任何用户数据；而真跑要花 token 并占用模型配额，
 * 属于管理动作。真跑接口在 {@code /admin} 段下，先过网关的 admin 强制登录，
 * 再过 {@code @RequireAdmin} 的角色判定。
 */
@RestController
@RequestMapping("/ai")
public class GroundingEvalController {

    /**
     * @param offline 离线重放（判定链回归基线）
     * @param live    最近一次真跑；没人跑过时是 IDLE，{@code metrics} 为 null
     */
    public record Report(GroundingEvalRunner.EvalRun offline, GroundingLiveEvalService.LiveRun live) {
    }

    private final GroundingLiveEvalService liveEval;
    private final ProductionRetrievalEvalService productionRetrievalEval;

    public GroundingEvalController(GroundingLiveEvalService liveEval,
                                   ProductionRetrievalEvalService productionRetrievalEval) {
        this.liveEval = liveEval;
        this.productionRetrievalEval = productionRetrievalEval;
    }

    /**
     * 生产链路检索评测报告 —— 与关键词路基线口径不同（见 {@link ProductionRetrievalEvalService}）。
     * 报告页把两栏分开陈述：这一栏是「用户此刻在用的链路有多好」。
     */
    @GetMapping("/eval/retrieval/report")
    public Result<ProductionRetrievalEvalService.Report> retrievalReport() {
        return Result.success(productionRetrievalEval.current());
    }

    /**
     * 触发一次生产链路检索真跑（异步）。要调真实 embedding / 重排，几分钟、要计费，
     * 所以走管理员，且只返回「已开始」——前端轮询 {@link #retrievalReport()} 看进度。
     */
    @RequireAdmin
    @PostMapping("/admin/eval/retrieval/run")
    public Result<ProductionRetrievalEvalService.Report> retrievalRun() {
        return Result.success(productionRetrievalEval.start());
    }

    @GetMapping("/eval/grounding/report")
    public Result<Report> report() {
        return Result.success(new Report(
                new GroundingEvalRunner().run(GroundingEvalRunner.TRIGGER_MANUAL), liveEval.current()));
    }

    /**
     * 触发一次真实重跑（异步）。
     * <p>
     * 返回的是「已开始，当前状态是这样」，不是跑完的结果——24 条用例串行调模型要两分钟，
     * 同步等会让 HTTP 客户端先超时（与索引重建同样的取舍）。前端轮询 {@link #report()}
     * 看 {@code live.status} 与进度。
     * <p>
     * 已在跑时重复触发返回的是当前任务的状态，不是错误：连点两次按钮的人想要的是
     * 「看着它跑完」，不是「排队第二个任务」。
     */
    @RequireAdmin
    @PostMapping("/admin/eval/grounding/run")
    public Result<GroundingLiveEvalService.LiveRun> run() {
        return Result.success(liveEval.start());
    }
}
