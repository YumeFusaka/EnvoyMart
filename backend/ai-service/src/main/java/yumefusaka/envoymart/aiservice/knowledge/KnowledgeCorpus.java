package yumefusaka.envoymart.aiservice.knowledge;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import yumefusaka.envoymart.agent.rag.Document;
import yumefusaka.envoymart.aiservice.client.KnowledgeClient;
import yumefusaka.envoymart.common.result.Result;
import yumefusaka.envoymart.contract.KnowledgeDocumentPayload;

import java.util.List;

/**
 * 检索语料 —— <b>从 knowledge-service 拉，不再从自己的 classpath 读</b>。
 * <p>
 * 语料原先内联在 ai-service 的 {@code resources/knowledge/*.md} 里。那意味着
 * 「知识库」不是一个能被管理的东西：改一条规则要改代码、重新打包、重启服务，
 * 而且没人能回答「线上现在生效的是哪几份文档」。
 * 现在文档的事实源在 knowledge-service 的库里，ai-service 只是它的一个消费者。
 * <p>
 * <b>为什么在内存里缓存一份。</b>检索时 BM25 要在整个语料上算分，每个请求都去拉一次
 * 是不可能的；而语料只在管理动作（改文档、重建索引）时变化，不是高频路径。
 * 所以：启动拉一次，之后靠 {@link #reload()} 显式刷新。
 * <p>
 * <b>拉不到就拒绝启动。</b>没有任何降级路径——空语料意味着每一句回答都会落到
 * 「知识库中没有相关依据」，而服务的健康检查依然是绿的。一个「看起来正常、实际全在胡说」
 * 的 AI 服务比一个起不来的 AI 服务危险得多。
 */
@Slf4j
@Component
public class KnowledgeCorpus {

    /** 首次拉取的等待上限。ai 与 knowledge 同时启动时用得上，见下面的重试说明 */
    private static final int MAX_ATTEMPTS = 24;
    private static final long RETRY_INTERVAL_MS = 5000;

    private final KnowledgeClient knowledgeClient;

    /**
     * {@code volatile}：{@link #reload()} 由管理动作触发（HTTP 线程），
     * 读它的是每个检索请求（其它线程）。不保证可见性的话，重建索引之后
     * 一部分线程仍会拿着旧语料打分，表现为「重建了但搜到的还是旧内容」——
     * 且只在一部分请求上复现。
     */
    private volatile List<Document> documents = List.of();

    public KnowledgeCorpus(KnowledgeClient knowledgeClient) {
        this.knowledgeClient = knowledgeClient;
        this.documents = fetchWithRetry();
    }

    /** 当前语料。调用方不应修改返回的列表。 */
    public List<Document> documents() {
        return documents;
    }

    /**
     * 重新拉取语料。管理动作「重建索引」的第一步。
     * <p>
     * 抛异常而不是吞掉：调用方需要知道「新的没拉到、现在用的还是旧的」，
     * 否则重建索引会返回成功而索引根本没变。
     */
    public synchronized List<Document> reload() {
        List<Document> fresh = fetch();
        this.documents = fresh;
        return fresh;
    }

    /**
     * 带重试地拉取。
     * <p>
     * 重试不是「容错」而是<b>启动顺序的解耦</b>：ai-service 与 knowledge-service 是同时
     * 启动的，谁先就绪不确定，重试把「谁先起」从部署约束变成了实现细节。
     * 但重试有上限——超过之后仍然上报失败，因为一个永远拉不到语料却一直重试的服务
     * 在运维眼里与健康的服务没有区别。
     */
    private List<Document> fetchWithRetry() {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                List<Document> documents = fetch();
                log.info("[Knowledge] 语料拉取成功（第 {} 次尝试）", attempt);
                return documents;
            } catch (RuntimeException e) {
                last = e;
                log.warn("[Knowledge] 第 {}/{} 次拉取语料失败：{}", attempt, MAX_ATTEMPTS, e.getMessage());
                if (attempt < MAX_ATTEMPTS) {
                    sleep();
                }
            }
        }
        throw new IllegalStateException(
                "拉取知识库语料失败，已重试 " + MAX_ATTEMPTS + " 次。请确认 knowledge-service "
                        + "（默认 http://127.0.0.1:9008）已启动。"
                        + "AI 服务拒绝在语料不可用时启动：空语料的检索结果为空，"
                        + "而「知识库中没有相关依据」会被当成「知识库里确实没写」，"
                        + "两者在用户看来一模一样。", last);
    }

    private List<Document> fetch() {
        Result<List<KnowledgeDocumentPayload>> result = knowledgeClient.corpus();
        if (result == null || result.getCode() == null || result.getCode() != 200) {
            throw new IllegalStateException("知识库返回异常：" + (result == null ? "null" : result.getMsg()));
        }
        List<KnowledgeDocumentPayload> payloads = result.getData();
        if (payloads == null || payloads.isEmpty()) {
            // 与「装载器读到空目录」同一个判断：空语料是部署问题，不是「没有这条知识」
            throw new IllegalStateException("知识库语料为空 —— knowledge-service 起来了但库里一篇文档都没有");
        }
        return payloads.stream().map(KnowledgeCorpus::toDocument).toList();
    }

    private static Document toDocument(KnowledgeDocumentPayload payload) {
        return Document.builder()
                .id(payload.getDocNo())
                .title(payload.getTitle())
                .source(payload.getSource())
                .scope(payload.getScope())
                .version(payload.getVersion())
                .tags(payload.getTags())
                .content(payload.getContent())
                // 归属随语料带过来，图谱构建期据此确定性地补商品→成分边
                .subjectSpuIds(payload.getSubjectSpuIds())
                .build();
    }

    private static void sleep() {
        try {
            Thread.sleep(RETRY_INTERVAL_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待知识库就绪时被中断", e);
        }
    }
}
