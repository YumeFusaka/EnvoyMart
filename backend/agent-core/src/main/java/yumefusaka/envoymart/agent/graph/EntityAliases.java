package yumefusaka.envoymart.agent.graph;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 别名表：把同一个实体的不同写法归到同一个<b>规范名</b>上。
 * <p>
 * <b>为什么必须有它，而不是靠 {@link EntityNames} 多剥几层</b>：规范化只处理
 * 「同一个词的不同拼写」（空白、大小写），处理不了「同一个东西的不同叫法」。
 * 后者是医学判断，不是字符串处理——{@link EntityNames} 的类注释已经写明这一点，
 * 并把这件事留给「人工维护的别名表」。本类就是那张表。
 * <p>
 * <b>不做会怎样（实测）</b>：同一个东西在不同文档里被抽成了不同节点，
 * 图的失败是<b>静默的</b>——它不报错，只是让多跳路径在中间断开、查询返回空。
 * 实测到的三处：
 * <ul>
 *   <li>{@code KB-0009} 写「富马酸亚铁」、{@code KB-0010} 写「铁剂」，两条边各自成立但接不上，
 *       于是「孕期复合营养包 + 左旋多巴」这个查询<b>查不到</b>——而这条跨文档多跳
 *       恰恰是这张图存在的理由；</li>
 *   <li>{@code 强心苷类} 与 {@code 强心苷类药物} 是同一类药物，却是两个节点、
 *       连 kind 都不一致（{@code DRUG_CLASS} / {@code DRUG}），同一条相互作用被记了两遍；</li>
 *   <li>{@code KB-0010} 有一句话让抽取器生成了「鱼油中的 EPA 与 DHA」这个节点——
 *       那是个句子片段，不是实体，还与「深海鱼油」重复了同两条边。</li>
 * </ul>
 * <p>
 * <b>合并的判据是「这个区分会不会改变图要得出的结论」，不是「它们是不是同一个东西」。</b>
 * 这张图的用途是回答「这几样能不能一起吃」，所以：{@code 富马酸亚铁} 并进 {@code 铁剂}——
 * 铁盐的具体形式不改变它与左旋多巴的相互作用结论；而 {@code 维生素 D3} <b>不</b>并进
 * {@code 维生素 D}，尽管 D3 是 D 的一种形式——见文末「有意不合并」。
 * <p>
 * <b>放在 agent-core 而不是 knowledge-service</b>：这张表有两个使用者，且两边必须一致——
 * knowledge-service 在<b>写入</b>时用归并节点（否则同一个东西仍旧是两处），
 * ai-service 与 knowledge-service 的读取路径用归并把用户输入解析到规范节点
 * （否则写进去是 A、查出来按 B 查，等于没合并）。两边各存一份的话，
 * 改了一边忘了另一边，症状与「表不存在」完全一样。
 * <p>
 * <b>表是手写的，这是刻意的。</b>别名是领域知识，写错一条就是把两种药当成一种——
 * 这种错误的代价远高于多写几行。模型可以<b>建议</b>别名，但不能自己决定。
 */
public final class EntityAliases {

    private EntityAliases() {
    }

    /**
     * 一个规范实体：<b>名字与类型一起</b>。
     * <p>
     * {@code name} <b>已规范化</b>（见 {@link EntityNames#normalize}）——它直接就是图的节点键，
     * 调用方拿到就能用，不必也不能再归一一次。规范化在这里做一次而不是留给每个调用方，
     * 是因为「注册时按原始写法当键、查询时按规范化写法找」这种错配不会报错，
     * 只会让表<b>静默失配</b>，症状与「表里没这条」一模一样。
     * <p>
     * 类型也归规范，因为实测中同一个东西的类型在不同文档里会不一致——
     * {@code 强心苷类药物} 被抽成 {@code DRUG}、{@code 强心苷类} 被抽成 {@code DRUG_CLASS}。
     * 只统一名字不统一类型的话，节点上留哪一个 kind 取决于哪篇文档后写入，
     * 而 {@code kind} 会参与写入期的形状校验（{@code relation.acceptsHead/Tail}）。
     */
    public record Canonical(String name, EntityKind kind) {
    }

