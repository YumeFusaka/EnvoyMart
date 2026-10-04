package yumefusaka.envoymart.aiservice.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import yumefusaka.envoymart.aiservice.knowledge.KnowledgeIndexer;
import yumefusaka.envoymart.common.result.Result;
import yumefusaka.envoymart.common.web.RequireAdmin;

/**
 * 检索索引的管理接口 —— 知识库管理台调的就是它。
 * <p>
 * 改完知识库之后索引不会自己变——知识库是事实源，索引是派生物，两者之间需要一次显式重建。
 * 这就是那一次。
 * <p>
 * <b>为什么是 {@code /ai/admin/} 而不是 {@code /ai/internal/}</b>：
 * 这两条路的信任模型完全不同。{@code /internal/} 是服务间通道——网关直接 404，
 * 请求里<b>没有用户身份</b>，靠的是「够不着」而不是「验过身份」；而这个接口要由<b>人</b>在管理台上点，
 * 它必须走网关拿到身份，再按角色判定。把运维接口放在 {@code /internal/} 下，
 * 等于把一个要人来点的按钮放进了一条没有身份概念的通路里。
 * <p>
 * 重建索引会让<b>所有人的</b> AI 回答在这段时间落到一个空索引上（重建是「先清空、再写入」），
 * 所以它是管理动作，不是用户动作——因此整个类标了 {@link RequireAdmin}，
 * 由 {@code AdminGuardInterceptor} 按 {@code X-User-Role} 判定；
 * 而那个头只有网关能注入（它从签了名的 Token 里取角色，且会先剥掉客户端自带的）。
 */
@Slf4j
@RequireAdmin
@RestController
@RequestMapping("/ai/admin/knowledge")
public class KnowledgeAdminController {

    private final KnowledgeIndexer indexer;

    public KnowledgeAdminController(KnowledgeIndexer indexer) {
        this.indexer = indexer;
    }

    /**
     * 重建检索索引：重新拉语料 → 重切 → 覆盖向量库 → 替换 BM25 快照。
     * <p>
     * <b>异步</b>：返回的是「已开始，当前状态是这样」，不是「重建完成」。
     * 一次完整重建要抽图谱、调十几次模型，实测七十几秒——同步版会让 HTTP 客户端先超时，
     * 而重建其实成功完成了（客户端看到 HTTP 000、系统却是新索引，最难排查的一类不一致）。
     * 要知道跑完没有，看 {@link #status()}。
     * <p>
     * 本来想直接回 202，但这里的响应体是统一 {@code Result} 包装，
     * 状态码得在业务码里表达；调「状态」接口比让调用方解析 HTTP 状态码更直接。
     */
    @PostMapping("/reindex")
    public Result<KnowledgeIndexer.Status> reindex() {
        return Result.success(indexer.rebuildAsync());
    }

    /**
     * 单篇增量重建：管理台上传/编辑/停用一篇文档后调用，让这一篇立刻生效。
     * <p>
     * <b>为什么需要它</b>：全量 {@code /reindex} 是「清空 + 整库 embedding + 全量图谱抽取」，
     * 实测七十几秒、十几次模型调用。上传一篇文档就付这个代价，等于把「改一条规则」
     * 变成「等一分钟、按整库计费」——于是管理台要么很慢，要么（更糟）让人干脆不点重建，
     * 让「知识库改了但 AI 不知道」变成常态。
     * <p>
     * <b>为什么是同步返回</b>：单篇只需一次 embedding 调用 + 一次图谱抽取，
     * 是秒级操作，没有超出 HTTP 超时的风险。全量那边异步是因为它会超时，不是因为
     * 「管理动作都该异步」——给秒级操作套异步只会让调用方多写一轮轮询。
     * <p>
     * <b>停用/删除也走这里</b>：传进来的 {@code docNo} 若已不在语料中（停用的文档不在语料里），
     * 会执行「只删不写」，把它的向量与图谱边清掉。
     */
    @PostMapping("/reindex/{docNo}")
    public Result<KnowledgeIndexer.IncrementalResult> reindexOne(
            @PathVariable("docNo") String docNo) {
        return Result.success(indexer.rebuildOne(docNo));
    }

    /**
     * 重建进度与上次结果。
     * <p>
     * 单列出这个接口的理由：重建是「先清空、再写入」的两步，<b>跑着的那段时间检索是空的</b>，
     * 期间所有 AI 回答都会偏「知识库中没有相关依据」。管理台得能把这段显示出来，
     * 否则运维看着一个看起来正常、实际在降级的系统。
     */
    @GetMapping("/status")
    public Result<KnowledgeIndexer.Status> status() {
        return Result.success(indexer.status());
    }
}
