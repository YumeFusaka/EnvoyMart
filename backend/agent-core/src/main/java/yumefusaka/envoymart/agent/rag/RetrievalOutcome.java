package yumefusaka.envoymart.agent.rag;

import java.util.List;

/**
 * 一次检索的完整产出 —— <b>交付给下游的切片 + 检索过程本身提供的事实</b>。
 * <p>
 * 这两个东西过去被压成一个 {@code List<DocumentChunk>}，代价是「结果列表被截断」
 * 与「过程里发生过什么」分不开：重排按 topK 截断是给模型看正文用的，
 * 而「本轮图谱路命中过哪几片」是拒答门要的判据（见 {@link EvidenceGate}）——
 * 后者不该因为前者截断而消失。
 * <p>
 * 判据是「这件事还有谁能算出来」：图谱是否触达，只有检索器知道，下游就算拿着完整
 * 结果列表也推不出来（图谱切片可能压根没排进 topK）。凡是下游推不出来的事实，
 * 就必须由上游显式带出来。
 *
 * @param chunks          交付给下游的切片（已重排、已截断到 topK）
 * @param graphChunkIds   本轮图谱路命中、且成功并入候选池的切片 key；空表示本轮无图谱依据
 * @param graphCandidateInPool 图谱切片是否进过融合池（区分「图谱没召回」与「召回了但被截掉」）
 * @param expansions      本轮实际生效的扩写（假想答案 + 角度改写）。空表示没有扩写，
 *                        检索走的是与改造前逐位相同的那条路
 */
public record RetrievalOutcome(List<DocumentChunk> chunks,
                               java.util.Set<String> graphChunkIds,
                               boolean graphCandidateInPool,
                               QueryExpansions expansions) {

    public RetrievalOutcome {
        chunks = chunks == null ? List.of() : List.copyOf(chunks);
        graphChunkIds = graphChunkIds == null ? java.util.Set.of() : java.util.Set.copyOf(graphChunkIds);
        expansions = expansions == null ? QueryExpansions.none() : expansions;
    }

    /** 纯文本检索的产出：没有任何图谱事实 */
    public static RetrievalOutcome textOnly(List<DocumentChunk> chunks) {
        return new RetrievalOutcome(chunks, java.util.Set.of(), false, QueryExpansions.none());
    }

    /** 本轮是否有图谱依据在场 */
    public boolean hasGraphEvidence() {
        return !graphChunkIds.isEmpty();
    }
}