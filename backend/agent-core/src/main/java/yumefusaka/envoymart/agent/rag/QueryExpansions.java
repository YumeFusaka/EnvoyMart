package yumefusaka.envoymart.agent.rag;

import java.util.List;

/**
 * 检索查询的扩写结果 —— <b>两类变体，各有各的去处</b>。
 * <p>
 * <b>为什么不是一串平铺的「其它查询句」：</b>假想答案与角度改写补的是两种不同的盲区，
 * 只能喂给不同的路。
 * <ul>
 *   <li>{@code hypothetical} 是一段<b>假想的资料原文</b>（HyDE）——假设知识库里恰好有一段
 *       能回答这个问题的文字，把它写出来，再拿这段文字去检索。它补的是<b>词汇鸿沟</b>：
 *       用户说「东西还没到」，文档写「配送时效」；假想答案会自然地用文档那种口吻说话，
 *       于是向量空间里离真正的文档更近。它的用武之地在<b>语义路</b>。</li>
 *   <li>{@code angles} 是同一件事的<b>不同说法</b> —— 换个词、换个角度问。它补的是
 *       <b>表述差异</b>：词法路（BM25）只认字面重合，用户的那套词与文档的那套词对不上时，
 *       换个说法就有一句能对上。它的用武之地在<b>词法路</b>。</li>
 * </ul>
 * 把假想答案也塞给词法路，是拿一段长文本去稀释真正的查询词元——BM25 的 idf 会把
 * 那些编出来的词也当成信号；反过来说，把角度改写当语义查询用，它又太短、承载不了语义。
 * <b>所以两条路各取所需，而不是无差别地都跑一遍。</b>
 * <p>
 * <b>空值不是失败。</b>{@link #none()} 表示「这次没有扩写」——它必须与改造前的行为
 * <b>逐位相同</b>，因为扩写是一条可选增强：模型超时、没配 Key、开关关掉，都退到这里，
 * 而退化的代价只该是「回到没有它的时候」。
 */
public record QueryExpansions(String hypothetical, List<String> angles) {

    /** 没有扩写。所有降级路径的落点，也是 {@link QueryExpander#NOOP} 的唯一产出 */
    private static final QueryExpansions NONE = new QueryExpansions(null, List.of());

    public static QueryExpansions none() {
        return NONE;
    }

    public QueryExpansions {
        if (angles == null) {
            angles = List.of();
        } else {
            angles = List.copyOf(angles);
        }
    }

    /** 两类变体都没有 —— 检索侧据此走与原实现完全相同的那条路 */
    public boolean isEmpty() {
        return (hypothetical == null || hypothetical.isBlank()) && angles.isEmpty();
    }
}
