package yumefusaka.envoymart.contract;

import java.util.List;

/**
 * 「我手上这几样，能不能一起吃」的答案。
 * <p>
 * <b>按用户提到的每一样东西分组</b>，而不是把找到的风险平铺成一张表。原因是这个查询
 * 有一半的价值在<b>阴性结果</b>上：用户问「这三样能不能一起吃」，正确答案往往是
 * 「鱼油和华法林有冲突，另外两样查过了、没有已知相互作用」。平铺的列表给不出后半句——
 * 它只会显示鱼油那一条，用户无法判断另外两样是「安全」还是「系统没查」。
 * <p>
 * 因此每一项都必须带 {@code found}：图谱里根本没有这个东西时，答案必须是
 * 「没有收录，请咨询医师」，而不是「未发现风险」。这两句话在药品营养场景下是
 * 完全相反的结论，而它们的界面表现都是「没有风险条目」。
 * <p>
 * <b>跨进程的读法只有一种</b>：{@code available=false} 时 {@code items} 必为空，
 * 调用方（ai-service 的工具、前端）必须据此说「暂时查不了」，
 * <b>绝不能</b>把空列表渲染成「没有冲突」——那正是图谱不可用时最容易犯、
 * 后果最重的错。
 *
 * @param available 图谱是否可用。为 false 时 {@code items} 必为空
 * @param note      不可用时的原因说明，可用时为 null
 */
public record InteractionReport(boolean available, String note, List<Item> items) {

    /**
     * @param input      调用方传进来的原始标识（商品为 SPU 编号）
     * @param label      它的展示名。图谱里没有时回落为 {@code input} 本身
     * @param found      图谱里是否有这个节点。false 表示<b>没有收录</b>，不等于安全
     * @param substances 由它展开出的活性物质，含它自己
     * @param risks      这些物质命中的相互作用 / 人群禁忌。空列表 + found=true 才是「未发现风险」
     */
    public record Item(String input, String label, boolean found,
                       List<Substance> substances, List<GraphEdge> risks) {
    }
}