    /**
     * 变体（<b>已规范化</b>，即 {@link EntityNames#normalize} 的产物）→ 规范。
     * <p>
     * 键必须是规范化后的写法：查询侧与写入侧都是拿规范化后的名字来查这张表，
     * 键留着原始写法（带空格、带大写）就永远命中不了。
     * <p>
     * 按规范名长度排一下没有意义，用 {@link LinkedHashMap} 只是为了让
     * 同一份表在两次运行里顺序一致，便于日志与测试比对。
     */
    private static final Map<String, Canonical> BY_VARIANT = new LinkedHashMap<>();

    /** 规范名 → 它名下的全部变体（含规范名自己）。给读取侧的词典匹配用 */
    private static final Map<String, List<String>> VARIANTS = new LinkedHashMap<>();

    private static void alias(String canonical, EntityKind kind, String... variants) {
        // 规范名在注册时归一，之后它就是节点键。VARIANTS 也用归一后的名字做键——
        // 查它的调用方手上拿的是节点键，本来就是规范化的
        String key = EntityNames.normalize(canonical);
        Canonical target = new Canonical(key, kind);
        BY_VARIANT.put(key, target);
        VARIANTS.computeIfAbsent(key, k -> new ArrayList<>()).add(key);
        for (String v : variants) {
            BY_VARIANT.put(EntityNames.normalize(v), target);
            VARIANTS.get(key).add(EntityNames.normalize(v));
        }
    }

    static {
        // 同一类药的两个叫法，且类型被抽得不一致。并入 DRUG_CLASS——
        // 相互作用本来就常以类为单位成立（KB-0005 的原文也是「强心苷类药物」这一整类）
        alias("强心苷类", EntityKind.DRUG_CLASS, "强心苷类药物", "强心苷");

        // 铁盐的具体形式不改变相互作用结论，并入通用的「铁剂」。
        // 这一条同时修好了那条断掉的多跳：孕期复合营养包 → 铁剂 → 左旋多巴
        alias("铁剂", EntityKind.INGREDIENT, "富马酸亚铁", "硫酸亚铁", "琥珀酸亚铁", "铁补充剂");

        // 句子片段，不是实体。它重复了「深海鱼油」已有的两条边
        alias("深海鱼油", EntityKind.INGREDIENT, "鱼油中的 EPA 与 DHA", "鱼油");

        // 类型纠正：抗生素是药物类别不是具体药物。规范名不变，只把它拉回 DRUG_CLASS，
        // 与同样是类别的「喹诺酮类抗生素」「噻嗪类利尿剂」保持一致
        alias("抗生素", EntityKind.DRUG_CLASS);
    }

    /**
     * 把一个写法解析到规范实体。
     * <p>
     * <b>不接受调用方传类型</b>：类型与名字一起归规范，所以调用方（模型抽取或用户输入）
     * 给的那个类型在命中之后一律作废——实测模型会把同一类药物一会儿标 {@code DRUG}
     * 一会儿标 {@code DRUG_CLASS}，正是它不可信才需要这张表。留一个「类型可被覆盖」的
     * 参数只会让人以为它是生效的。
     *
     * @param normalizedName <b>已规范化</b>的名字（{@link EntityNames#normalize} 的产物）。
     *                       传原始写法（带空格、带大写）永远命中不了——表里的键都是规范化后的
     * @return 规范实体；<b>表里没有这个写法时返回 {@code null}</b>，调用方沿用原名原类型
     */
    public static Canonical resolve(String normalizedName) {
        if (normalizedName == null || normalizedName.isEmpty()) {
            return null;
        }
        return BY_VARIANT.get(normalizedName);
    }

