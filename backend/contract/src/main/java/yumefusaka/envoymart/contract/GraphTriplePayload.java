package yumefusaka.envoymart.contract;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 一条候选三元组 —— <b>由 ai-service 发出，knowledge-service 消费</b>。
 * <p>
 * 「候选」是认真的：模型抽出来的东西还<b>没被接受</b>。它会过 knowledge-service 的两道闸
 * （类型与关系必须在封闭词表内、{@code quote} 必须能在文档正文里逐字找到），
 * 过不了的整条丢弃。质检放在入库侧而不是抽取侧，是因为<b>判定要用到原文</b>——
 * ai-service 手上只有自己刚发出去的那份文本，knowledge-service 手上才是库里的事实源。
 * <p>
 * 字段名与 knowledge-service 的 {@code graph.Triple} 一一对应，但<b>不共用同一个类</b>：
 * 那边是领域内类型（字段用枚举），这边是跨进程契约（字段必须是字符串）。
 * 共用会让「模型返回了一个不认识的类型名」在反序列化阶段就 500，
 * 而正确行为是丢弃那一条并继续。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GraphTriplePayload {

    /** 头实体类型名，取值见 EntityKind。不认识即丢弃该条 */
    private String headKind;
    /**
     * 头实体名。<b>图的节点键由它规范化而来</b>，所以它必须是稳定标识。
     * <p>
     * 商品用 SPU 编号（{@code SPU7}）而不是商品名：说明书全篇写「本品」，
     * 文中根本没有「鱼油软胶囊」这几个字，靠模型从标题猜商品名迟早会猜出个对不上的写法，
     * 而症状是<b>图谱看着有数据、一问就查不到</b>。
     */
    private String headName;
    /**
     * 头实体展示名，如 {@code 鱼油软胶囊}。<b>模型不产出这个字段</b>——
     * 实测它会把类型名当展示名填进来（{@code "headLabel":"NUTRIENT"}）。
     * 非商品实体由知识库侧回落到 {@code headName} 原样显示；
     * 商品的展示名由 ai-service 从商品目录<b>回填</b>，因为只有它拿得到目录。
     */
    private String headLabel;
    /** 关系名，取值见 GraphRelation */
    private String relation;
    private String tailKind;
    private String tailName;
    /** 尾实体展示名，同上：不由模型产出 */
    private String tailLabel;
    /** 关系的后果说明，如「合用可能增加出血风险」。可为空 */
    private String effect;
    /**
     * 支撑这条关系的<b>原文片段</b>，必须能在该文档正文里找到。
     * <p>
     * 这是整条链路上最关键的一个字段：它把「模型根据常识补出来的边」挡在库外。
     * 抽取提示词要求模型<b>逐字复制</b>原文，不要改写、不要拼接。
     */
    private String quote;
}
