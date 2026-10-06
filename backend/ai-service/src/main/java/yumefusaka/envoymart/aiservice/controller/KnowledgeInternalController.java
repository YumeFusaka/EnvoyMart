package yumefusaka.envoymart.aiservice.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import yumefusaka.envoymart.aiservice.knowledge.KnowledgeIndexer;
import yumefusaka.envoymart.common.result.Result;
import yumefusaka.envoymart.contract.KnowledgeIndexStatus;

/**
 * 索引重建的服务间通道 —— 供 product-service 在商品上下架联动时调用。
 * <p>
 * <b>为什么不复用管理台的 {@code /ai/admin/knowledge/reindex/{docNo}}</b>：
 * 那条路要求「人来点」，走网关取用户身份、按角色判定（{@code @RequireAdmin}）。
 * 商品上下架是服务间动作，请求里没有用户身份，打到 admin 段只会被拦成 401——
 * 实测就是 {@code [401] ... 未登录}，而失败还被 {@code KnowledgeLifecycleSync}
 * 吞成一条 WARN，症状是「文档状态确实变了、索引却一直没跟上」。
 * <p>
 * 放在 {@code /ai/internal/} 下：网关对这段一律 404（见 {@code INTERNAL_ONLY_PREFIXES}），
 * 外部够不着；服务间调用靠 Feign 注入的 {@code X-Internal-Token} 建立信任。
 * 两条路并存不是冗余——管理台要身份与追责，联动要的是够不着。
 */
@Slf4j
@RestController
@RequestMapping("/ai/internal/knowledge")
public class KnowledgeInternalController {

    private final KnowledgeIndexer indexer;

    public KnowledgeInternalController(KnowledgeIndexer indexer) {
        this.indexer = indexer;
    }

    /**
     * 单篇增量重建 —— <b>发起即返回</b>。
     * <p>
     * 传进来的 {@code docNo} 若已不在语料中（如刚被停用），会执行「只删不写」，
     * 把它的向量与图谱边清掉——这正是商品下架时想要的效果。
     * <p>
     * <b>为什么返回的不是最终结果</b>：单篇重建要走一次模型抽取，实测约 120 秒，
     * 远超调用方 Feign 的 30 秒读超时。同步返回会让「超时」与「成功」在日志里长得一样
     * （调用方看到 Read timed out，索引其实建好了），所以这里只回「已发起」，
     * 真正的结果由 {@code GET /ai/admin/knowledge/status} 观察。
     * <p>
     * 返回值里的 {@code running=true} 表示「已受理、正在跑或排在已有重建之后」。
     * <b>忙时不丢弃而是排队</b>：调用方要的语义是「这件事一定会发生」——
     * 忙时直接返回会让刚下架的说明书继续留在索引里，而调用方只看到一条成功日志。
     * 所以这一篇会入队，等当前那次重建结束后被排空。
     */
    @PostMapping("/reindex/{docNo}")
    public Result<KnowledgeIndexStatus> reindexOne(@PathVariable("docNo") String docNo) {
        log.info("[内部] 收到单篇索引重建请求（异步） docNo={}", docNo);
        KnowledgeIndexer.Status s = indexer.rebuildOneAsync(docNo);
        return Result.success(KnowledgeIndexStatus.builder()
                .running(s.running()).startedAt(s.startedAt()).finishedAt(s.finishedAt())
                .error(s.error()).build());
    }
}