    /**
     * 一个规范名名下的全部写法（<b>已规范化</b>，含规范名自己）。
     * <p>
     * 给读取侧的<b>词典匹配</b>用：用户的问题是整句话（「我在吃富马酸亚铁，能吃这个吗」），
     * 要拿图上的实体名去做子串命中。只拿节点的 {@code name} 与 {@code label} 当词典的话，
     * 「富马酸亚铁」这个词在图上已经不存在了（写入时被并成了「铁剂」），
     * 于是这句话<b>一个实体都链接不到</b>——合并掉了节点却留着旧写法在用户嘴里。
     *
     * @return 该规范名名下的写法；不是规范名时返回只含它自己的单元素列表
     */
    public static List<String> variantsOf(String canonicalName) {
        List<String> hit = VARIANTS.get(canonicalName);
        return hit == null ? List.of(canonicalName) : List.copyOf(hit);
    }

    /*
     * ── 有意不合并的一对：维生素 D3 与 维生素 D ──
     *
     * <p>
     * 表面上它们是「同一个东西的两个叫法」——D3 是维生素 D 的一种形式，
     * 而且实测中它们确实各带一条指向噻嗪类利尿剂的相互作用，看起来是重复。
     * <b>但不合并</b>，理由有两条：
     * <ol>
     *   <li><b>医学上不成立</b>。维生素 D 是一个族（D2、D3 都是它的形式），
     *       把 D3 并进 D 等于宣称「补充 D3 与补充 D 是一回事」——本项目的
     *       {@code KB-0016} 里，两者的推荐摄入量与耐受上限并不通用。
     *       别名表的合并是不可逆的语义断言，这一条断言是错的。</li>
     *   <li><b>功能上不需要</b>。两条路径各自都是通的：
     *       查「维生素 D3 软胶囊」走的是 {@code 软胶囊 → 胆钙化醇 → 维生素 D3 → 噻嗪类利尿剂}，
     *       查「维生素 D」直接命中。合并只省掉一条重复的边，
     *       而代价是抹掉一个真实的区别——<b>不合并只是冗余，合并错了是错误</b>。</li>
     * </ol>
     * 什么时候该重新考虑：如果语料里出现「维生素 D2」并带一条与 D3 不同的相互作用，
     * 那时需要的是一个 {@code IS_A} 上下位关系让遍历可以向上跳，而不是把所有形式压成一个节点。
     */

    /*
     * ── 另一类有意不合并：人群节点，它缺的是层次不是别名 ──
     *
     * <p>
     * 实测图上共存三个人群节点，各自带着不同的边，互不连通：
     * <ul>
     *   <li>{@code 孕期} ← 维生素a（KB-0010：孕期过量有致畸风险）</li>
     *   <li>{@code 妊娠期与哺乳期女性} ← 维生素d3（KB-0005）</li>
     *   <li>{@code 备孕期与孕早期女性} ← 叶酸（KB-0009）</li>
     * </ul>
     * 用户问「孕期能不能吃维生素 D3」时只解析到 {@code 孕期}，看不到维生素 D3 那条——
     * 与「富马酸亚铁」那次一样是一条断掉的路。
     * <p>
     * <b>但它们不该用这张表合并</b>：三者是三个真正不同的人群，
     * 备孕期在人怀孕之前、哺乳期在人生产之后。压成一个节点，
     * 「备孕期能吃叶酸吗」与「哺乳期能吃维生素 A 吗」会同时返回全部三条警示——
     * 把漏报换成了误报，而误报在这里的代价是让人平白停掉该补的东西。
     * <p>
     * 它缺的是<b>上下位关系</b>：{@code 备孕期与孕早期女性} 与 {@code 妊娠期与哺乳期女性}
     * 各有共同的父节点（孕期女性），查询时沿父节点向上跳一跳即可。
     * 那要新增一种关系，而新增关系就要动抽取词表与提示词，不是这次改动能顺带做掉的。
     * <b>现状如实记在这里：这是已知边界，不是没看见。</b>
     */
}
