package yumefusaka.envoymart.knowledgeservice.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import yumefusaka.envoymart.common.result.Result;
import yumefusaka.envoymart.knowledgeservice.model.ChunkDetail;
import yumefusaka.envoymart.knowledgeservice.model.DocumentDetail;
import yumefusaka.envoymart.knowledgeservice.model.DocumentSummary;
import yumefusaka.envoymart.knowledgeservice.service.KnowledgeDocumentService;

import java.util.List;

/**
 * 知识库只读接口。
 * <p>
 * <b>全部公开可读</b>（网关 PUBLIC_RULES 已放行）。理由是溯源的意义：引用要让
 * <b>任何人</b>都能自己核对，而不是「登录了才给你看依据」。这里没有任何用户数据——
 * 全是平台规则、说明书、监管规范这类本来就该公示的内容。
 * <p>
 * 也正是因为这个前缀是公开的，写接口必须放在别的前缀下（网关
 * {@code INTERNAL_ONLY_PREFIXES} 反向排除），否则「前缀公开」会连带把后来新增的
 * 写接口一起放行——这个坑本仓库真的踩过。
 */
@RestController
@RequestMapping("/knowledge")
public class KnowledgeController {

    private final KnowledgeDocumentService documentService;

    public KnowledgeController(KnowledgeDocumentService documentService) {
        this.documentService = documentService;
    }

    /**
     * 文档列表。
     *
     * @param scope   领域筛选（nutrition / after_sale / ...）
     * @param keyword 标题或标签关键词
     * @param status  0 停用 / 1 启用；不传则全给，管理台要能看到停用的
     */
    @GetMapping("/documents")
    public Result<List<DocumentSummary>> documents(
            @RequestParam(value = "scope", required = false) String scope,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "status", required = false) Integer status) {
        return Result.success(documentService.list(scope, keyword, status));
    }

    /** 文档详情（含全文与切片索引）。这是「点引用跳原文」的目的地。 */
    @GetMapping("/documents/{docNo}")
    public Result<DocumentDetail> document(@PathVariable("docNo") String docNo) {
        return Result.success(documentService.detail(docNo));
    }

    /**
     * 按切片取原文 —— <b>引用回跳的唯一入口</b>。
     * <p>
     * 回答里的 {@code [1]} 点开就是这里：切片原文（模型实际看到的那段字）、
     * 它在全文中的位置、以及所属文档的哪一版。四样少一样，用户就没法自己核对。
     */
    @GetMapping("/chunks/{chunkId}")
    public Result<ChunkDetail> chunk(@PathVariable("chunkId") String chunkId) {
        return Result.success(documentService.chunk(chunkId));
    }
}
