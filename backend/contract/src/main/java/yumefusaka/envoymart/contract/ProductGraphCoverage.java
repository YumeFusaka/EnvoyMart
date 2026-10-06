package yumefusaka.envoymart.contract;

import java.util.List;

/**
 * 商品在知识图谱里的资料覆盖读数 —— <b>由 knowledge-service 计算，管理台展示</b>。
 * <p>
 * 回答的是「多少在售商品是有资料的、多少没有」，而不是某一件商品的细节。
 * 这个数字是存储质量的欠账表：缺的那部分，就是用户问到时答不上来的那部分。
 * <p>
 * <b>两种「没有」必须分开</b>：{@link Reason#NO_NODE} 是图上根本没有这个商品节点
 * （从未构建过），{@link Reason#NO_DOCUMENT} 是有节点但没有 {@code CONTAINS} 边
 * （说明书没入库，或正文没提到这个商品）。两者的修法不同——前者要跑一次图谱构建，
 * 后者要去传或改说明书。合并成一个「未覆盖」会让人对着错误的方向使劲。
 *
 * @param totalSpu     在售商品总数
 * @param coveredSpu   其中图上能查到至少一篇文档支持的数量
 * @param uncoveredSpu 未覆盖清单，按商品编号升序
 * @param available    图谱是否可用。false 时上面三个字段都没有意义，
 *                     调用方必须把「没查成」说出去，而不是当成「全都覆盖了」
 * @param reason       图谱不可用时的原因，可用时为 null
 */
public record ProductGraphCoverage(int totalSpu, int coveredSpu, List<Uncovered> uncoveredSpu,
                                   boolean available, String reason) {

    /**
     * 一件未覆盖的商品。
     *
     * @param spuKey 图谱节点键（{@code SPU7}）
     * @param name   商品名，供管理台直接点进编辑页
     * @param reason 未覆盖的原因
     */
    public record Uncovered(String spuKey, String name, Reason reason) {
    }

    /** 未覆盖的两种原因，见 {@link ProductGraphCoverage} 类注释 */
    public enum Reason {
        /** 图上没有这个商品节点 —— 从未构建，或在重建时被孤岛清理带走了 */
        NO_NODE,
        /** 图上有节点，但没有任何文档通过 CONTAINS 边支持它 */
        NO_DOCUMENT
    }
}
