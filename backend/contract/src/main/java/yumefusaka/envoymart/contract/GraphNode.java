package yumefusaka.envoymart.contract;

/**
 * 图上的一个实体，给前端画节点用。
 * <p>
 * <b>为什么放在 contract 而不是 knowledge-service</b>：图谱的读取结果有两个跨进程使用者——
 * ai-service 拿它组装回答与工具输出，网关把它转给前端画图。各定义一份的话，
 * 字段名对齐完全靠人记，而这个模块正是为这件事存在的（见 {@code package-info}）。
 *
 * @param name  节点键。<b>规范化过的</b>（去空白、转小写，见 agent-core 的 {@code EntityNames}），
 *              前端拿它做去重与连线
 * @param label 展示名，与用户看到的原文一致
 * @param kind  实体类型名（PRODUCT / INGREDIENT / NUTRIENT / DRUG / DRUG_CLASS / POPULATION），
 *              取值见 agent-core 的 {@code EntityKind}。
 *              用它的中文 {@code label()} 再显示，不要把枚举名漏给用户
 */
public record GraphNode(String name, String label, String kind) {
}
