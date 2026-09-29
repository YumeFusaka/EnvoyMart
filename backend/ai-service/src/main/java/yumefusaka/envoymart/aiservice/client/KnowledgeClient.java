package yumefusaka.envoymart.aiservice.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import yumefusaka.envoymart.common.result.Result;
import yumefusaka.envoymart.contract.KnowledgeDocumentPayload;

import java.util.List;

@FeignClient(name = "knowledge-service", url = "${services.knowledge-service-url:http://127.0.0.1:9008}")
public interface KnowledgeClient {

    /** 全量语料。知识库是事实源，这里是它唯一对外的语料出口 */
    @GetMapping("/knowledge/internal/corpus")
    Result<List<KnowledgeDocumentPayload>> corpus();
}
