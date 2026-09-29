package yumefusaka.envoymart.agent.rag;

import java.util.List;

/**
 * 拒答门 —— 判定这一轮检索到的证据<b>够不够支撑一个回答</b>。
 * <p>
 * 没有这道门，RAG 的失败模式是「检索到三个不相关的切片，模型照样据此编出一段像样的话」：
 * 有依据外观、没有依据事实。企业场景里这比检索不到更糟——用户看到引用编号会以为已经核对过。
 * <p>
 * <b>判定只看跨查询可比的分数。</b>{@link DocumentChunk#getScore()} 承载的要么是向量余弦
 * 相似度、要么是重排分，两者都是「这对 query-doc 有多相关」的绝对估计。RRF 融合分不在此列：
 * 它的量纲是 {@code 1/(60+rank)}，第 1 名与第 5 名的差异是 1/60 与 1/64，
 * 反映的是名次而不是相关度。
 * <p>
 * <b>两把尺子，两个阈值。</b>余弦相似度与 cross-encoder 打分的分布不同：同一批语料上，
 * 前者「相关」通常在 0.5 以上、「不相关」在 0.35 以下，后者的区分度更极端。
 * 用同一个数字卡两者，等于让其中一条路径的阈值形同虚设。
 * <p>
 * <b>没有分数时放行（fail-open）。</b>{@code score} 为 null 只说明这条链路没提供相关性信号
 * （例如纯关键词命中），不说明它不相关。因为一个缺失的指标就拒绝回答，
 * 是把工程缺陷转嫁成用户的损失。
 */
public final class EvidenceGate {

    private EvidenceGate() {
    }

    public enum Level {
        /** 证据足够，可以据此作答并要求标注引用 */
        SUFFICIENT,
        /** 有召回但相关度低于阈值，只能当线索提示，不能当结论依据 */
        WEAK,
        /** 什么都没召回，必须拒答 */
        NONE
    }

    /**
     * @param level    判定结果
     * @param topScore 本轮最高相关性分；无任何分数时为 {@code null}
     * @param reason   人类可读的判定理由，进日志
     */
    public record Decision(Level level, Double topScore, String reason) {

        public boolean isSufficient() {
            return level == Level.SUFFICIENT;
        }
    }

    /**
     * 阈值配置。
     *
     * @param minSimilarity  向量余弦相似度阈值（{@code reranked=false} 时生效）
     * @param minRerankScore 重排分阈值（{@code reranked=true} 时生效）
     */
    public record Thresholds(double minSimilarity, double minRerankScore) {

        public static Thresholds defaults() {
            return new Thresholds(0.45, 0.20);
        }
    }

    public static Decision evaluate(List<DocumentChunk> chunks, Thresholds thresholds) {
        if (chunks == null || chunks.isEmpty()) {
            return new Decision(Level.NONE, null, "未召回任何切片");
        }

        DocumentChunk top = null;
        for (DocumentChunk chunk : chunks) {
            if (chunk == null || chunk.getScore() == null) {
                continue;
            }
            if (top == null || chunk.getScore() > top.getScore()) {
                top = chunk;
            }
        }

        if (top == null) {
            return new Decision(Level.SUFFICIENT, null,
                    "召回 " + chunks.size() + " 条但未提供相关性分，按放行处理");
        }

        boolean reranked = Boolean.TRUE.equals(top.getReranked());
        double threshold = reranked ? thresholds.minRerankScore() : thresholds.minSimilarity();
        String scale = reranked ? "重排分" : "余弦相似度";
        double score = top.getScore();

        if (score < threshold) {
            return new Decision(Level.WEAK, score,
                    "%s %.4f 低于阈值 %.2f".formatted(scale, score, threshold));
        }
        return new Decision(Level.SUFFICIENT, score,
                "%s %.4f 达到阈值 %.2f".formatted(scale, score, threshold));
    }
}
