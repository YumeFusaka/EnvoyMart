package yumefusaka.envoymart.agent.rag;

import java.util.ArrayList;
import java.util.List;

/**
 * 检索评测的共享夹具 —— 语料与标注样本的单一来源。
 * <p>
 * CI 回归测试（{@code RetrievalQualityTest}）与本地对照实验
 * （{@code RetrievalComparisonTest}）都从这里取数据，避免两处维护导致口径不一致。
 * <p>
 * 评测集独立维护、与生产语料解耦：生产知识库会更新，但评测基准不变，
 * 指标才有历史可比性。语料规模（30 篇）远大于生产的演示语料（4 篇），
 * 因此指标是对线上表现的<b>保守估计</b>——语料越大检索越难。
 */
final class RetrievalFixtures {

    private RetrievalFixtures() {
    }

    static final List<Document> DOCS = List.of(
            doc("after_sale", "七天无理由与售后规则",
                    "除定制类和贴身个护商品外，大部分商品支持七天无理由退货；质量问题支持换新与运费补贴。",
                    "退货", "售后", "退款"),
            doc("refund_timeline", "退款到账时效",
                    "审核通过后原路退回，支付宝与微信一般 1 到 3 个工作日到账，银行卡最长 7 个工作日。",
                    "退款", "到账", "时效"),
            doc("exchange", "换货流程说明",
                    "换货需先提交申请，审核通过后寄回原商品，仓库验收后重新发货；换货不收取额外费用。",
                    "换货", "流程", "寄回"),
            doc("logistics", "物流说明",
                    "现货订单通常在 24 小时内出库，华东地区预计 1 到 2 天送达，偏远地区 3 到 5 天。",
                    "物流", "快递", "配送"),
            doc("delivery_range", "配送范围与偏远地区",
                    "目前支持全国大部分地区配送，港澳台及部分偏远乡镇暂不支持，下单时会提示是否可达。",
                    "配送", "范围", "偏远"),
            doc("logistics_damage", "运输破损处理",
                    "签收时发现外包装破损应拍照拒收，已签收的可提交破损凭证，核查后补发或退款。",
                    "破损", "拒收", "补发"),
            doc("cod", "货到付款说明",
                    "货到付款仅支持部分城市，订单金额上限 2000 元，签收时需当面验货并支付现金或刷卡。",
                    "货到付款", "验货", "现金"),
            doc("promotion", "平台满减规则",
                    "本周数码会场满 199 减 20，满 299 减 40；学生认证用户可叠加 95 折校园券。",
                    "活动", "满减", "优惠"),
            doc("coupon", "优惠券使用说明",
                    "优惠券可与满减叠加，但同一订单最多使用一张优惠券；过期优惠券不予补发。",
                    "优惠券", "叠加", "过期"),
            doc("student_discount", "学生优惠认证",
                    "在校学生通过学信网认证后可领取校园券，认证有效期一年，到期需重新提交学籍信息。",
                    "学生", "认证", "校园券"),
            doc("group_buy", "拼团规则",
                    "拼团需在 24 小时内凑齐成团人数，未成团自动退款；成团后不支持取消单个订单。",
                    "拼团", "成团", "退款"),
            doc("flash_sale", "秒杀活动规则",
                    "秒杀商品数量有限，下单后 15 分钟内未支付将自动释放库存，同一账号限购一件。",
                    "秒杀", "限购", "库存"),
            doc("price_protection", "价格保护说明",
                    "商品签收后 15 天内若发生降价，可申请差价补偿；参与秒杀与拼团的订单不适用。",
                    "价保", "降价", "差价"),
            doc("presale", "预售规则",
                    "预售商品需支付定金，尾款在开售日 24 小时内结清，逾期未付定金不退但可转入余额。",
                    "预售", "定金", "尾款"),
            doc("payment", "支付方式与到账时间",
                    "支持支付宝、微信与银行卡支付；支付成功后立即到账，退款原路返回。",
                    "支付", "退款", "到账"),
            doc("installment", "分期付款说明",
                    "订单满 500 元可申请 3 到 12 期分期，手续费率随期数递增，提前还款不减免手续费。",
                    "分期", "手续费", "还款"),
            doc("invoice", "发票开具说明",
                    "订单完成后可在订单详情页申请电子发票，抬头支持个人与企业，开具后发送至预留邮箱。",
                    "发票", "开票", "抬头"),
            doc("invoice_type", "发票类型与税率",
                    "默认开具电子普通发票，企业可申请增值税专用发票，需提供纳税人识别号与开户信息。",
                    "专票", "普票", "税率"),
            doc("member", "会员等级与权益",
                    "普通会员累计消费满 1000 元升级银卡，享受包邮与专属客服；满 5000 元升级金卡。",
                    "会员", "等级", "权益"),
            doc("points", "积分获取与使用",
                    "每消费 1 元累计 1 积分，积分可在结算时抵扣现金，100 积分抵 1 元，有效期两年。",
                    "积分", "抵扣", "有效期"),
            doc("review", "评价规则",
                    "确认收货后可对商品评价，支持图文与视频；评价内容需真实，恶意差评将被折叠。",
                    "评价", "晒图", "差评"),
            doc("quality_report", "商品质量反馈",
                    "使用中出现质量问题可提交检测报告，核实后按假一赔十处理，运费由平台承担。",
                    "质量", "检测", "赔付"),
            doc("fake_goods", "假一赔十承诺",
                    "平台承诺正品保障，经鉴定的假冒商品按售价十倍赔付，并退还全部运费与税费。",
                    "正品", "假货", "赔付"),
            doc("warranty", "保修与维修服务",
                    "数码商品享受一年官方保修，非人为损坏免费维修；人为损坏按官方报价收取零件费。",
                    "保修", "维修", "零件"),
            doc("order_cancel", "订单取消规则",
                    "未发货订单可直接取消并全额退款；已发货订单需拒收或签收后申请退货。",
                    "取消", "退款", "拒收"),
            doc("address_change", "收货地址修改",
                    "订单发货前可自助修改收货地址，已发货的需联系客服拦截，拦截失败则无法更改。",
                    "地址", "修改", "拦截"),
            doc("account_security", "账号安全说明",
                    "建议开启登录二次验证，异常登录会触发短信提醒；账号不支持转让与共享。",
                    "账号", "安全", "验证"),
            doc("privacy", "隐私与数据保护",
                    "平台仅在履约范围内使用个人信息，不会向第三方出售；用户可申请导出或删除数据。",
                    "隐私", "个人信息", "数据"),
            doc("complaint", "投诉与举报渠道",
                    "对商家服务不满可在订单页发起投诉，平台 48 小时内介入；举报违规商品有奖励。",
                    "投诉", "举报", "介入"),
            doc("guide", "百元耳机选购建议",
                    "学生党选择百元耳机时，优先看佩戴舒适度、麦克风通话清晰度和续航，通勤场景重视低延迟和抗风噪。",
                    "耳机", "学生党", "推荐")
    );

