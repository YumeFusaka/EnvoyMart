package yumefusaka.envoymart.agent.rag;

import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 多查询检索 —— 检索前先扩写，再让原句与变体一起进候选池。
 * <p>
 * <b>为什么是一个装饰器，而不是写在 HybridRetriever 里：</b>扩写需要一个模型，
 * 而 {@link HybridRetriever} 是一个纯检索算法（BM25 + 向量 + 图谱 + RRF），
 * 它的每一个单测都不该被迫拖上一个模型。分开之后，「加扩写」与「不加扩写」
 * 就是同一个检索器上的两种包装，评测里能拿同一份语料跑出真正的对照——
 * 加前加后的差别只来自扩写这一个变量。
 * <p>
 * <b>为什么包在这一层，而不是对话层：</b>检索有两个入口——Agent 主链路的 RAG
 * 检索与 {@code KnowledgeSearchTool} 的 ReAct 再检索。两个入口都经过 retriever，
 * 所以包在这里一次就够；包在任一入口里，另一条就成了分叉：同一个知识库，
 * 主链路走扩写、工具再检索走原句，两条路的召回口径从此不同。
 * <p>
 * <b>它只影响检索。</b>指代消解那句改写有四个消费方（检索、情节记忆召回、
 * 意图路由、确定性流程参数），本类的产出<b>不是</b>那句改写——改写句仍然只有一份，
 * 由 {@code QueryRewriter} 产出。假想答案是「一段像资料的文字」，拿它去召回情节记忆
 * 或路由意图，等于让模型对着自己编的东西做判断。
 * <p>
 * <b>扩写失败不抛异常。</b>实现者本就不该抛（见 {@link QueryExpander}），这里再兜一层：
 * 这是检索主流程，任何漏出来的异常都会顺着 {@code retrieve} 冒到回答侧，
 * 表现成「用户看不到回答」——一个可选增强的失败代价不该是主功能不可用。
 */
@Slf4j
public class MultiQueryRetriever implements Retriever {

    private final HybridRetriever base;
    private final QueryExpander expander;

    public MultiQueryRetriever(HybridRetriever base, QueryExpander expander) {
        this.base = base;
        this.expander = expander;
    }

    @Override
    public List<DocumentChunk> retrieve(String query, int topK) {
        QueryExpansions expansions;
        try {
            expansions = expander.expand(query);
        } catch (RuntimeException e) {
            log.warn("[QueryExpand] 扩写失败，本次按原句检索：{}", e.toString());
            expansions = QueryExpansions.none();
        }
        return base.retrieve(query, expansions == null ? QueryExpansions.none() : expansions, topK);
    }
}
