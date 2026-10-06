package yumefusaka.envoymart.knowledgeservice.graph;

import yumefusaka.envoymart.agent.graph.GraphRelation;

/**
 * 一条待校验的候选三元组，由 ai-service 的抽取器产出、由 {@link TripleValidator} 判定去留。
 * <p>
 * 刻意不叫 Entity/Relation：这里装的是<b>还没被接受的</b>主张。
 * 模型说「维生素 D 和噻嗪类利尿剂有相互作用」并不等于图上该有这条边，
 * 中间要过词表与原文引用两道关。
 *
 * @param headKind  头实体类型名（由模型给出，解析失败即丢弃该条）
 * @param headName  头实体名，<b>同时是图上的唯一键</b>
 * @param headLabel 头实体<b>展示名</b>。为空时按 {@code headName} 显示。
 *                  商品要分开：键用 SPU 编号（改名不影响图），显示用商品名（用户看得懂）
 * @param relation  关系名
 * @param tailKind  尾实体类型名
 * @param tailName  尾实体名
 * @param tailLabel 尾实体展示名，同上
 * @param effect    关系的后果/说明。写在边属性上，见 {@link GraphRelation#INTERACTS_WITH}
 * @param quote     支撑这条关系的<b>原文片段</b>。必须能在来源文档正文里找到，否则整条丢弃
 */
public record Triple(
        String headKind,
        String headName,
        String headLabel,
        String relation,
        String tailKind,
        String tailName,
        String tailLabel,
        String effect,
        String quote,
        /**
         * 这条边由代码按人工声明的归属补出，不需要引文。见 {@code GraphTriplePayload#declared}。
         * <b>豁免范围只到「归属声明推出的商品→成分边」，不给「无引文」开通用口子。</b>
         */
        boolean declared) {

    /** 供日志与测试断言使用的紧凑表示 */
    public String display() {
        return "%s -[%s]-> %s".formatted(headName, relation, tailName);
    }
}
