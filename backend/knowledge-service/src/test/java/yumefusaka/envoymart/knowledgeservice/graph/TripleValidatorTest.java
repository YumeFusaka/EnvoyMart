package yumefusaka.envoymart.knowledgeservice.graph;

import org.junit.jupiter.api.Test;
import yumefusaka.envoymart.knowledgeservice.entity.KnowledgeChunkEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 校验闸的边界。这里的每一条都对应一个<b>真的发生过或者差一点发生</b>的失败，
 * 不是凑覆盖率。
 * <p>
 * 这些用例之前一条都没有，而这一类代码恰恰是「改对了没人知道、改错了也没人知道」——
 * 唯一的表现是图上多一条或少一条边，而那要看演示时点开引用才发现。
 */
class TripleValidatorTest {

    /** 语料里那种文档：正文提到华法林，也提到阿司匹林，但从未说它们之间有相互作用 */
    private static final String BODY = """
            深海鱼油软胶囊说明书
            本品为鱼油提取物制剂，含有丰富的 EPA 与 DHA。
            本品与华法林等抗凝药物合用可能增加出血风险。
            正在服用阿司匹林的患者请咨询医师。
            出血性疾病患者慎用。
            """;

    private static Triple triple(String headKind, String headName, String relation,
                                 String tailKind, String tailName, String quote) {
        return new Triple(headKind, headName, null, relation, tailKind, tailName, null, null, quote);
    }

    private static TripleValidator.Result validate(Triple... triples) {
        return TripleValidator.validate(List.of(triples), "KB-0006", BODY, List.of());
    }

    // ==================== 端点必须出自原文 ====================

    @Test
    void 引文逐字存在但两个端点都是编的_必须丢弃() {
        // 这条是本次改造的核心用例。模型当然知道华法林不能和阿司匹林乱吃，
        // 而它填的引文在正文里逐字存在——只是那句话讲的是别的事。
        // 只校验引文的那一版会放它入库，于是图上多一条带着「伪溯源」的边，
        // 演示时点开引用高亮的是「正在服用阿司匹林的患者请咨询医师」这句无关的话
        TripleValidator.Result result = validate(
                triple("NUTRIENT", "维生素K", "INTERACTS_WITH", "DRUG", "阿司匹林",
                        "正在服用阿司匹林的患者请咨询医师"));

        assertThat(result.accepted()).isEmpty();
        assertThat(result.count(TripleValidator.RejectReason.UNANCHORED)).isEqualTo(1);
    }

    @Test
    void 端点只有一个出自原文_另一个是编的_同样丢弃() {
        // 华法林在正文里，维生素K 不在。半真半假的边同样不该入库——
        // 它的结论("维生素K 与华法林相互作用")整个是编的，不是只有一半错
        TripleValidator.Result result = validate(
                triple("NUTRIENT", "维生素K", "INTERACTS_WITH", "DRUG", "华法林",
                        "本品与华法林等抗凝药物合用可能增加出血风险"));

        assertThat(result.accepted()).isEmpty();
        assertThat(result.count(TripleValidator.RejectReason.UNANCHORED)).isEqualTo(1);
    }

    @Test
    void 两个端点都出自原文_正常入库() {
        TripleValidator.Result result = validate(
                triple("INGREDIENT", "深海鱼油", "INTERACTS_WITH", "DRUG", "华法林",
                        "本品与华法林等抗凝药物合用可能增加出血风险"));

        assertThat(result.accepted()).hasSize(1);
        GroundedTriple accepted = result.accepted().get(0);
        assertThat(accepted.headName()).isEqualTo("深海鱼油");
        assertThat(accepted.quoteStart()).isEqualTo(BODY.indexOf("本品与华法林"));
    }

    @Test
    void 商品端不要求出现在正文里() {
        // 文档里写的是「本品」，而商品节点键是 SPU 编号——正文里当然找不到。
        // 它和目录的对齐在 ai-service 做，这里只认键的形状
        TripleValidator.Result result = validate(
                triple("PRODUCT", "SPU7", "CONTAINS", "INGREDIENT", "深海鱼油",
                        "本品为鱼油提取物制剂，含有丰富的 EPA 与 DHA"));

        assertThat(result.accepted()).hasSize(1);
        assertThat(result.rejected()).isZero();
    }

    @Test
    void 展示名也能作为锚定的依据() {
        // 模型给端点用的是别名，正文里只有展示名——两个写法都算出自原文
        Triple t = new Triple("INGREDIENT", "鱼油", "鱼油软胶囊", "INTERACTS_WITH",
                "DRUG", "华法林", null, null, "本品与华法林等抗凝药物合用可能增加出血风险");
        TripleValidator.Result result = validate(t);

        assertThat(result.accepted()).hasSize(1);
    }

