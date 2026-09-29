package yumefusaka.envoymart.aiservice.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import yumefusaka.envoymart.common.result.Result;
import yumefusaka.envoymart.contract.GraphIngestPayload;
import yumefusaka.envoymart.contract.GraphIngestResult;
import yumefusaka.envoymart.contract.KnowledgeDocumentPayload;

import java.util.List;

@FeignClient(name = "knowledge-service", url = "${services.knowledge-service-url:http://127.0.0.1:9008}")
public interface KnowledgeClient {

    /** 全量语料。知识库是事实源，这里是它唯一对外的语料出口 */
    @GetMapping("/knowledge/internal/corpus")
    Result<List<KnowledgeDocumentPayload>> corpus();

    /**
     * 交一篇文档的图谱贡献，替换它原有的全部边。
     * <p>
     * <b>抽取失败时不要调</b>：空列表与「没抽」在服务端看起来一模一样，
     * 而前者会把这篇文档已经建好的边全部清空。判据在调用方——只有拿到一份
     * 可信的抽取结果才发这个请求。
     */
    @PostMapping("/knowledge/internal/graph")
    Result<GraphIngestResult> ingestGraph(@RequestBody GraphIngestPayload payload);

    /** 整批重建后清理孤立实体 */
    @PostMapping("/knowledge/internal/graph/orphans")
    Result<Void> dropGraphOrphans();
}
