package yumefusaka.envoymart.knowledgeservice.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import yumefusaka.envoymart.common.result.Result;
import yumefusaka.envoymart.contract.GraphIngestPayload;
import yumefusaka.envoymart.contract.GraphIngestResult;
import yumefusaka.envoymart.contract.KnowledgeDocumentPayload;
import yumefusaka.envoymart.knowledgeservice.graph.GraphService;
import yumefusaka.envoymart.knowledgeservice.service.KnowledgeDocumentService;

import java.util.List;

/**
 * 知识库内部接口 —— 供 ai-service 构建检索索引、以及运维触发种子重导。
 * <p>
 * 路径放在 {@code /knowledge/internal/} 下，网关对这一段一律 404：
 * {@code /knowledge/**} 是公开读的，若只做前缀放行，同前缀下后来新增的内部接口会被
 * 静默放行——本仓库在 {@code /products/stock/} 上真的踩过这个坑。
 */
@Slf4j
@RestController
@RequestMapping("/knowledge/internal")
public class KnowledgeInternalController {

    private final KnowledgeDocumentService documentService;
    private final GraphService graphService;

    public KnowledgeInternalController(KnowledgeDocumentService documentService, GraphService graphService) {
        this.documentService = documentService;
        this.graphService = graphService;
    }

    /**
     * 全量语料（仅启用状态的文档），ai-service 启动时拉取。
     * <p>
     * 一次给全而不是分页：语料是十几篇、几万字符的量级，分页只会让索引构建多出
     * 「拉到一半失败」这个中间态。语料涨到需要分页时，索引构建方式也该换（增量索引），
     * 而不是把分页加上去。
     */
    @GetMapping("/corpus")
    public Result<List<KnowledgeDocumentPayload>> corpus() {
        List<KnowledgeDocumentPayload> corpus = documentService.corpus();
        log.info("[Knowledge] 下发语料 {} 篇，总字符 {}", corpus.size(),
                corpus.stream().mapToInt(doc -> doc.getContent().length()).sum());
        return Result.success(corpus);
    }

    /**
     * 重新导入种子语料（{@code resources/knowledge/*.md}）并重切变更的文档。
     * <p>
     * 给开发期用：改了 md 不想重启整个服务，调它一次即可。
     * 线上改文档走的是管理接口，不是改文件。
     */
    @PostMapping("/reseed")
    public Result<List<String>> reseed() {
        return Result.success(documentService.seed());
    }

    /**
     * 接收一篇文档的图谱贡献，替换该文档在图上的全部旧边。
     * <p>
     * <b>调用即代表一次完整抽取</b>，见 {@link GraphIngestPayload} 的说明：
     * 抽取失败的时侯不要调，空列表与「没抽」在服务端看起来一模一样。
     * <p>
     * 校验（词表 + 引文必须有原文出处）在这里做，不在 ai-service：判据是
     * {@code knowledge_document.content}，那是本服务的事实源。
     */
    @PostMapping("/graph")
    public Result<GraphIngestResult> ingestGraph(@RequestBody GraphIngestPayload payload) {
        return Result.success(graphService.ingest(payload));
    }

    /**
     * 清理已无任何文档支持的孤立实体。整批重建结束后调一次。
     * <p>
     * 与写入分开而不是并进 {@code /graph}：抽取失败时不能调 {@code /graph}（会清空），
     * 但孤立实体照样该清——那边清不掉只会留下几个查不到关系的节点，不影响正确性。
     */
    @PostMapping("/graph/orphans")
    public Result<Void> dropOrphans() {
        graphService.dropOrphans();
        return Result.success();
    }
}
