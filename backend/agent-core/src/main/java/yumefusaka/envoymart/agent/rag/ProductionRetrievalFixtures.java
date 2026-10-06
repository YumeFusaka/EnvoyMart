package yumefusaka.envoymart.agent.rag;

import java.util.List;

/**
 * 检索评测夹具（生产语料版）—— 标注对象是<b>线上真正在跑的那 47 篇文档</b>，不是评测专用语料。
 * <p>
 * <b>为什么另起一份，而不是改用 {@link EvalFixtures}。</b>那一份是 90 篇短文档（每篇恰好一片），
 * 检索器也刻意用「关键词路 + 伪随机向量」以便逐位复现、能进 CI。它测的是<b>下限回归</b>，
 * 价值在确定性。而报告页真正要回答的是另一个问题：<b>用户此刻用的那条完整链路有多好</b>——
 * 真实 embedding、知识图谱第三路、RRF、重排，跑在真实语料上。两份夹具口径不同、不可互推，
 * 因此分开维护、分开陈述，谁也不冒充谁。
 * <p>
 * <b>标注逐条对到真实文档编号</b>（{@code KB-00xx}）。分层沿用同一套：
 * <ul>
 *   <li>{@link Stratum#TEXTUAL}（字面档）：问句里的关键词与文档用词基本一致；</li>
 *   <li>{@link Stratum#PARAPHRASE}（语义档）：口语化、换说法，关键词路容易漏、要靠向量路与图谱路；</li>
 *   <li>{@link Stratum#HARD}（难题档）：结论要跨两篇以上文档，或答案分散在同一篇的多个章节。</li>
 * </ul>
 * 三条 {@code relevantDocIds} 里都可能有第二条——那不是噪声，是「这样问也合理」的第二种正确召回。
 */
public final class ProductionRetrievalFixtures {

    public enum Stratum {
        TEXTUAL("TEXTUAL", "字面档", "问句用词与文档基本一致"),
        PARAPHRASE("PARAPHRASE", "语义档", "口语化换说法，靠语义与图谱"),
        HARD("HARD", "难题档", "跨文档或跨章节才能答全");

        private final String key;
        private final String label;
        private final String note;

        Stratum(String key, String label, String note) {
            this.key = key;
            this.label = label;
            this.note = note;
        }

        public String key() {
            return key;
        }

        public String label() {
            return label;
        }

        public String note() {
            return note;
        }
    }

    /** 查询 + 该查询的正确文档编号集合 */
    public record Case(String query, Stratum stratum, List<String> relevantDocIds) {
    }

    private ProductionRetrievalFixtures() {
    }

    public static List<Case> allCases() {
        List<Case> cases = new java.util.ArrayList<>();
        cases.addAll(TEXTUAL);
        cases.addAll(PARAPHRASE);
        cases.addAll(HARD);
        return cases;
    }

    public static List<Case> of(Stratum stratum) {
        return switch (stratum) {
            case TEXTUAL -> TEXTUAL;
            case PARAPHRASE -> PARAPHRASE;
            case HARD -> HARD;
        };
    }

    private static final List<Case> TEXTUAL = List.of(
            new Case("维生素 D3 软胶囊每天吃几粒", Stratum.TEXTUAL, List.of("KB-0005")),
            new Case("深海鱼油软胶囊的用法用量", Stratum.TEXTUAL, List.of("KB-0006")),
            new Case("乳清蛋白粉怎么冲泡", Stratum.TEXTUAL, List.of("KB-0007")),
            new Case("益生菌冻干粉冲服水温要求", Stratum.TEXTUAL, List.of("KB-0008")),
            new Case("退换货政策总则", Stratum.TEXTUAL, List.of("KB-0001")),
            new Case("换货退货运费谁承担", Stratum.TEXTUAL, List.of("KB-0002")),
            new Case("物流配送与签收规则", Stratum.TEXTUAL, List.of("KB-0012")),
            new Case("支付方式与发票开具规则", Stratum.TEXTUAL, List.of("KB-0013")),
            new Case("优惠券使用规则", Stratum.TEXTUAL, List.of("KB-0014")),
            new Case("会员等级与权益", Stratum.TEXTUAL, List.of("KB-0015")),
            new Case("叶黄素酯软胶囊说明书", Stratum.TEXTUAL, List.of("KB-0018")),
            new Case("氨糖软骨素钙片说明书", Stratum.TEXTUAL, List.of("KB-0019")),
            new Case("辅酶 Q10 软胶囊说明书", Stratum.TEXTUAL, List.of("KB-0025")),
            new Case("胶原蛋白肽粉说明书", Stratum.TEXTUAL, List.of("KB-0031")),
            new Case("维生素 AD 滴剂说明书", Stratum.TEXTUAL, List.of("KB-0043")),
            new Case("褪黑素片说明书", Stratum.TEXTUAL, List.of("KB-0046"))
    );

