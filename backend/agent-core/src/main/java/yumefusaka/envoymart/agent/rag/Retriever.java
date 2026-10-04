package yumefusaka.envoymart.agent.rag;

import java.util.List;

/**
 * 检索器接口 —— 返回与查询相关的知识片段。
 */
public interface Retriever {

    List<DocumentChunk> retrieve(String query, int topK);

    /**
     * 带请求级事实的检索。
     * <p>
     * <b>为什么需要它：</b>检索结果是一个<b>被截断过的交付列表</b>——重排只留 topK 条，
     * 排在后面的依据会被丢掉。绝大多数下游只该看这个列表，但有一类判断不能：
     * 「本轮有没有图谱依据在场」（见 {@link EvidenceGate} 的图谱豁免）。
     * <p>
     * 实测到的失效（U76 第三层，2026-10-03）：融合池里确实有图谱切片，但它正文是
     * 「图谱推导：…」的转述、字面与问题不像，重排分天然低，{@code candidates=9 kept=3}
     * 时被整条截掉。于是拒答门拿到的判据列表里根本没有它——<b>豁免分支不是没触发，
     * 是它的输入根本没送到</b>。把「图谱路触达过哪几片」压进返回列表去表达，
     * 就等于要求「必须排进 topK 才算触达过」，而这两件事没有关系。
     * <p>
     * 因此把「本轮触达过图谱依据」提成与结果列表并列的请求级事实，由最懂检索过程的
     * 那一层（{@link HybridRetriever}）如实产出，一路传到拒答门。默认实现返回
     * {@link RetrievalOutcome#textOnly}，保证「不关心这件事」的实现与调用方零成本。
     */
    default RetrievalOutcome retrieveWithOutcome(String query, int topK) {
        return RetrievalOutcome.textOnly(retrieve(query, topK));
    }
}