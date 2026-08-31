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

    public EvalReport evaluate(Retriever retriever, List<EvalCase> cases, int topK) {
        if (cases.isEmpty()) {
            return new EvalReport(0, topK, 0, 0, 0);
        }

        double hitSum = 0;
        double mrrSum = 0;
        double ndcgSum = 0;

        for (EvalCase evalCase : cases) {
            List<String> retrieved = retriever.retrieve(evalCase.query(), topK).stream()
                    .map(DocumentChunk::getDocId)
                    .toList();

            hitSum += hit(evalCase, retrieved);
            mrrSum += reciprocalRank(evalCase, retrieved);
            ndcgSum += ndcg(evalCase, retrieved, topK);
        }

        int n = cases.size();
        return new EvalReport(n, topK, hitSum / n, mrrSum / n, ndcgSum / n);
    }

    private double hit(EvalCase evalCase, List<String> retrieved) {
        return retrieved.stream().anyMatch(evalCase.relevantDocIds()::contains) ? 1 : 0;
    }

    private double reciprocalRank(EvalCase evalCase, List<String> retrieved) {
        for (int i = 0; i < retrieved.size(); i++) {
            if (evalCase.relevantDocIds().contains(retrieved.get(i))) {
                return 1.0 / (i + 1);
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