    private static final List<Case> PARAPHRASE = List.of(
            new Case("骨头脆想补钙吃啥", Stratum.PARAPHRASE, List.of("KB-0027", "KB-0047")),
            new Case("记性差、晚上老睡不着能吃点什么", Stratum.PARAPHRASE, List.of("KB-0017", "KB-0046")),
            new Case("想增强抵抗力买哪种维生素", Stratum.PARAPHRASE, List.of("KB-0020", "KB-0037")),
            new Case("血脂高能不能吃鱼油", Stratum.PARAPHRASE, List.of("KB-0006", "KB-0041")),
            new Case("怀孕了要补点啥营养", Stratum.PARAPHRASE, List.of("KB-0029", "KB-0035")),
            new Case("天天对着电脑眼睛累吃啥好", Stratum.PARAPHRASE, List.of("KB-0018")),
            new Case("腿脚不灵便想护关节", Stratum.PARAPHRASE, List.of("KB-0019", "KB-0047")),
            new Case("孩子挑食要不要补维生素", Stratum.PARAPHRASE, List.of("KB-0032", "KB-0044")),
            new Case("胃不好补钙选哪种", Stratum.PARAPHRASE, List.of("KB-0028")),
            new Case("想减肥又怕饿", Stratum.PARAPHRASE, List.of("KB-0021", "KB-0024")),
            new Case("买的药和保健品能不能一起吃", Stratum.PARAPHRASE, List.of("KB-0010")),
            new Case("补剂一天最多吃多少", Stratum.PARAPHRASE, List.of("KB-0010", "KB-0016")),
            new Case("东西坏了想退怎么弄", Stratum.PARAPHRASE, List.of("KB-0001", "KB-0004")),
            new Case("快递一直没到怎么办", Stratum.PARAPHRASE, List.of("KB-0012")),
            new Case("下单了想开发票", Stratum.PARAPHRASE, List.of("KB-0013")),
            new Case("活动打折和优惠券能一起用吗", Stratum.PARAPHRASE, List.of("KB-0014", "KB-0015"))
    );

    private static final List<Case> HARD = List.of(
            new Case("儿童钙片和维生素 D 一起吃会不会超量", Stratum.HARD, List.of("KB-0034", "KB-0016")),
            new Case("孕期补钙和补铁能同时吃吗", Stratum.HARD, List.of("KB-0033", "KB-0009")),
            new Case("吃华法林期间还能补鱼油和维生素 K2 吗", Stratum.HARD, List.of("KB-0010", "KB-0026")),
            new Case("褪黑素和 γ-氨基丁酸一起吃安全吗", Stratum.HARD, List.of("KB-0017", "KB-0046")),
            new Case("铁剂和钙片要错开多久吃", Stratum.HARD, List.of("KB-0009", "KB-0010")),
            new Case("孕妇吃鱼油补 DHA 和吃维生素 AD 会不会冲突", Stratum.HARD, List.of("KB-0035", "KB-0043")),
            new Case("中老年人补钙护关节该买哪几样", Stratum.HARD, List.of("KB-0047", "KB-0019")),
            new Case("食品类商品拆封后还能退吗", Stratum.HARD, List.of("KB-0004", "KB-0001")),
            new Case("换货的运费和退货的运费一样吗", Stratum.HARD, List.of("KB-0002", "KB-0001")),
            new Case("特殊医学用途配方食品的售后有什么特别规定", Stratum.HARD, List.of("KB-0003", "KB-0004")),
            new Case("会员权益和优惠券叠加的规则", Stratum.HARD, List.of("KB-0015", "KB-0014")),
            new Case("锌和硒一起补有没有上限", Stratum.HARD, List.of("KB-0039", "KB-0010"))
    );
}