    /** 字面重合：查询与文档用词高度一致，关键词检索应该稳拿。 */
    static final List<RetrievalEvaluator.EvalCase> LEXICAL_CASES = List.of(
            new RetrievalEvaluator.EvalCase("我想退货，七天无理由怎么操作？", List.of("after_sale")),
            new RetrievalEvaluator.EvalCase("华东地区多久能送到？", List.of("logistics")),
            new RetrievalEvaluator.EvalCase("满减活动是怎么算的？", List.of("promotion")),
            new RetrievalEvaluator.EvalCase("优惠券能和满减一起用吗？", List.of("coupon", "promotion")),
            new RetrievalEvaluator.EvalCase("退款多久到账？", List.of("refund_timeline", "payment")),
            new RetrievalEvaluator.EvalCase("怎么开发票？", List.of("invoice")),
            new RetrievalEvaluator.EvalCase("会员升级有什么权益？", List.of("member")),
            new RetrievalEvaluator.EvalCase("学生党买耳机有什么推荐？", List.of("guide")),
            new RetrievalEvaluator.EvalCase("积分怎么抵扣？", List.of("points")),
            new RetrievalEvaluator.EvalCase("保修期多久？", List.of("warranty"))
    );

    /** 口语化改写：与文档几乎无字面重合，纯关键词检索会明显掉分。 */
    static final List<RetrievalEvaluator.EvalCase> PARAPHRASE_CASES = List.of(
            new RetrievalEvaluator.EvalCase("买的东西坏了能不能换新的", List.of("quality_report", "after_sale")),
            new RetrievalEvaluator.EvalCase("我的包裹怎么还没到啊", List.of("logistics")),
            new RetrievalEvaluator.EvalCase("学生有没有便宜点", List.of("student_discount", "promotion")),
            new RetrievalEvaluator.EvalCase("能开公司抬头的收据吗", List.of("invoice", "invoice_type")),
            new RetrievalEvaluator.EvalCase("钱什么时候能退回来", List.of("refund_timeline")),
            new RetrievalEvaluator.EvalCase("这东西是正品吗", List.of("fake_goods")),
            new RetrievalEvaluator.EvalCase("我不想买了怎么退", List.of("order_cancel")),
            new RetrievalEvaluator.EvalCase("发货了还能改地方吗", List.of("address_change")),
            new RetrievalEvaluator.EvalCase("包装压扁了咋整", List.of("logistics_damage")),
            new RetrievalEvaluator.EvalCase("我想跟客服投诉商家", List.of("complaint"))
    );

