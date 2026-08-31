package yumefusaka.envoymart.agent.rag;

import java.util.List;

/**
 * 重排器 —— 对召回结果做精排。
 * <p>
 * 召回（BM25 / 向量）追求"不漏"，重排追求"排得准"：
 * 用 cross-encoder 让 query 与候选文档逐对打分，比双塔向量相似度更准，
 * 但只适合对少量候选做，所以放在召回之后。
 */
public interface Reranker {

    /**
     * @param query      原始查询
     * @param candidates 召回结果（已按融合分排序）
     * @param topK       重排后返回条数
     */
    List<DocumentChunk> rerank(String query, List<DocumentChunk> candidates, int topK);

    /** 不重排，直接截断——未配置重排服务时的降级实现。 */
    Reranker NOOP = (query, candidates, topK) ->
            candidates.stream().limit(topK).toList();
}