    // ==================== 实体消解（别名归并） ====================

    /**
     * 语料里的真实分歧：{@code KB-0009} 写「富马酸亚铁」、{@code KB-0010} 写「铁剂」。
     * 两条边各自成立却接不上，于是「孕期复合营养包 + 左旋多巴」这个跨文档查询查不到
     * ——而这条多跳正是这张图存在的理由。
     */
    private static final String IRON_BODY = """
            孕期复合营养包说明书
            本品主要成分为富马酸亚铁与叶酸。
            铁剂与左旋多巴、甲状腺素合用会降低后者吸收，应间隔至少 2 小时。
            """;

    private static TripleValidator.Result validateIron(Triple... triples) {
        return TripleValidator.validate(List.of(triples), "KB-0009", IRON_BODY, List.of());
    }

    @Test
    void 归并后的端点仍按模型写的原名锚定() {
        // 本类最容易写反的一处：归并要在形状校验<b>之前</b>算出来（形状要用规范类型），
        // 而锚定必须用<b>模型写的那个名字</b>。文档里写的是「富马酸亚铁」，
        // 拿规范名「铁剂」去正文里找，这一条会被判成「端点无出处」整体丢掉——
        // 恰好丢掉那条跨文档多跳的一半
        TripleValidator.Result result = validateIron(
                triple("INGREDIENT", "富马酸亚铁", "INTERACTS_WITH", "DRUG", "左旋多巴",
                        "铁剂与左旋多巴、甲状腺素合用会降低后者吸收"));

        assertThat(result.count(TripleValidator.RejectReason.UNANCHORED)).isZero();
        assertThat(result.accepted()).hasSize(1);
        GroundedTriple accepted = result.accepted().get(0);
        // 节点键与展示名一并归到规范名：键是「铁剂」而标签留着「富马酸亚铁」的话，
        // 前端点开这个节点看到的是旧名字，而它底下的边属于「铁剂」——一个节点两副面孔
        assertThat(accepted.headName()).isEqualTo("铁剂");
        assertThat(accepted.headLabel()).isEqualTo("铁剂");
    }

    @Test
    void 归并出的自环_报自环而不是词表() {
        // 归并会顺带改类型（两端都成了 INGREDIENT），于是这一条同时是自环和类型不符。
        // 报词表会把人送去改提示词与枚举，而那里没坏——归因错了比不归因更贵
        TripleValidator.Result result = validateIron(
                triple("INGREDIENT", "富马酸亚铁", "INTERACTS_WITH", "DRUG", "铁剂",
                        "铁剂与左旋多巴、甲状腺素合用会降低后者吸收"));

        assertThat(result.accepted()).isEmpty();
        assertThat(result.count(TripleValidator.RejectReason.SELF_LOOP)).isEqualTo(1);
        assertThat(result.count(TripleValidator.RejectReason.VOCABULARY)).isZero();
    }

    @Test
    void 表里没有的写法沿用原名原类型() {
        // 归并只对登记过的写法生效，其余原样放过。若把「表里没有」也当成需要特殊处理的
        // 情况，等于给整张图强加一个隐含词表，模型抽出的新实体全落不进来——
        // 而症状是图悄悄变小，不是报错
        assertThat(TripleValidator.canonicalName("叶酸")).isEqualTo("叶酸");
        assertThat(TripleValidator.canonicalName("富马酸亚铁")).isEqualTo("铁剂");

        // 叶酸没登记，于是按模型给的 NUTRIENT 走：PROVIDES 的尾端只接受 NUTRIENT，
        // 若这里被归成别的类型，这一条会被误判成词表不符
        TripleValidator.Result result = validateIron(
                triple("INGREDIENT", "富马酸亚铁", "PROVIDES", "NUTRIENT", "叶酸",
                        "本品主要成分为富马酸亚铁与叶酸"));

        assertThat(result.accepted()).hasSize(1);
    }

    // ==================== 商品键的形状 ====================

    @Test
    void 商品键不是SPU编号_丢弃() {
        // 服务端必须自己校验形状，不能只靠客户端对齐：内部写入接口直连端口就能调，
        // 不校验的话谁都能往图上灌一个永远连不上商品目录的商品节点，
        // 而它在查询里的表现是「这个商品没有已知相互作用」——一个不报错的错误答案
        TripleValidator.Result result = validate(
                triple("PRODUCT", "鱼油软胶囊", "CONTAINS", "INGREDIENT", "深海鱼油",
                        "本品为鱼油提取物制剂，含有丰富的 EPA 与 DHA"));

        assertThat(result.accepted()).isEmpty();
        assertThat(result.count(TripleValidator.RejectReason.MALFORMED)).isEqualTo(1);
    }

