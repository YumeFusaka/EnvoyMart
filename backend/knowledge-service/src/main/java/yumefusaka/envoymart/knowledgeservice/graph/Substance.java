package yumefusaka.envoymart.knowledgeservice.graph;

import java.util.List;

/**
 * 从用户提到的某样东西出发可达的一个实体 —— 「鱼油软胶囊 → 深海鱼油 → EPA」里的 EPA。
 * <p>
 * <b>为什么要把这一步的结果暴露出去</b>：用户只说得出商品名，而风险挂在成分上。
 * 把展开链一并返回，界面上就能显示「我们把你买的那瓶鱼油拆成了 EPA 与 DHA」，
 * 而不是让系统凭空说出一个用户从没提过的名词——后者看起来就像模型在编。
 *
 * @param rootName  出发节点的键（商品为 SPU 编号）
 * @param rootLabel 出发节点的展示名
 * @param name      本节点的键
 * @param label     本节点的展示名
 * @param kind      实体类型名，取值见 {@link EntityKind}
 * @param chain     从出发节点到本节点的展示名链，含两端，如 {@code [鱼油软胶囊, 深海鱼油, EPA]}
 */
public record Substance(String rootName, String rootLabel, String name, String label,
                        String kind, List<String> chain) {
}
