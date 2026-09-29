package yumefusaka.envoymart.contract;

import java.util.List;

/**
 * 图上的一条边，<b>连同它的原文出处</b>。
 * <p>
 * 出处不是可选项。图谱的结论要能点回原文——{@code quoteStart}/{@code quoteEnd} 是
 * 该引文在文档正文里的 UTF-16 偏移，与切片层同一套坐标系，前端可据此在文档页高亮。
 * 少了这层，「知识图谱给出的结论」和「模型自己说的」在演示上没有任何区别。
 * <p>
 * 两端是完整的 {@link GraphNode} 而不是两个字符串：节点键（{@code name}，商品是 SPU 编号）
 * 与展示名（{@code label}，商品是商品名）必须都带出来。只给展示名的话前端连不出图
 * （同一节点在不同文档里的写法可能不同），只给键的话界面上会冒出 {@code SPU007}。
 *
 * @param docTitle 文档标题。由服务层按 docId 解析后回填，图谱里只存 docId
 * @param counterpart 这条边上<b>不是用户问的那样东西</b>的那一端。{@code head}/{@code tail}
 *                    保留图的真实方向（{@code CAUTION_FOR} 这类关系方向有语义，不能翻），
 *                    所以「哪一端是我问的」必须另外说清楚，否则查药物时会渲染成
 *                    「华法林 与 华法林 有相互作用」——两个端点恰好都是它自己，
 *                    而调用方没有任何依据分辨。仅在相互作用查询里有值
 * @param chain    从用户提到的那个东西走到本边端点的中文名链，如 {@code [鱼油软胶囊, 深海鱼油]}。
 *                 仅在相互作用查询里有值；普通邻域查询为空
 */
public record GraphEdge(
        GraphNode head,
        String relation,
        String effect,
        GraphNode tail,
        String docId,
        String docTitle,
        String chunkId,
        int quoteStart,
        int quoteEnd,
        String quote,
        GraphNode counterpart,
        List<String> chain) {

    public GraphEdge withDocTitle(String title) {
        return new GraphEdge(head, relation, effect, tail, docId, title,
                chunkId, quoteStart, quoteEnd, quote, counterpart, chain);
    }

    public GraphEdge withChain(List<String> via) {
        return new GraphEdge(head, relation, effect, tail, docId, docTitle,
                chunkId, quoteStart, quoteEnd, quote, counterpart, via);
    }

    public GraphEdge withCounterpart(GraphNode node) {
        return new GraphEdge(head, relation, effect, tail, docId, docTitle,
                chunkId, quoteStart, quoteEnd, quote, node, chain);
    }
}