    @Test
    void 超长实体名_丢弃() {
        TripleValidator.Result result = validate(
                triple("INGREDIENT", "深海鱼油".repeat(20), "INTERACTS_WITH", "DRUG", "华法林",
                        "本品与华法林等抗凝药物合用可能增加出血风险"));

        assertThat(result.accepted()).isEmpty();
        assertThat(result.count(TripleValidator.RejectReason.MALFORMED)).isEqualTo(1);
    }

    // ==================== 空白字符 ====================

    @Test
    void 不换行空格不影响引文命中() {
        // 从网页或模型输出里复制的文本常带 U+00A0，而 Character.isWhitespace 不认它、
        // String.strip() 也不剥它。不处理的话同一句话会因为一个看不见的字符被判成幻觉
        String bodyWithNbsp = "本品为深海鱼油制剂。本品与华法林\u00A0等抗凝药物合用可能增加出血风险。";
        TripleValidator.Result result = TripleValidator.validate(List.of(
                triple("INGREDIENT", "深海鱼油", "INTERACTS_WITH", "DRUG", "华法林",
                        "本品与华法林 等抗凝药物合用可能增加出血风险")),
                "KB-0006", bodyWithNbsp, List.of());

        assertThat(result.accepted()).hasSize(1);
    }

    @Test
    void 不换行空格不影响实体名合并() {
        // 节点键的规范化必须把 NBSP 当空白，否则「维生素\u00A0d3」和「维生素d3」
        // 会变成两个节点，多跳路径在中间断开
        assertThat(TripleValidator.normalizeName("维生素\u00A0D3"))
                .isEqualTo(TripleValidator.normalizeName("维生素 D3"))
                .isEqualTo("维生素d3");
    }

    @Test
    void 零宽空格不影响实体名合并() {
        assertThat(TripleValidator.normalizeName("维生素\u200BD3")).isEqualTo("维生素d3");
    }

    // ==================== 基本判定 ====================

    @Test
    void 自环丢弃() {
        // 名字只差一个空格，规范化之后是同一个键——按节点键判自环，不按字面
        TripleValidator.Result result = validate(
                triple("INGREDIENT", "深海鱼油", "PROVIDES", "NUTRIENT", "深海 鱼油",
                        "本品为鱼油提取物制剂，含有丰富的 EPA 与 DHA"));

        assertThat(result.accepted()).isEmpty();
        assertThat(result.count(TripleValidator.RejectReason.SELF_LOOP)).isEqualTo(1);
    }

    @Test
    void 关系与类型不匹配时归到词表而不是别的类() {
        // 归因错了比不归因更贵：它让人去改一个没坏的地方。
        // 第一版就是靠事后重算推导原因，于是自环、空名这类既不属于词表、
        // 引文又能找到的条目被静默算进了「词表」，实测日志写着「词表 10 / 无出处 0」
        TripleValidator.Result result = validate(
                triple("PRODUCT", "SPU7", "PROVIDES", "NUTRIENT", "EPA",
                        "本品为鱼油提取物制剂，含有丰富的 EPA 与 DHA"));

        assertThat(result.count(TripleValidator.RejectReason.VOCABULARY)).isEqualTo(1);
        assertThat(result.count(TripleValidator.RejectReason.UNANCHORED)).isZero();
    }

    @Test
    void 引文太短不构成依据() {
        TripleValidator.Result result = validate(
                triple("INGREDIENT", "深海鱼油", "INTERACTS_WITH", "DRUG", "华法林", "华法林"));

        assertThat(result.accepted()).isEmpty();
        assertThat(result.count(TripleValidator.RejectReason.UNGROUNDED)).isEqualTo(1);
    }

    @Test
    void 命中位置映射到所属切片() {
        KnowledgeChunkEntity chunk = new KnowledgeChunkEntity();
        chunk.setChunkId("KB-0006-002");
        chunk.setCharOffset(BODY.indexOf("本品与华法林"));

        TripleValidator.Result result = TripleValidator.validate(List.of(
                        triple("INGREDIENT", "深海鱼油", "INTERACTS_WITH", "DRUG", "华法林",
                                "本品与华法林等抗凝药物合用可能增加出血风险")),
                "KB-0006", BODY, List.of(chunk));

        assertThat(result.accepted().get(0).chunkId()).isEqualTo("KB-0006-002");
    }

