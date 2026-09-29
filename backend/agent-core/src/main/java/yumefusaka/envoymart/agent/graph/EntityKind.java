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
    POPULATION("人群");

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
