package yumefusaka.envoymart.knowledgeservice.graph;

import yumefusaka.envoymart.agent.graph.EntityKind;
import yumefusaka.envoymart.agent.graph.GraphRelation;

/**
 * 已通过校验、并<b>锚定了原文位置</b>的三元组。
 * <p>
 * {@code quoteStart}/{@code quoteEnd} 是 {@code quote} 在该文档<b>正文</b>中的
 * UTF-16 code unit 偏移，与切片层 {@code charOffset} 同一套坐标系——
 * 前端因此可以把「这条关系来自哪句话」直接高亮到文档里，
 * 而不是只给一个文档标题让人自己去找。图的结论必须能一路点回原文，
 * 否则「知识图谱」和「模型说的」在演示上没有区别。
 *
 * @param headKind   头实体类型
 * @param headName   头实体名（节点键）
 * @param headLabel  头实体展示名。规范化前的原名，商品用它显示商品名
 * @param relation   关系
 * @param tailKind   尾实体类型
 * @param tailName   尾实体名（节点键）
 * @param tailLabel  尾实体展示名
 * @param effect     后果/说明，可为空
 * @param docId      支撑文档
 * @param chunkId    支撑切片。定位失败时为 null，引用仍可落到文档
 * @param quoteStart 引用在文档正文中的起始偏移
 * @param quoteEnd   引用在文档正文中的结束偏移（不含）
 * @param quote      原文片段
 */
public record GroundedTriple(
        EntityKind headKind,
        String headName,
        String headLabel,
        GraphRelation relation,
        EntityKind tailKind,
        String tailName,
        String tailLabel,
        String effect,
        String docId,
        String chunkId,
        int quoteStart,
        int quoteEnd,
        String quote) {

    public String display() {
        return "%s -[%s]-> %s".formatted(headName, relation, tailName);
    }
}
