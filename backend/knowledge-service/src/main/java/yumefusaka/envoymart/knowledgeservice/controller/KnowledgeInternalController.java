package yumefusaka.envoymart.knowledgeservice.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import yumefusaka.envoymart.common.result.Result;
import yumefusaka.envoymart.contract.KnowledgeDocumentPayload;
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

    public KnowledgeInternalController(KnowledgeDocumentService documentService) {
        this.documentService = documentService;
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
}
