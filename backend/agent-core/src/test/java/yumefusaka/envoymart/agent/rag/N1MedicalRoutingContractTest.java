package yumefusaka.envoymart.agent.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

/** R1 路由判据落地校验：逐条对照 JS 原型结论（零成本，不调模型）。 */
class N1MedicalRoutingContractTest {

    @Test
    void 路由判据与设计一致() {
        // 官方验收用例
        List<Object[]> cases = List.of(
                new Object[]{"推荐一款维生素D补充剂", false},
                new Object[]{"维生素D每日上限是多少", true},
                new Object[]{"搜索钙片，至少展示3个候选并给出各规格", false},
                new Object[]{"帮我推荐几款乳清蛋白粉", false},
                new Object[]{"取消刚才的操作，不执行加购。", false},
                new Object[]{"我在补钙，同时需要吃抗生素，两者要怎么安排时间？", true},
                new Object[]{"磷虾油和阿司匹林一起用要注意什么？", true},
                new Object[]{"褪黑素会不会影响华法林或降压药？", true},
                new Object[]{"儿童钙软糖和四环素类药物为什么要错开？", true},
                new Object[]{"维生素 D 的每日可耐受最高摄入量是多少？我在吃维生素 D3 软胶囊，会超量吗？", true},
                // 36 题全量：判定不得改变（这些全部应为 true，见基线）
                new Object[]{"保健食品的标签上必须标注哪句警示用语？", true},
                new Object[]{"平台支持几天无理由退货？从哪天开始算？", false},
                new Object[]{"退货的运费由谁承担？", false},
                new Object[]{"退款一般多久到账？", false},
                new Object[]{"维生素 D3 软胶囊每天吃多少？", true},
                new Object[]{"铁叶酸片每片含多少铁和叶酸？", true},
                new Object[]{"益生菌冻干粉开封以后怎么贮存？", true},
                new Object[]{"乳清蛋白粉一次建议吃多少？", true},
                new Object[]{"哪些商品不适用七天无理由退货？", false},
                new Object[]{"会员等级是怎么划分的？不同等级有什么权益？", false},
                new Object[]{"优惠券可以叠加使用吗？", false},
                new Object[]{"快递签收的时候发现包装破损怎么办？", false},
                new Object[]{"我在吃富马酸亚铁补铁，同时还在吃左旋多巴，会有相互作用吗？", true},
                new Object[]{"我买的维生素 D3 软胶囊已经拆封了，还能七天无理由退货吗？", true},
                new Object[]{"哺乳期女性在吃深海鱼油，同时需要吃抗凝药，说明书里有什么提示？", true},
                new Object[]{"备孕女性每天需要多少叶酸？最高不能超过多少？", true},
                new Object[]{"蓝牙耳机怎么连接手机？", false},
                new Object[]{"你们平台支持比特币支付吗？", false},
                new Object[]{"iPhone 16 Pro 的官方保修政策是什么？", false},
                new Object[]{"你们家地板清洁剂的配方成分是什么？", false},
                new Object[]{"火星旅行套餐的退款政策是怎么规定的？", false},
                new Object[]{"帮我预约下周一到三甲医院看内分泌科，要挂专家号。", false},
                new Object[]{"鱼油中的 EPA 和 DHA 与华法林合用有什么风险？", true},
                new Object[]{"碳酸钙 D3 咀嚼片里的钙与喹诺酮抗生素会相互影响吗？", true},
                new Object[]{"铁叶酸片中的铁会影响左旋多巴吸收吗？", true},
                new Object[]{"维生素 K2 和华法林之间是什么关系？", true},
                new Object[]{"辅酶 Q10 对华法林的抗凝作用有什么影响？", true},
                new Object[]{"孕期 DHA 藻油和抗凝药合用有什么风险？", true},
                new Object[]{"益生菌咀嚼片能和抗生素同一时间吃吗？", true},
                new Object[]{"乳清蛋白粉对乳糖不耐受者有什么选择建议，和左旋多巴呢？", true},
                new Object[]{"高纯度鱼油的 EPA、DHA 与氯吡格雷合用有什么注意事项？", true},
                // 真实会话中曾被误判的业务操作轮
                new Object[]{"我要购买益生菌粉，帮我加入购物车", false},
                new Object[]{"那就来一个碳酸钙 D3 咀嚼片，帮我加入购物车", false},
                new Object[]{"褪黑素缓释片这个看起来不错，有什么规格可以选择吗", false},
                new Object[]{"把之前那个钙片去掉，那个不要了，这次结单只结算这个褪黑素", false},
                new Object[]{"那这两款护眼的补充剂你推荐哪一款？", false},
                new Object[]{"推荐一款适合送长辈的钙片", false},
                new Object[]{"我需要补一些维生素C，我是学生，所以推荐一些便宜的", false},
                new Object[]{"我要给我的儿子买一点维生素，有没有推荐的商品", false},
                // G06 回归：属性名词「规格」不得把医学文档问题判成非医学
                new Object[]{"铁叶酸片两份说明书规格不一样，应以哪一版为准？", true},
                new Object[]{"我需要补一些维生素C，我是学生，所以推荐一些便宜的", false});

        StringBuilder failures = new StringBuilder();
        for (Object[] c : cases) {
            String q = (String) c[0];
            boolean want = (boolean) c[1];
            boolean got = MedicalEvidenceVerifier.isMedicalQuestion(q);
            if (got != want) {
                failures.append("  expect=").append(want).append(" got=").append(got).append(" | ").append(q).append('\n');
            }
        }
        System.out.println("[R1] 用例数=" + cases.size() + " 失败数="
                + (failures.isEmpty() ? 0 : failures.toString().lines().count()));
        if (!failures.isEmpty()) {
            throw new AssertionError("R1 路由判据与设计不一致：\n" + failures);
        }
    }
}