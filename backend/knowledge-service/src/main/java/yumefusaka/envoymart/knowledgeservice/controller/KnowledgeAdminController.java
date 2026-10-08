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
import yumefusaka.envoymart.knowledgeservice.service.KnowledgeDocBindingService;
import yumefusaka.envoymart.knowledgeservice.entity.MigrationWarningEntity;
import yumefusaka.envoymart.knowledgeservice.mapper.MigrationWarningMapper;
import yumefusaka.envoymart.knowledgeservice.service.KnowledgeDocumentService;
import yumefusaka.envoymart.knowledgeservice.entity.GraphBuildFailureEntity;

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
    private final KnowledgeDocBindingService bindingService;
    private final MigrationWarningMapper migrationWarningMapper;

    public KnowledgeAdminController(KnowledgeDocumentService documentService, GraphService graphService,
                                    KnowledgeDocBindingService bindingService,
                                    MigrationWarningMapper migrationWarningMapper) {
        this.documentService = documentService;
        this.graphService = graphService;
        this.bindingService = bindingService;
        this.migrationWarningMapper = migrationWarningMapper;
    }

    /**
     * 启动期迁移留下的告警。
     * <p>
     * <b>为什么要有这个接口，而不是让它只躺在表里。</b>迁移脚本在加唯一约束撞上历史重复
     * 数据时会跳过该约束并写一条告警 —— 这是对的取舍（宁可少一个约束，也别让服务起不来），
     * 但「跳过」必须有人看得见，否则只是把「启动失败」换成了「约束静默缺失」。
     * 查询接口让管理台一次性回答「本机有没有欠着的迁移」。
     * <p>
     * 倒序返回最近 50 条：这张表平时是空的，真出事时也只需要看最近那几条。
     */
    @GetMapping("/migration-warnings")
    public Result<java.util.List<MigrationWarningEntity>> migrationWarnings() {
        return Result.success(migrationWarningMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<MigrationWarningEntity>()
                        .orderByDesc("at", "id")
                        .last("limit 50")));
    }

    @GetMapping("/graph/failures")
    public Result<java.util.List<GraphBuildFailureEntity>> graphFailures(
            @RequestParam(required = false) String batchId,
            @RequestParam(required = false) String docNo,
            @RequestParam(required = false) String stage,
            @RequestParam(required = false) String reasonCode,
            @RequestParam(defaultValue = "100") int limit) {
        return Result.success(graphService.failures(batchId, docNo, stage, reasonCode, limit));
    }

    @GetMapping("/graph/failures/{id}")
    public Result<GraphBuildFailureEntity> graphFailure(@PathVariable Long id) {
        GraphBuildFailureEntity value = graphService.failure(id);
        return value == null ? Result.error(404, "图谱失败记录不存在") : Result.success(value);
    }

    @GetMapping("/graph/failures/stats")
    public Result<java.util.Map<String, Object>> graphFailureStats(@RequestParam(required = false) String batchId) {
        return Result.success(graphService.failureStats(batchId));
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
    /**
     * 某个商品的**归属**文档 —— 与 {@link #productDocuments} 是两件事，不是重复。
     * <p>
     * {@code productDocuments} 读的是**图**（模型从正文里抽出来的边），回答「这个商品在图上
     * 连着什么资料」；这里读的是**关联表**（上传时人工声明的归属），回答「这个商品在册的
     * 说明书是哪几篇」。两者不一致本身就是有价值的信号：
     * 关联表有而图上没有 → 声明了归属但图谱没建出边（抽取漏了商品端）；
     * 图上有而关联表没有 → 历史数据没回填。
     * 合成一个接口会让这两种状态无法区分，而它们的处置完全不同。
     */
    @GetMapping("/products/{spuId}/bindings")
    public Result<java.util.List<String>> productBindings(@PathVariable("spuId") Long spuId) {
        return Result.success(bindingService.subjectDocsOf(spuId));
    }

    /** 一篇文档归属的商品 id */
    @GetMapping("/documents/{docNo}/bindings")
    public Result<java.util.List<Long>> documentBindings(@PathVariable("docNo") String docNo) {
        return Result.success(bindingService.subjectSpusOf(docNo));
    }

    @PutMapping("/documents/{docNo}/status")
    public Result<Void> changeStatus(@PathVariable("docNo") String docNo,
                                     @RequestParam("status") int status) {
        // 管理台是人工动作，原因记为 MANUAL：商品重新上架时不该把它打开
        documentService.changeStatus(docNo, status, null);
        return Result.success();
    }
}
