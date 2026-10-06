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
 * <p>
 * <b>图谱依据在场时判定不低于 SUFFICIENT。</b>这条规则看着像开后门，其实是在纠正一把
 * 用错了的尺子：跨编码器量的是 query 与文本的<b>字面语义距离</b>，而图谱依据的相关性
 * 是<b>结构</b>给的——用户问「SPU7 有什么禁忌」，原文写「出血性疾病患者……应咨询医师」，
 * 这两句话本来就不像，任何文本相似度都会给它打低分，而它恰恰是唯一正确的答案。
 * 实测到过这个失效：整轮重排分 0.126–0.157 全在阈值 0.20 之下，判定成 WEAK，
 * 于是模型答对了却要补一句「该条目相关度不足，不能作为权威依据」——
 * 系统明明握着一句原文，却告诉用户别信它。
 * <p>
 * 敢这么放行的依据是图谱依据的<b>准入门槛不在这一层</b>：能出现在这里的边，
 * 已经过实体链接、关系类型过滤，且引文必须逐字出现在<b>事实源文档</b>里
 * （见 knowledge-service 的 {@code TripleValidator}）。一条「两个实体之间确有这个关系、
 * 文档里确有这句话」的依据，比一条「语义上很像但可能答非所问」的切片更硬。
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

        /**
         * 「本轮没有知识依据」的判定。
         * <p>
         * 用在「检索还没发生」的时刻：此时确实没有依据，而下游的 prompt 组装要一个判定
         * 才不会把切片误当依据。用一个显式工厂而不是 {@code null}，是为了让调用点读起来
         * 就是「这里判定为无依据」，而不是「这里忘了传」。
         */
        public static Decision none() {
            return new Decision(Level.NONE, null, "未检索");
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
        return evaluate(chunks, thresholds, java.util.Set.of());
    }

    /**
     * 带请求级图谱事实的判定。
     * <p>
     * @param graphChunkIds 本轮图谱路<b>触达过</b>的切片 key（可能已被重排截断、不在
     *                      {@code chunks} 里）。空表示本轮无图谱依据。
     *                      <p>
     *                      这个参数存在的理由见 U76 第三层：图谱切片正文是「图谱推导：…」
     *                      的转述，字面与问题不像，重排分天然低，topK 截断会把它整条丢掉。
     *                      只看 {@code chunks} 的话，豁免分支在真实链路里永远没有输入——
     *                      <b>不是没触发，是判据根本没送到。</b>
     */
    public static Decision evaluate(List<DocumentChunk> chunks, Thresholds thresholds,
                                    java.util.Set<String> graphChunkIds) {
        if (chunks == null || chunks.isEmpty()) {
            return new Decision(Level.NONE, null, "未召回任何切片");
        }

        boolean requestLevelGraph = graphChunkIds != null && !graphChunkIds.isEmpty();

        DocumentChunk top = null;
        boolean hasGraph = false;
        DocumentChunk topGraph = null;
        for (DocumentChunk chunk : chunks) {
            if (chunk == null) {
                continue;
            }
            // 判定依据是「本轮图谱路命中过某一片」，不是「这条切片本身的 source 是 graph」。
            // 后者是 U76 的病根：图谱边与知识库切片归一到同一个 chunkId，融合留的是先到的
            // 文本版（source=manual），图谱身份在这一步就蒸发了——单测里手动构造
            // source=graph 的切片能过，真实链路里永远构造不出来。
            if (Boolean.TRUE.equals(chunk.getGraphBacked())
                    || DocumentChunk.SOURCE_GRAPH.equals(chunk.getSource())) {
                if (chunk.getScore() != null
                        && (topGraph == null || chunk.getScore() > topGraph.getScore())) {
                    topGraph = chunk;
                }
                hasGraph = true;
            }
            if (chunk.getScore() == null) {
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

        // 请求级图谱事实：图谱路本轮触达过某片依据，而那片可能已经被重排截掉。
        // 它与「切片自带 graphBacked 标记」是同一件事的两个来源——后者描述结果列表里
        // 能看见的，前者描述检索过程里发生过的。两者任一成立都算「图谱依据在场」。
        hasGraph = hasGraph || requestLevelGraph;

        boolean reranked = Boolean.TRUE.equals(top.getReranked());
        double threshold = reranked ? thresholds.minRerankScore() : thresholds.minSimilarity();
        String scale = reranked ? "重排分" : "余弦相似度";
        double score = top.getScore();

        if (score < threshold) {
            // 图谱依据在场时分数照报（日志要如实记下这一轮文本路有多弱），但判定提到 SUFFICIENT。
            //
            // <b>「在场」还不够，它还必须是本轮最强的那条。</b>实测反例：用户问「K2 和鱼油能
            // 一起吃吗」，图上没有 K2，实体链接只命中鱼油，召回的全是鱼油的边；这些边带着
            // graphBacked 标记把判定从 WEAK 抬成 SUFFICIENT，于是「K2 未收录」被说成了
            // 「有图谱依据」。判据收窄后，只有被重排器认可（分数不低于最强文本切片）的图谱
            // 依据才有资格替整轮背书——转述文本的字面相似度低是正常的，但低到连候选里都
            // 不是最强的，说明它并没有回答用户的问题
            //
            // <b>请求级事实走一条不同的判据。</b>图谱切片被重排截掉时它连分数都没有，
            // 自然「不是最强的那条」——但被截掉的原因是它的正文是转述、字面分天然低，
            // 与「它没回答用户的问题」是两回事。这种情形由图谱路自己的召回决定：
            // 能进图谱路的边，已经过实体链接与关系过滤，且引文必须逐字出现在事实源文档里
            // （见 knowledge-service 的 TripleValidator）——门槛在检索之前就已经把过了。
            // 因此「图谱触达过、且本轮文本路确实弱」→ 按足够处理。
            // K2 反例（图上没有 K2、只召回鱼油的边）不落在这里：那种情形下图谱切片
            // 仍在结果列表里（不是被截掉的），走 graphIsTop 那半条判据。
            boolean graphIsTop = topGraph != null && topGraph.getScore() >= score;
            boolean graphTruncated = requestLevelGraph && topGraph == null;
            if (hasGraph && (graphIsTop || graphTruncated)) {
                return new Decision(Level.SUFFICIENT, score,
                        "%s %.4f 低于阈值 %.2f，但本轮有图谱依据在场，按足够处理"
                                .formatted(scale, score, threshold)
                                + (graphTruncated ? "（图谱切片被重排截断，依据来自检索过程记录）" : ""));
            }
            return new Decision(Level.WEAK, score,
                    "%s %.4f 低于阈值 %.2f".formatted(scale, score, threshold));
        }
        return new Decision(Level.SUFFICIENT, score,
                "%s %.4f 达到阈值 %.2f".formatted(scale, score, threshold));
    }
}
