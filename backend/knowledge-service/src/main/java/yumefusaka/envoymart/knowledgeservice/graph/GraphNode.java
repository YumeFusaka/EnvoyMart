package yumefusaka.envoymart.knowledgeservice.graph;

/**
 * 图上的一个实体，给前端画节点用。
 *
 * @param name  节点键。<b>规范化过的</b>（去空白、转小写），前端拿它做去重与连线
 * @param label 展示名，与用户看到的原文一致
 * @param kind  实体类型名（PRODUCT / INGREDIENT / NUTRIENT / DRUG / DRUG_CLASS / RISK / POPULATION）。
 *              用 {@link EntityKind#label()} 换中文再显示，不要把枚举名漏给用户
 */
public record GraphNode(String name, String label, String kind) {
}