    // ==================== 组合禁忌 ====================

    /** 一句话里明确列出三样，且给出共同后果 —— 这是唯一能支撑组合关系的句式 */
    private static final String COMBO_BODY = """
            复合矿物质补充剂说明书
            本品含铁剂、钙剂与维生素D，三者同服可能增加结石风险，肾结石患者禁用。
            铁剂与钙剂同服会影响铁的吸收。
            维生素D 与噻嗪类利尿剂合用可能引起高钙血症。
            """;

    private static Triple combo(String members, String tailKind, String tailName, String quote) {
        return new Triple("COMBINATION", members, null, "COMBINED_WITH", tailKind, tailName,
                null, "三者同服可能增加结石风险", quote);
    }

    @Test
    void 组合成员被引文全覆盖时入库_键按成员排序且可复现() {
        // 「钙剂＋铁剂＋维生素D」与「铁剂＋钙剂＋维生素D」是同一个组合。
        // 不排序的话同一件事会在图上分成两个节点，条数与来源各算一半 —— 而这是静默的
        TripleValidator.Result a = TripleValidator.validate(List.of(
                combo("铁剂+钙剂+维生素D", "POPULATION", "肾结石患者",
                        "本品含铁剂、钙剂与维生素D，三者同服可能增加结石风险")),
                "KB-0031", COMBO_BODY, List.of());
        TripleValidator.Result b = TripleValidator.validate(List.of(
                combo("钙剂＋维生素D＋铁剂", "POPULATION", "肾结石患者",
                        "本品含铁剂、钙剂与维生素D，三者同服可能增加结石风险")),
                "KB-0031", COMBO_BODY, List.of());

        assertThat(a.accepted()).hasSize(1);
        assertThat(b.accepted()).hasSize(1);
        assertThat(a.accepted().get(0).headName())
                .isEqualTo(b.accepted().get(0).headName())
                .startsWith("combo:")
                // 成员已被别名归一（钙剂 → 钙），且按字典序拼接
                .isEqualTo("combo:维生素d|钙|铁剂");
    }

    @Test
    void 引文只提到部分成员_必须丢弃() {
        // 这条是本批最要紧的判据。文档里有「铁剂与钙剂同服影响铁的吸收」这句话，
        // 它确实是逐字存在的、也确实提到了两样东西；但模型把「维生素D」也塞进了组合，
        // 而那句话里没有维生素D。放它入库，用户就会看到「铁剂+钙剂+维生素D 有结石风险」
        // 这样一条**带着完整伪溯源**的结论 —— 少报一条组合远好过多报一条
        TripleValidator.Result result = TripleValidator.validate(List.of(
                combo("铁剂+钙剂+维生素D", "POPULATION", "肾结石患者",
                        "铁剂与钙剂同服会影响铁的吸收")),
                "KB-0031", COMBO_BODY, List.of());

        assertThat(result.accepted()).isEmpty();
        assertThat(result.count(TripleValidator.RejectReason.UNANCHORED)).isEqualTo(1);
    }

    @Test
    void 只有一个成员的组合是畸形_必须丢弃() {
        // 一个成员的「组合」与单跳是一回事，走 INTERACTS_WITH 表达即可。
        // 放它进来等于给同一件事多加一条形状不同的边，查询时会报两遍
        TripleValidator.Result result = TripleValidator.validate(List.of(
                combo("铁剂", "DRUG", "左旋多巴", "铁剂与钙剂同服会影响铁的吸收")),
                "KB-0031", COMBO_BODY, List.of());

        assertThat(result.accepted()).isEmpty();
        assertThat(result.count(TripleValidator.RejectReason.MALFORMED)).isEqualTo(1);
    }

    @Test
    void 组合的尾端必须出现在原文里() {
        // 尾端是编的（正文里没有「高钾血症患者」），整条丢弃 —— 与单跳那条判据一致。
        // 组合的头不是实体，所以「端点有无出处」这条只剩尾端可查
        TripleValidator.Result result = TripleValidator.validate(List.of(
                combo("铁剂+钙剂", "POPULATION", "高钾血症患者",
                        "本品含铁剂、钙剂与维生素D，三者同服可能增加结石风险")),
                "KB-0031", COMBO_BODY, List.of());

        assertThat(result.accepted()).isEmpty();
        assertThat(result.count(TripleValidator.RejectReason.UNANCHORED)).isEqualTo(1);
    }
}
