package yumefusaka.envoymart.knowledgeservice.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import yumefusaka.envoymart.common.result.Result;
import yumefusaka.envoymart.knowledgeservice.graph.GraphEdge;
import yumefusaka.envoymart.knowledgeservice.graph.GraphNode;
import yumefusaka.envoymart.knowledgeservice.graph.GraphService;
import yumefusaka.envoymart.knowledgeservice.graph.InteractionReport;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 知识图谱只读接口。与 {@link KnowledgeController} 同属公开前缀：
 * 图谱的每一条边都带着原文引文，公开它和公开知识库文档是一回事。
 * <p>
 * <b>图谱不可用时返回 503，不返回空列表。</b>这是本控制器唯一一处刻意的「不优雅」。
 * 空列表在界面上会渲染成「没有查到相关内容」，而在这个场景里它会被读成
 * 「这几样东西没有冲突」——一个<b>完全相反</b>的结论。宁可让前端弹一个明确的
 * 「图谱暂时查不了」，也不能让它把故障说成安全。
 */
@RestController
@RequestMapping("/knowledge/graph")
public class GraphController {

    private final GraphService graphService;

    public GraphController(GraphService graphService) {
        this.graphService = graphService;
    }

    /**
     * 实体邻域，供前端画图。
     * <p>
     * 返回的是<b>边表</b>而不是「节点表 + 边表」：节点就是边两端的并集，
     * 让前端自己并一次，省掉一个会和边表不同步的字段。
     *
     * @param name  实体键。商品传 SPU 编号，其余传中文名（大小写与空格不敏感）
     * @param depth 跳数，1–3，默认 2
     */
    @GetMapping("/entity")
    public Result<List<GraphEdge>> entity(@RequestParam("name") String name,
                                          @RequestParam(value = "depth", defaultValue = "2") int depth) {
        Result<List<GraphEdge>> unavailable = requireGraph();
        return unavailable != null ? unavailable : Result.success(graphService.neighborhood(name, depth));
    }

    /** 实体检索。也给「图谱里到底有没有收录这个词」用 */
    @GetMapping("/search")
    public Result<List<GraphNode>> search(@RequestParam("keyword") String keyword,
                                          @RequestParam(value = "limit", defaultValue = "20") int limit) {
        Result<List<GraphNode>> unavailable = requireGraph();
        return unavailable != null ? unavailable : Result.success(graphService.search(keyword, limit));
    }

    /**
     * 「我手上这几样能不能一起吃」。
     *
     * @param items 逗号分隔的实体键。商品给 SPU 编号，药物/成分给中文名——
     *              如 {@code SPU007,SPU001,华法林}
     */
    @GetMapping("/interactions")
    public Result<InteractionReport> interactions(@RequestParam("items") String items) {
        List<String> keys = Arrays.stream(items.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        // 不可用时不走 requireGraph：报告对象里带着 available=false 与原因，
        // 前端据此显示「未检查」而不是「无冲突」。这里返回 200 是有意的——
        // 这是一份「检查没做成」的报告，不是一次请求失败
        return Result.success(graphService.interactions(keys));
    }

    /** 图谱规模与各类关系条数，用于确认「图到底建起来没有」 */
    @GetMapping("/stats")
    public Result<Map<String, Object>> stats() {
        return Result.success(graphService.stats());
    }

    private <T> Result<T> requireGraph() {
        if (!graphService.store().isAvailable()) {
            return Result.error(503, "知识图谱暂时不可用，这不代表没有查到风险，"
                    + "请稍后重试或改看知识库文档");
        }
        return null;
    }
}
