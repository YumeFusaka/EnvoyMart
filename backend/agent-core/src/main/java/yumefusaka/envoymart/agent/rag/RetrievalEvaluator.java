package yumefusaka.envoymart.agent.rag;

import java.util.List;

/**
 * 检索质量评测器 —— 用标注数据算出可复现的指标，替代"感觉还行"。
 * <p>
 * 三个指标覆盖不同侧面：
 * <ul>
 *   <li>Hit Rate@K：Top-K 里是否至少命中一篇相关文档（召回有没有）</li>
 *   <li>MRR：第一篇相关文档排在第几位（排序好不好）</li>
 *   <li>NDCG@K：整体排序质量，越靠前的相关文档权重越高</li>
 * </ul>
 */
public class RetrievalEvaluator {

    /** 一条评测样本：查询 + 该查询的正确文档 ID 集合。 */
    public record EvalCase(String query, List<String> relevantDocIds) {
    }

    public record EvalReport(int caseCount, int topK, double hitRate, double mrr, double ndcg) {
        @Override
        public String toString() {
            return String.format("cases=%d topK=%d hitRate=%.3f mrr=%.3f ndcg=%.3f",
                    caseCount, topK, hitRate, mrr, ndcg);
        }
    }

    /**
     * 一条样本的逐条结果。
     * <p>
     * 聚合指标回答"整体多少分"，逐条结果回答"<b>谁没中、检索到的又是什么</b>"——
     * 报告页上真正让人信服的部分是后者：失败样本摆在明面上，才有人信前面的平均分。
     * {@code hitRank} 为 0 表示未命中；{@code ndcg} 是该条自己的得分。
     */
    public record CaseOutcome(String query, List<String> relevantDocIds, List<String> retrievedDocIds,
                              boolean hit, int hitRank, double ndcg) {
    }

    /**
     * 逐条评测。检索只跑一遍——聚合报告与逐条明细都从这一份结果推导，
     * 两边各检一次的话，两处实现漂移时页面上的"整体分"和明细就再也对不上账。
     */
    public List<CaseOutcome> evaluateEach(Retriever retriever, List<EvalCase> cases, int topK) {
        List<CaseOutcome> outcomes = new java.util.ArrayList<>(cases.size());
        for (EvalCase evalCase : cases) {
            List<String> retrieved = retriever.retrieve(evalCase.query(), topK).stream()
                    .map(DocumentChunk::getDocId)
                    .toList();
            int rank = hitRank(evalCase, retrieved);
            outcomes.add(new CaseOutcome(evalCase.query(), evalCase.relevantDocIds(), retrieved,
                    rank > 0, rank, ndcg(evalCase, retrieved, topK)));
        }
        return outcomes;
    }

    public EvalReport evaluate(Retriever retriever, List<EvalCase> cases, int topK) {
        return summarize(evaluateEach(retriever, cases, topK), topK);
    }

    /**
     * 把逐条结果汇总成聚合指标 —— 分层指标与整体指标必须出自同一份逐条结果。
     * 报告页会同时显示「整体的 0.633」与「三档各自的分数」，两处各算各的话，
     * 出现 40+40+40 与 120 对不上时没人知道是哪一边算错了。
     */
    public static EvalReport summarize(List<CaseOutcome> outcomes, int topK) {
        if (outcomes.isEmpty()) {
            return new EvalReport(0, topK, 0, 0, 0);
        }

        double hitSum = 0;
        double mrrSum = 0;
        double ndcgSum = 0;
        for (CaseOutcome outcome : outcomes) {
            hitSum += outcome.hit() ? 1 : 0;
            mrrSum += outcome.hitRank() > 0 ? 1.0 / outcome.hitRank() : 0;
            ndcgSum += outcome.ndcg();
        }

        int n = outcomes.size();
        return new EvalReport(n, topK, hitSum / n, mrrSum / n, ndcgSum / n);
    }

    /** 第一篇相关文档排在第几位（1 起）；未命中为 0。 */
    private int hitRank(EvalCase evalCase, List<String> retrieved) {
        for (int i = 0; i < retrieved.size(); i++) {
            if (evalCase.relevantDocIds().contains(retrieved.get(i))) {
                return i + 1;
            }
        }
        return 0;
    }

    /** DCG 用 1/log2(rank+1) 折损，IDCG 为理想排序下的 DCG。 */
    private double ndcg(EvalCase evalCase, List<String> retrieved, int topK) {
        double dcg = 0;
        for (int i = 0; i < retrieved.size(); i++) {
            if (evalCase.relevantDocIds().contains(retrieved.get(i))) {
                dcg += 1.0 / (Math.log(i + 2) / Math.log(2));
            }
        }
        int idealHits = Math.min(evalCase.relevantDocIds().size(), topK);
        double idcg = 0;
        for (int i = 0; i < idealHits; i++) {
            idcg += 1.0 / (Math.log(i + 2) / Math.log(2));
        }
        return idcg == 0 ? 0 : dcg / idcg;
    }
}
