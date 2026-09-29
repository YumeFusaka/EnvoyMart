package yumefusaka.envoymart.agent.graph;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 图谱的关系词表。
 * <p>
 * <b>为什么是封闭词表而不是让模型自由发挥</b>：开放式关系名在图上会迅速退化。
 * 「增加出血风险」「加大出血可能」「提升出血倾向」是同一个意思的三种写法，
 * 每一条查询都只能捞到其中一部分，而图上看起来「有数据」——查询静默漏结果比报错难查得多。
 * <p>
 * <b>为什么只有四个</b>：只收语料里真有原文支撑的关系。替代品、搭配推荐、类目归属
 * 这类关系在平台别处（商品库、推荐位）已经有了，语料里也没有任何一句话说过它们；
 * 放进来只会让模型照着关系的名字把答案编出来。图的价值在<b>文本检索不到的多跳结论</b>，
 * 不在于把所有知识都复制一份——摄入上限就属于「一句话写在某一片里」的那种，
 * 单跳检索本来就能捞到，搬进图只是增加schema面积。
 */
public enum GraphRelation {

    /** 商品 → 成分。「用户买的那个东西到底含什么」的唯一入口 */
    CONTAINS(EnumSet.of(EntityKind.PRODUCT), EnumSet.of(EntityKind.INGREDIENT)),

    /** 成分 → 营养素。胆钙化醇提供维生素 D */
    PROVIDES(EnumSet.of(EntityKind.INGREDIENT), EnumSet.of(EntityKind.NUTRIENT)),

    /**
     * 营养素/成分 → 药物或药物类别。
     * <p>
     * 相互作用的<b>后果写在边的 effect 属性上，不拆成 RISK 节点</b>：
     * 「维生素 D + 噻嗪类利尿剂 → 高钙血症风险」里的风险是<b>组合导致的</b>，
     * 单独一条「维生素 D → 高钙血症」边是错的——正常剂量下它不会造成这个结果。
     * 拆节点恰好把「谁和谁一起才有事」这个最关键的信息丢掉。
     */
    INTERACTS_WITH(EnumSet.of(EntityKind.NUTRIENT, EntityKind.INGREDIENT),
            EnumSet.of(EntityKind.DRUG, EntityKind.DRUG_CLASS)),

    /** 营养素/成分 → 人群。需要先咨询医师的人群 */
    CAUTION_FOR(EnumSet.of(EntityKind.NUTRIENT, EntityKind.INGREDIENT),
            EnumSet.of(EntityKind.POPULATION));

    private final Set<EntityKind> heads;
    private final Set<EntityKind> tails;

    GraphRelation(Set<EntityKind> heads, Set<EntityKind> tails) {
        this.heads = heads;
        this.tails = tails;
    }

    public boolean acceptsHead(EntityKind kind) {
        return kind != null && heads.contains(kind);
    }

    public boolean acceptsTail(EntityKind kind) {
        return kind != null && tails.contains(kind);
    }

    private static final Map<String, GraphRelation> BY_NAME = Arrays.stream(values())
            .collect(Collectors.toMap(Enum::name, Function.identity()));

    /** 供抽取提示词使用：全部关系名 */
    public static List<String> names() {
        return Arrays.stream(values()).map(Enum::name).toList();
    }

    /**
     * 关系两端的形状，形如 {@code 成分/营养素 -> 药物/药物类别}。给抽取提示词用。
     * <p>
     * <b>为什么提示词必须由它生成，而不是手写一遍关系名</b>：手写的那版只列了四个名字，
     * 模型于是不知道每条关系对两端类型有约束，「本品与华法林合用可能增加出血风险」
     * 被抽成了 {@code PRODUCT -> DRUG}。校验器判丢是对的，但模型没有任何办法知道错在哪——
     * 它看到的信息里根本不含这条约束。实测 KB-0005 七抽七丢、KB-0006 九抽丢七，
     * 丢弃原因全部是「关系不接受这对类型」。
     * <p>
     * 从枚举生成之后，改约束必然改到提示词，两者不可能再漂移。
     */
    public String signature() {
        return labels(heads) + " -> " + labels(tails);
    }

    private static String labels(Set<EntityKind> kinds) {
        // EnumSet 保持声明顺序，所以同一份代码每次生成的提示词是逐字相同的
        return kinds.stream().map(EntityKind::label).collect(Collectors.joining("/"));
    }

    /** 按名字解析，大小写不敏感。解析不出来返回 {@code null} */
    public static GraphRelation parse(String name) {
        return name == null ? null : BY_NAME.get(name.trim().toUpperCase());
    }
}
