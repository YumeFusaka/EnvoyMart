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
        // 补铁剂的全部盐形式都并到这一条上：**同一条别名只能出现在这一个列表里**，
        // 在别处再 alias("铁剂", ...) 一次会让 variantsOf 回重复项（alias 对 VARIANTS 是追加写）
        alias("铁剂", EntityKind.INGREDIENT, "富马酸亚铁", "硫酸亚铁", "琥珀酸亚铁",
                "乳酸亚铁", "葡萄糖酸亚铁", "铁补充剂", "口服铁剂");

        // 句子片段，不是实体。它重复了「深海鱼油」已有的两条边
        alias("深海鱼油", EntityKind.INGREDIENT, "鱼油中的 EPA 与 DHA", "鱼油");

        // 类型纠正：抗生素是药物类别不是具体药物。规范名不变，只把它拉回 DRUG_CLASS，
        // 与同样是类别的「喹诺酮类抗生素」「噻嗪类利尿剂」保持一致
        alias("抗生素", EntityKind.DRUG_CLASS);

        // ── 营养素与成分：用户嘴里的写法 vs 说明书里的写法 ──
        //
        // 下面这一批覆盖的是「同一种东西在语料里叫学名、在用户嘴里叫俗名」。
        // 不合并的症状与「富马酸亚铁 / 铁剂」那次完全一样：用户问「维 C 和这个能一起吃吗」，
        // 而图上只有「抗坏血酸」，「维c」这个词在词典匹配时一个实体都链接不到——
        // 图不报错，只是让这次多跳直接返回空。
        //
        // 判据仍然是「这个区分会不会改变图要得出的结论」：维 C 的三种叫法不改变它与
        // 铁剂、抗凝药的相互作用结论，所以归一；而「维生素 D3 与维生素 D 不合并」
        // 的理由见文末。

        // 维生素 C：抗坏血酸是学名，「维C」「维生素C」「VC」都是常见写法
        // 大小写变体不必逐个列出：注册时统一 normalize（去空白 + 转小写），
        // 「维C」与「维c」归一到同一个键，重复写只会让 variantsOf 里出现两个一样的项
        alias("维生素C", EntityKind.NUTRIENT, "维C", "VC", "抗坏血酸");

        // 维生素 B12：钴胺素是学名
        alias("维生素B12", EntityKind.NUTRIENT, "钴胺素", "氰钴胺", "VB12");

        // 叶酸：维生素 B9 是同一个东西的另一个编号
        alias("叶酸", EntityKind.NUTRIENT, "维生素B9", "蝶酰谷氨酸");

        // 维生素 A：视黄醇是学名
        alias("维生素A", EntityKind.NUTRIENT, "视黄醇", "维A");

        // 维生素 E：生育酚是学名
        alias("维生素E", EntityKind.NUTRIENT, "生育酚", "维E");

        // 维生素 B6：吡哆醇是学名
        alias("维生素B6", EntityKind.NUTRIENT, "吡哆醇", "吡哆辛");

        // 维生素 B1 / B2：硫胺素、核黄素是学名
        alias("维生素B1", EntityKind.NUTRIENT, "硫胺素");
        alias("维生素B2", EntityKind.NUTRIENT, "核黄素");

        // 钙：钙是元素，碳酸钙 / 柠檬酸钙是它最常见的两种化合物形式。
        // 两者在吸收是否依赖胃酸上有区别（见 KB-0028），但**与药物的相互作用结论相同**
        // （都影响四环素、喹诺酮、左甲状腺素的吸收，都增加噻嗪类的高钙风险），
        // 而这张图要回答的正是后者，所以并入「钙」
        alias("钙", EntityKind.NUTRIENT, "钙剂", "钙元素", "碳酸钙", "柠檬酸钙");

        // DHA：藻油 DHA、二十二碳六烯酸是同一种东西
        alias("DHA", EntityKind.NUTRIENT, "藻油DHA", "二十二碳六烯酸");

        // EPA：与 DHA 同属深海鱼油提供的 omega-3，但相互作用结论不同（EPA 与出血风险的
        // 关联证据更直接），因此**不并入 DHA**，只把拼写变体归一
        alias("EPA", EntityKind.NUTRIENT, "二十碳五烯酸");

        // 锌与硒：复合配方里常一起出现，但相互作用（锌拮抗铜吸收）只对锌成立，不合并
        alias("锌", EntityKind.NUTRIENT, "锌剂", "锌元素");
        alias("硒", EntityKind.NUTRIENT, "硒元素", "硒剂");
        alias("铁", EntityKind.NUTRIENT, "铁元素");

        // 「铁剂」这一组在文首已经注册。**不能在这里再注册一次**：alias() 对 VARIANTS 是
        // 追加写而不是覆盖，第二次注册会让同一个写法在 variantsOf 里出现两遍。
        // 要补别名就补到文首那一条上，别新开一条 —— 这是表自身唯一的坑

        // 铜：长期大剂量补锌影响铜的吸收，这条边挂在铜上
        alias("铜", EntityKind.NUTRIENT, "铜元素");

        // 镁：与钙、铁同样存在吸收竞争
        alias("镁", EntityKind.NUTRIENT, "镁元素");

        // 辅酶 Q10：泛醌是学名，CoQ10 是常见英文写法
        alias("辅酶Q10", EntityKind.INGREDIENT, "泛醌", "CoQ10", "泛癸利酮");

        // 维生素 K2：甲萘醌-7 是学名。**不并入「维生素 K」**——K2 与 K1 的生理作用
        // 与抗凝拮抗强度不同，而语料里只有 K2
        alias("维生素K2", EntityKind.NUTRIENT, "甲萘醌", "MK-7");

        // 「深海鱼油」这一组在文首已经注册（含「鱼油」与那个句子片段），这里不再重复登记，
        // 只补它的营养素类别写法：欧米伽 3 / omega-3 说的是它提供的脂肪酸，不是鱼油本身，
        // 因此单独作为一个规范名，靠 PROVIDES 这条关系与鱼油相连
        alias("欧米伽3", EntityKind.NUTRIENT, "欧米伽-3", "Omega-3", "omega3", "ω-3", "n-3脂肪酸");

        // 叶黄素与叶黄素酯：叶黄素酯在体内水解为叶黄素，二者在语料里是同一条链路
        alias("叶黄素", EntityKind.INGREDIENT, "叶黄素酯");
        alias("玉米黄质", EntityKind.INGREDIENT, "玉米黄素");

        // 氨基葡萄糖：氨糖是口语写法
        alias("氨基葡萄糖", EntityKind.INGREDIENT, "氨糖", "葡萄糖胺");
        alias("硫酸软骨素", EntityKind.INGREDIENT, "软骨素");

        // 胶原蛋白肽：小分子肽、水解胶原蛋白是同一种东西
        alias("胶原蛋白肽", EntityKind.INGREDIENT, "胶原蛋白", "水解胶原蛋白", "小分子胶原蛋白肽");

        // 膳食纤维：菊粉、抗性糊精都是水溶性膳食纤维的具体形式，
        // 「与铁剂/左甲状腺素间隔 2 小时」这条结论对三者一致，故归一
        alias("膳食纤维", EntityKind.INGREDIENT, "水溶性膳食纤维", "菊粉", "抗性糊精", "纤维素");

        // 共轭亚油酸：CLA 是常见英文缩写
        alias("共轭亚油酸", EntityKind.INGREDIENT, "CLA");

        // 白芸豆提取物：语料与商品名两处写法
        alias("白芸豆提取物", EntityKind.INGREDIENT, "白芸豆");

        // 二十二碳六烯酸已在 DHA 那条登记；这里补「藻油」单独成词的写法
        alias("藻油", EntityKind.INGREDIENT, "藻油DHA软胶囊");

        // 乳清蛋白 / 豌豆蛋白 / 糙米蛋白：蛋白来源，各自是成分
        alias("乳清蛋白", EntityKind.INGREDIENT, "乳清蛋白粉");
        alias("豌豆蛋白", EntityKind.INGREDIENT, "豌豆蛋白粉");
        alias("糙米蛋白", EntityKind.INGREDIENT, "糙米蛋白粉");

        // ── 药物与药物类别 ──

        // 华法林：香豆素类抗凝药的代表，语料里偶尔写成「华法林钠」
        alias("华法林", EntityKind.DRUG, "华法林钠");
        // 阿司匹林：抗血小板药的常见写法
        alias("阿司匹林", EntityKind.DRUG, "乙酰水杨酸", "拜阿司匹灵");
        // 左甲状腺素：甲状腺功能减退的替代治疗药，语料里带钠盐后缀
        alias("左甲状腺素", EntityKind.DRUG, "左甲状腺素钠", "优甲乐", "L-T4");
        // 左旋多巴：帕金森病治疗药
        alias("左旋多巴", EntityKind.DRUG, "L-DOPA", "levodopa");
        // 奥利司他：脂肪酶抑制剂，影响脂溶性营养素吸收
        alias("奥利司他", EntityKind.DRUG, "赛尼可");
        // 考来烯胺：胆汁酸螯合剂，影响脂溶性维生素与叶黄素吸收
        alias("考来烯胺", EntityKind.DRUG, "消胆胺");
        // 氟伏沙明：影响褪黑素代谢的 SSRI
        alias("氟伏沙明", EntityKind.DRUG, "兰释");
        // 地高辛：强心苷类代表药物
        alias("地高辛", EntityKind.DRUG);

        // 四环素类抗生素 / 喹诺酮类抗生素：两端的类型都统一到 DRUG_CLASS——
        // 与钙、铁、锌的相互作用本来就以整类为单位成立，写成具体药名反而漏
        alias("四环素类抗生素", EntityKind.DRUG_CLASS, "四环素类", "四环素");
        alias("喹诺酮类抗生素", EntityKind.DRUG_CLASS, "喹诺酮类", "喹诺酮");
        alias("噻嗪类利尿剂", EntityKind.DRUG_CLASS, "噻嗪类", "噻嗪类利尿药");
        alias("苯二氮䓬类", EntityKind.DRUG_CLASS, "苯二氮卓类", "苯二氮䓬类药物");
        alias("镇静催眠药", EntityKind.DRUG_CLASS, "镇静催眠类药物", "安眠药");
        alias("抗凝药物", EntityKind.DRUG_CLASS, "抗凝药", "口服抗凝药");
        alias("抗血小板药物", EntityKind.DRUG_CLASS, "抗血小板药");
        alias("降糖药物", EntityKind.DRUG_CLASS, "降糖药", "口服降糖药");
        alias("降脂药物", EntityKind.DRUG_CLASS, "降脂药", "他汀类药物", "他汀类");

        // ── 人群 ──
        //
        // 孕期相关的三个人群见文末「有意不合并：人群节点，它缺的是层次不是别名」，
        // 这里只补同一人群内部的写法变体（空格、全半角、常见误写），不做跨人群合并。
        alias("妊娠期与哺乳期女性", EntityKind.POPULATION,
                "孕期与哺乳期女性", "孕妇及哺乳期女性", "妊娠期哺乳期女性", "哺乳期女性", "哺乳期妇女");
        alias("备孕期与孕早期女性", EntityKind.POPULATION, "备孕期女性", "孕早期女性");
        alias("孕期女性", EntityKind.POPULATION, "孕妇", "妊娠期女性", "孕期");
        alias("儿童", EntityKind.POPULATION, "小儿", "未成年人");
        alias("婴幼儿", EntityKind.POPULATION, "婴儿", "幼儿");
        alias("中老年人群", EntityKind.POPULATION, "中老年人", "老年人群", "老年人");
        alias("素食人群", EntityKind.POPULATION, "素食者");
        alias("乳糖不耐受人群", EntityKind.POPULATION, "乳糖不耐受者");
        alias("肾功能不全者", EntityKind.POPULATION, "肾功能不全患者");
        alias("肝功能不全者", EntityKind.POPULATION, "肝功能不全患者");
        alias("高钙血症患者", EntityKind.POPULATION, "高钙血症");
        alias("肾结石患者", EntityKind.POPULATION, "肾结石");
        alias("出血性疾病患者", EntityKind.POPULATION, "出血性疾病");
        alias("地中海贫血患者", EntityKind.POPULATION, "地中海贫血");

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
