package yumefusaka.envoymart.aiservice.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import yumefusaka.envoymart.aiservice.knowledge.KnowledgeIndexer;
import yumefusaka.envoymart.common.result.Result;

/**
 * 检索索引的运维接口。
 * <p>
 * 改完知识库之后索引不会自己变——知识库是事实源，索引是派生物，两者之间需要一次显式重建。
 * 这就是那一次。
 * <p>
 * 路径放 {@code /ai/internal/}：网关对 {@code /ai/internal/} 一律 404（与
 * {@code /products/stock/} 同一份排除表）。重建索引会让**所有人的**AI 回答在几十毫秒内
 * 落到一个空索引上，它是运维动作，不是用户动作。
 */
@Slf4j
@RestController
@RequestMapping("/ai/internal/knowledge")
public class KnowledgeAdminController {

    private final KnowledgeIndexer indexer;

    public KnowledgeAdminController(KnowledgeIndexer indexer) {
        this.indexer = indexer;
    }

    /**
     * 重建检索索引：重新拉语料 → 重切 → 覆盖向量库 → 替换 BM25 快照。
     * <p>
     * 返回的是「重建了几篇、几片」，不是「成功」——一个返回 ok 但什么都没重建的接口
     * 与重建失败的接口在调用方看来完全一样。片数是这里唯一能证明它真的做了事的数字。
     */
    @PostMapping("/reindex")
    public Result<KnowledgeIndexer.Result> reindex() {
        return Result.success(indexer.rebuild());
    }
}
