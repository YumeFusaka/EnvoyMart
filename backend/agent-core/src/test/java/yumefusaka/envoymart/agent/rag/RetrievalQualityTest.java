package yumefusaka.envoymart.agent.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 检索质量回归 —— 用标注样本锁住召回与排序，防止改动把效果悄悄改坏。
 * <p>
 * 这里只跑 BM25 路（向量库留空），因此指标反映的是关键词检索的底线；
 * 接入 Milvus + 重排后指标应更高，可用同一套样本对比。
 */
class RetrievalQualityTest {

    private static final int TOP_K = 3;

    private static final List<Document> DOCS = List.of(
            doc("after_sale", "七天无理由与售后规则",
                    "除定制类和贴身个护商品外，大部分商品支持七天无理由退货；质量问题支持换新与运费补贴。",
                    "退货", "售后", "退款"),
            doc("logistics", "物流说明",
                    "现货订单通常在 24 小时内出库，华东地区预计 1 到 2 天送达，偏远地区 3 到 5 天。",
                    "物流", "快递", "配送"),
            doc("promotion", "平台满减规则",
                    "本周数码会场满 199 减 20，满 299 减 40；学生认证用户可叠加 95 折校园券。",
                    "活动", "满减", "优惠"),
            doc("coupon", "优惠券使用说明",
                    "优惠券可与满减叠加，但同一订单最多使用一张优惠券；过期优惠券不予补发。",
                    "优惠券", "叠加", "过期"),
            doc("payment", "支付方式与到账时间",
                    "支持支付宝、微信与银行卡支付；支付成功后立即到账，退款原路返回约 1 到 3 个工作日。",
                    "支付", "退款", "到账"),
            doc("invoice", "发票开具说明",
                    "订单完成后可在订单详情页申请电子发票，抬头支持个人与企业，开具后发送至预留邮箱。",
                    "发票", "开票", "抬头"),
            doc("member", "会员等级与权益",
                    "普通会员累计消费满 1000 元升级银卡，享受包邮与专属客服；满 5000 元升级金卡。",
                    "会员", "等级", "权益"),
            doc("guide", "百元耳机选购建议",
                    "学生党选择百元耳机时，优先看佩戴舒适度、麦克风通话清晰度和续航，通勤场景重视低延迟和抗风噪。",
                    "耳机", "学生党", "推荐")
    );

    /** 与文档用词高度重合的查询：关键词检索应该稳拿。 */
    private static final List<RetrievalEvaluator.EvalCase> LEXICAL_CASES = List.of(
            new RetrievalEvaluator.EvalCase("我想退货，七天无理由怎么操作？", List.of("after_sale")),
            new RetrievalEvaluator.EvalCase("华东地区多久能送到？", List.of("logistics")),
            new RetrievalEvaluator.EvalCase("满减活动是怎么算的？", List.of("promotion")),
            new RetrievalEvaluator.EvalCase("优惠券能和满减一起用吗？", List.of("coupon", "promotion")),
            new RetrievalEvaluator.EvalCase("退款多久到账？", List.of("payment", "after_sale")),
            new RetrievalEvaluator.EvalCase("怎么开发票？", List.of("invoice")),
            new RetrievalEvaluator.EvalCase("会员升级有什么权益？", List.of("member")),
            new RetrievalEvaluator.EvalCase("学生党买耳机有什么推荐？", List.of("guide"))
    );

    /**
     * 口语化改写：与文档几乎无字面重合。
     * 纯关键词检索会明显掉分——这正是引入向量检索与重排的理由。
     */
    private static final List<RetrievalEvaluator.EvalCase> PARAPHRASE_CASES = List.of(
            new RetrievalEvaluator.EvalCase("买的东西坏了能不能换新的", List.of("after_sale")),
            new RetrievalEvaluator.EvalCase("我的包裹怎么还没到啊", List.of("logistics")),
            new RetrievalEvaluator.EvalCase("学生有没有便宜点", List.of("promotion")),
            new RetrievalEvaluator.EvalCase("能开公司抬头的收据吗", List.of("invoice"))
    );

    @Test
    void 关键词检索在字面重合的查询上表现稳定() {
        RetrievalEvaluator.EvalReport report = evaluate(LEXICAL_CASES);

        System.out.println("[检索评测-字面] " + report);

        assertThat(report.hitRate()).isGreaterThanOrEqualTo(0.85);
        assertThat(report.mrr()).isGreaterThanOrEqualTo(0.8);
    }

    @Test
    void 口语化改写查询会掉分_用于对比引入向量与重排的收益() {
        RetrievalEvaluator.EvalReport report = evaluate(PARAPHRASE_CASES);

        System.out.println("[检索评测-口语] " + report);

        // 只保证不崩：这组数据的价值是暴露关键词检索的短板，不是设门槛
        assertThat(report.caseCount()).isEqualTo(PARAPHRASE_CASES.size());
    }

    private RetrievalEvaluator.EvalReport evaluate(List<RetrievalEvaluator.EvalCase> cases) {
        Retriever retriever = new HybridRetriever(
                new InMemoryVectorStore(new SimpleEmbeddingService()), DOCS);
        return new RetrievalEvaluator().evaluate(retriever, cases, TOP_K);
    }

    @Test
    void 重排器可以改变最终排序() {
        Retriever retriever = new HybridRetriever(
                new InMemoryVectorStore(new SimpleEmbeddingService()), DOCS,
                // 假重排：把含"发票"的候选顶到第一位，验证重排确实生效
                (query, candidates, topK) -> candidates.stream()
                        .sorted((a, b) -> Boolean.compare(
                                b.getContent().contains("发票"), a.getContent().contains("发票")))
                        .limit(topK)
                        .toList());

        List<DocumentChunk> result = retriever.retrieve("怎么开发票？", TOP_K);

        assertThat(result).isNotEmpty();
        assertThat(result.get(0).getDocId()).isEqualTo("invoice");
    }

    private static Document doc(String id, String title, String content, String... tags) {
        return Document.builder().id(id).title(title).content(content).tags(List.of(tags)).build();
    }
}
