package yumefusaka.envoymart.productservice.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import yumefusaka.envoymart.common.result.Result;
import yumefusaka.envoymart.contract.KnowledgeIndexStatus;

/**
 * AI 服务客户端 —— 触发知识索引的单篇重建。
 *
 * <p><b>为什么是单篇而不是全量</b>：全量重建是「清空 + 整库 embedding + 全量图谱抽取」，
 * 实测七十几秒、十几次模型调用；而上下架一次只影响这个商品名下的一两篇说明书。
 * 全量重建在这里既慢又花钱，还是非必要的。
 *
 * <p><b>为什么打 {@code /ai/internal/} 而不是 {@code /ai/admin/}</b>：admin 段要求人点、
 * 走网关取用户身份，服务间调用没有身份，实测被拦成 401；internal 段网关一律 404，
 * 外部够不着，信任由 Feign 注入的 X-Internal-Token 建立。
 */
@FeignClient(name = "ai-service", url = "${services.ai-service-url:http://127.0.0.1:9004}")
public interface KnowledgeIndexClient {

    @PostMapping("/ai/internal/knowledge/reindex/{docNo}")
    Result<KnowledgeIndexStatus> reindexOne(@PathVariable("docNo") String docNo);
}
