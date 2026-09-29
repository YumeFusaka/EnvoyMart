package yumefusaka.envoymart.knowledgeservice.seed;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import yumefusaka.envoymart.knowledgeservice.service.KnowledgeDocumentService;

/**
 * 启动时把种子语料灌进库。
 * <p>
 * <b>为什么失败要放行、成功要显眼。</b>装载失败会让服务起不来，而知识库起不来
 * 又会拖垮 ai-service——为了让「一份新克隆的仓库能直接跑」而把一个可选的初始化步骤
 * 变成硬失败，代价不成比例。但跳过必须是<b>响的</b>：日志按 error 打，并且明说
 * 「知识库当前为空，AI 回答会全部无依据」，而不是安静地少一行 INFO。
 * <p>
 * 真正的兜底在 ai-service 那边：它拉到空语料会拒绝启动。所以这里放行的后果是
 * 「AI 服务明确报错」，不是「AI 服务无症状地胡说」。
 */
@Slf4j
@Component
public class SeedRunner implements ApplicationRunner {

    private final KnowledgeDocumentService documentService;

    public SeedRunner(KnowledgeDocumentService documentService) {
        this.documentService = documentService;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            documentService.seed();
        } catch (RuntimeException e) {
            log.error("[Knowledge] 种子语料导入失败 —— 知识库当前可能为空，"
                    + "AI 回答会全部落到「知识库中没有相关依据」。"
                    + "检查 resources/knowledge/*.md 的 front-matter，或调用 "
                    + "POST /knowledge/internal/reseed 重试", e);
        }
    }
}
