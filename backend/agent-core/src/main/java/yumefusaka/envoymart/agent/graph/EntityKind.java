package yumefusaka.envoymart.agent.graph;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 图谱里实体节点的类型。
 * <p>
 * 类型是<b>封闭集合</b>而不是自由文本：抽取阶段模型返回的类型必须在词表里，
 * 不在就丢弃该条三元组。理由与 {@link GraphRelation} 同——开放类型会让「同一类东西」
 * 在图上裂成十几种叫法，最后没有任何一条查询能覆盖全。
 * <p>
 * <b>放在 agent-core 而不是 knowledge-service</b>：这份词表有两个使用者——
 * knowledge-service 拿它校验入库，ai-service 拿它拼抽取提示词。各存一份的话，
 * 改了枚举却忘了改提示词，症状是<b>模型一直返回一个词表里没有的类型名，
 * 于是整批三元组被静默丢弃</b>，而日志上只有一句「丢弃 N 条」，
 * 看不出是提示词过期了。共享一份是唯一能根治的做法。
 */
public enum EntityKind {

    /**
     * 商品。<b>键用 SPU 编号</b>（{@code SPU7}），展示名放各自的 label——
     * 说明书通篇写「本品」，靠模型从标题猜商品名迟早会猜出一个对不上的写法，
     * 而症状是图谱看着有数据、一问就查不到。
     */
    PRODUCT("商品"),
    /** 商品的组成成分。鱼油软胶囊 → 深海鱼油 */
    INGREDIENT("成分"),
    /** 营养素。成分提供的东西，如深海鱼油 → EPA */
    NUTRIENT("营养素"),
    /** 具体药物。华法林、阿司匹林 */
    DRUG("药物"),
    /** 药物类别。噻嗪类利尿剂、四环素类抗生素 —— 相互作用常以类为单位成立 */
    DRUG_CLASS("药物类别"),
    /** 人群。妊娠期哺乳期、肝肾功能不全者 */
    POPULATION("人群"),

    /**
     * 组合节点 —— 「三样东西一起」这件事本身。
     * <p>
     * <b>它不是一种实体，是图上的一个结构。</b>键以 {@code combo:} 开头，由参与组合的
     * 物质名按字典序拼接而成（如 {@code combo:铁剂|钙剂}），因此同一个组合无论从哪篇文档
     * 抽出来都落到同一个节点，条数与来源可以正确累加。
     * <p>
     * <b>为什么不给它一个「看起来更像实体」的名字</b>：它不对应现实世界里任何一种东西，
     * 用户不会想「我要查一下『组合』这个实体」。落在图上是为了让
     * {@code 物质 -COMBINED_WITH-> 药物} 这条边有地方挂 —— 而风险的<b>成立条件</b>
     * （「这几样一起」）写在头节点的键里，{@code effect} 里只写后果。
     * <p>
     * 抽取提示词与前端图例都不列它：前者的词表由 {@code GraphRelation#signature()} 生成，
     * 已经说明了「组合」这一端的形状；后者按 kind 上色，多一类就会多一个用户无法理解的图例项。
     * 前端遇到它会当作普通节点渲染，标签是参与成分的中文名拼接（见 {@code TripleValidator}）。
     */
    COMBINATION("组合");

    /** 组合节点键的前缀。写入与读取两处共用，改动只改这一处 */
    public static final String COMBINATION_PREFIX = "combo:";

    /** 是不是组合节点的类型。校验器据此放行 {@code COMBINED_WITH} 的形状 */
    public boolean isCombination() {
        return this == COMBINATION;
    }

    /**
     * 判定一个节点名是否为组合节点键。
     * <p>
     * 抽成方法而不是在各处写 {@code startsWith}：写入期形状校验、读取期展开、
     * 前端展示三处都要问同一个问题，三处各写一遍就必然有一天只改了两处。
     */
    public static boolean isCombinationKey(String name) {
        return name != null && name.startsWith(COMBINATION_PREFIX);
    }

    private final String label;

    EntityKind(String label) {
        this.label = label;
    }

    /** 中文说法。图的展示与给模型看的文本都用它，不要漏出枚举名 */
    public String label() {
        return label;
    }

    private static final Map<String, EntityKind> BY_NAME = Arrays.stream(values())
            .collect(Collectors.toMap(Enum::name, Function.identity()));

    /**
     * 按名字解析，大小写不敏感。解析不出来返回 {@code null}。
     * <p>
     * 返回 null 而不是抛异常：调用方是「校验抽取结果」这条路径，
     * 一个模型编出来的类型名应该让<b>那一条</b>三元组作废，不是让整批抽取失败。
     */
    public static EntityKind parse(String name) {
        return name == null ? null : BY_NAME.get(name.trim().toUpperCase());
    }
}