    /**
     * 语义鸿沟：口语化短句，且查询用词与目标文档<b>几乎无字面交集</b>。
     * <p>
     * 这组是纯关键词检索的天花板所在——也是引入向量检索最直接的依据。
     */
    static final List<RetrievalEvaluator.EvalCase> HARD_CASES = List.of(
            new RetrievalEvaluator.EvalCase("东西不合适想退掉", List.of("after_sale")),
            new RetrievalEvaluator.EvalCase("不想要了", List.of("order_cancel")),
            new RetrievalEvaluator.EvalCase("这个能便宜点吗", List.of("promotion")),
            new RetrievalEvaluator.EvalCase("太贵了", List.of("price_protection")),
            new RetrievalEvaluator.EvalCase("什么时候能到", List.of("logistics")),
            new RetrievalEvaluator.EvalCase("坏了谁负责", List.of("warranty")),
            new RetrievalEvaluator.EvalCase("怎么联系你们", List.of("complaint")),
            new RetrievalEvaluator.EvalCase("钱少了一部分", List.of("refund_timeline")),
            new RetrievalEvaluator.EvalCase("能换个别的颜色吗", List.of("exchange")),
            new RetrievalEvaluator.EvalCase("会不会泄露我的信息", List.of("privacy"))
    );

    static List<RetrievalEvaluator.EvalCase> allCases() {
        List<RetrievalEvaluator.EvalCase> all = new ArrayList<>(LEXICAL_CASES);
        all.addAll(PARAPHRASE_CASES);
        all.addAll(HARD_CASES);
        return all;
    }

    /**
     * 随机检索的 Hit Rate@K 基线 —— 用于判断实测值是否只是"碰巧"。
     * <p>
     * 每例只有 1 篇相关文档时，随机取 K 篇至少命中一篇的概率
     * = 1 - C(n-K, K)/C(n, K) 化简后 = 1 - (n-K)/n。语料 30 篇、取 top-3 即 0.1。
     */
    static double randomBaselineHitRate(int corpusSize, int topK) {
        return 1.0 - (double) (corpusSize - topK) / corpusSize;
    }

    private static Document doc(String id, String title, String content, String... tags) {
        return Document.builder().id(id).title(title).content(content).tags(List.of(tags)).build();
    }
}
