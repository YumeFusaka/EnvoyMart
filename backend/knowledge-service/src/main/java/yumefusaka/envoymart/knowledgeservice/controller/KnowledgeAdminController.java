package yumefusaka.envoymart.knowledgeservice.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import yumefusaka.envoymart.common.result.Result;
import yumefusaka.envoymart.common.web.RequireAdmin;
import yumefusaka.envoymart.knowledgeservice.model.DocumentUpsertRequest;
import yumefusaka.envoymart.knowledgeservice.graph.GraphService;
import yumefusaka.envoymart.knowledgeservice.service.KnowledgeDocumentService;

/**
 * 知识库管理接口 —— 上架一篇文档、停用一篇文档。
 * <p>
 * <b>为什么是 {@code /knowledge/admin/} 而不是 {@code /knowledge/}</b>：
 * {@code /knowledge/**} 是公开只读的（引用要让任何人能自己核对），
 * 若把写接口挂在同一个前缀下，网关的白名单是 {@code GET} 精确匹配、不覆盖 POST，
 * 但更重要的是「同前缀 = 同一道门」这个心智模型：读的门开着，写就必须换一扇门。
 * {@code /admin} 是网关 {@code ADMIN_SEGMENT} 的强制登录取值，
 * 与本类上的 {@link RequireAdmin} 构成两层——网关先挡未登录，服务再挡非管理员。
 * <p>
 * <b>写入之后要重建索引</b>：这里落的是事实源（文档与切片），向量库与图谱是派生物。
 * 不重建的话，管理台上能看到新文档、检索却永远搜不到它——所以调用方（管理台）
 * 在写入成功后要接着调 ai-service 的 {@code /ai/admin/knowledge/reindex}。
 * 这条链路刻意不进知识服务的写入接口：重建要拉全量语料并调模型，是分钟级动作，
 * 塞进一个 HTTP 写请求里会让管理台的保存按钮转很久，且失败后无法区分
 * 「文档没存上」与「存上了但索引没重建」。
 */
@Slf4j
@RestController
@RequireAdmin
@RequestMapping("/knowledge/admin")
public class KnowledgeAdminController {

    private final KnowledgeDocumentService documentService;
    private final GraphService graphService;

    public KnowledgeAdminController(KnowledgeDocumentService documentService, GraphService graphService) {
        this.documentService = documentService;
        this.graphService = graphService;
    }

    /**
     * 新建或按编号更新一篇文档。
     * <p>
     * {@code docNo} 留空即新建（服务端顺延分配编号），填了就是更新那一篇。
     * 更新会重切切片——正文变了，旧切片的 charOffset 会指向错误的原文位置，
     * 而引用正是靠它回跳的。
     */
    @PostMapping("/documents")
    public Result<String> upsert(@RequestBody DocumentUpsertRequest request) {
        String docNo = documentService.upsert(request);
        log.info("[Knowledge] 管理端写入文档 {} 《{}》", docNo, request.getTitle());
        return Result.success(docNo);
    }

    /**
     * 某个商品当前被哪些文档支持 —— 「这个商品的说明书接上了没有」。
     * <p>
     * 商品与说明书在库里没有外键，绑定是构建期实体链接的结果。所以这个接口读的是**图**，
     * 不是文档表：只有当说明书正文里确实抽出了指向该商品的关系，它才会出现在这里。
     * 上传了但链接不上（正文没提商品、或提的写法对不上目录）时返回空——这正是管理台
     * 需要看到的那个状态。
     * <p>
     * 挂在管理前缀下而不是公开前缀：它是运维视图，不是给用户看的知识。
     */
    @GetMapping("/products/{spuKey}/documents")
    public Result<java.util.List<GraphService.ProductDocRef>> productDocuments(
            @PathVariable("spuKey") String spuKey) {
        return Result.success(graphService.documentsOfProduct(spuKey));
    }

    /**
     * 停用 / 启用。
     * <p>
     * 停用而不删除：引用是跨会话存在的历史事实，删掉会让已经发出去的 {@code [3]}
     * 点开是 404。停用只让它退出检索索引（{@code corpus()} 只下发启用状态的文档）。
     */
    @PutMapping("/documents/{docNo}/status")
    public Result<Void> changeStatus(@PathVariable("docNo") String docNo,
                                     @RequestParam("status") int status) {
        documentService.changeStatus(docNo, status);
        return Result.success();
    }
}
