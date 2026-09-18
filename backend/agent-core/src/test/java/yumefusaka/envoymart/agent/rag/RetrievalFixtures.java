package yumefusaka.envoymart.agent.rag;

import java.util.ArrayList;
import java.util.List;

/**
 * 检索评测的共享夹具 —— 语料与标注样本的单一来源。
 * <p>
 * CI 回归测试（{@code RetrievalQualityTest}）与本地对照实验
 * （{@code RetrievalComparisonTest}）都从这里取数据，避免两处维护导致口径不一致。
 * <p>
 * <b>规模与结构</b>：90 篇语料、120 条标注查询，按三档分层（各 40 条）。
 * <p>
 * <b>这批样本的定位是回归防线，不是质量结论，读数字时必须带上这些前提：</b>
 * <ul>
 *   <li>语料与标注<b>出自同一作者</b>。三档的难度梯度由作者构造，字面档的高分是设计出来的，
 *       不是能力证明——它的作用是确认"链路没坏"，不是"检索很强"。</li>
 *   <li>样本同时用于开发、对照实验与 CI 门禁，<b>没有留出集</b>。缓解这一点的不是再加样本，
 *       而是：本项目未针对这批样本做过参数调优（无 BM25 权重、RRF 系数或 topK 的搜索），
 *       因此不存在"调到样本上去"的过拟合路径。真实的多标注者一致性校验仍然缺席。</li>
 *   <li>它与线上知识库（{@code AiAgentConfig.knowledgeDocuments()}）是两套独立数据，
 *       规模与主题分布接近但内容不重合。两组指标各自描述各自的语料，<b>不可互相推算</b>。</li>
 * </ul>
 * <p>
 * <b>语料为什么按主题成簇</b>：真实知识库里一个主题下有多篇相互竞争的文档，
 * 而不是一问对一答。本夹具刻意让「售后」「物流」「营销」等主题各占十篇上下，
 * 使同一条查询的 top-3 里出现多篇语义相邻的候选——这既是检索的真实难点，
 * 也让"命中"不再是唯一候选下的必然结果。
 */
final class RetrievalFixtures {

    /** 切片参数 —— 与线上 {@code AiAgentConfig} 的 SimpleRAGEngine 保持一致，避免评测与生产走不同切片 */
    static final int CHUNK_SIZE = 256;
    static final int CHUNK_OVERLAP = 32;

    private RetrievalFixtures() {
    }

    // ==================== 语料（90 篇，按主题成簇） ====================

    static final List<Document> DOCS = List.of(
            // —— 售后与退换（14） ——
            doc("after_sale", "七天无理由与售后规则",
                    "除定制类和贴身个护商品外，大部分商品支持七天无理由退货；质量问题支持换新与运费补贴。",
                    "退货", "售后", "退款"),
            doc("refund_timeline", "退款到账时效",
                    "审核通过后原路退回，支付宝与微信一般 1 到 3 个工作日到账，银行卡最长 7 个工作日。",
                    "退款", "到账", "时效"),
            doc("exchange", "换货流程说明",
                    "换货需先提交申请，审核通过后寄回原商品，仓库验收后重新发货；换货不收取额外费用。",
                    "换货", "流程", "寄回"),
            doc("quality_report", "商品质量反馈",
                    "使用中出现质量问题可提交检测报告，核实后按假一赔十处理，运费由平台承担。",
                    "质量", "检测", "赔付"),
            doc("fake_goods", "假一赔十承诺",
                    "平台承诺正品保障，经鉴定的假冒商品按售价十倍赔付，并退还全部运费与税费。",
                    "正品", "假货", "赔付"),
            doc("warranty", "保修与维修服务",
                    "数码商品享受一年官方保修，非人为损坏免费维修；人为损坏按官方报价收取零件费。",
                    "保修", "维修", "零件"),
            doc("return_shipping", "退货运费谁来承担",
                    "无理由退货由买家承担运费；商品质量问题或发错货的，运费由平台承担并补贴 12 元。",
                    "运费", "退货运费", "补贴"),
            doc("refund_method", "退款方式与到账渠道",
                    "退款一律原路返回，不支持退至其他账户或转为余额；余额支付的订单退回余额。",
                    "退款", "原路", "渠道"),
            doc("pickup_service", "上门取件服务",
                    "退货可选择上门取件，快递员 24 小时内联系；取件免费但需自行打包，超重部分另计。",
                    "上门取件", "退货", "快递"),
            doc("return_packaging", "退货包装要求",
                    "退货需保留原包装与配件，缺少配件可能影响退款金额；商品需不影响二次销售。",
                    "包装", "退货", "配件"),
            doc("custom_goods_return", "定制商品退换限制",
                    "刻字、改尺寸等定制商品不支持七天无理由退货，仅在质量问题时可退换。",
                    "定制", "退货", "限制"),
            doc("cross_border_return", "跨境商品退换说明",
                    "跨境商品受海关监管，非质量问题不支持退货；退货需承担往返国际运费与税费。",
                    "跨境", "退货", "海关"),
            doc("after_sale_entry", "售后申请入口与时效",
                    "订单完成后可在订单详情页发起售后，签收后 15 天内可申请，超期需联系客服。",
                    "售后", "申请", "时效"),
            doc("repair_process", "维修送修流程",
                    "保修期内可申请寄修，平台承担单程运费；维修周期一般 7 到 15 个工作日。",
                    "维修", "寄修", "周期"),

            // —— 物流配送（12） ——
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
            doc("express_choice", "快递公司可以选吗",
                    "默认由平台根据仓库与目的地智能选择快递，暂不支持指定；偏远地区可能转邮政。",
                    "快递", "选择", "邮政"),
            doc("self_pickup", "自提点与驿站取件",
                    "下单时可选择附近自提点，到货后凭取件码自取；自提点保管 3 天，超期退回。",
                    "自提", "驿站", "取件码"),
            doc("delivery_appointment", "配送时间可以预约吗",
                    "大家电等大件支持预约配送时段，下单后客服会电话确认；普通快递不支持指定时间。",
                    "预约", "配送", "大件"),
            doc("logistics_tracking", "物流轨迹查询",
                    "订单发货后可在订单详情查看物流轨迹；轨迹超过 48 小时未更新可联系客服催查。",
                    "物流", "轨迹", "查询"),
            doc("sign_check", "签收与验货须知",
                    "贵重商品建议当面验货再签收；外包装破损可拒收，拒收不产生额外费用。",
                    "签收", "验货", "拒收"),
            doc("urgent_delivery", "加急配送服务",
                    "部分城市支持次日达加急，需在当日 15 点前下单并支付加急费，节假日不适用。",
                    "加急", "次日达", "时效"),
            doc("bulky_delivery", "大件商品配送",
                    "家具家电等大件走专线物流，配送含上楼但需确认电梯尺寸；偏远地区可能不上楼。",
                    "大件", "配送", "上楼"),
            doc("delivery_fee", "运费怎么算",
                    "单笔满 99 元包邮，未满收取 8 元运费；新疆西藏等地区加收 15 元偏远附加费。",
                    "运费", "包邮", "附加费"),

            // —— 营销与优惠（14） ——
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
            doc("new_user_gift", "新人礼包领取",
                    "新注册用户可领三张优惠券，有效期 7 天；同一手机号与设备仅可领取一次。",
                    "新人", "礼包", "优惠券"),
            doc("member_day", "会员日专享活动",
                    "每月 8 号为会员日，金卡会员额外 95 折，银卡 98 折，与满减可叠加。",
                    "会员日", "折扣", "专享"),
            doc("flash_discount", "限时折扣说明",
                    "限时折扣商品在活动期内按折后价结算，活动结束后恢复原价，不支持价保。",
                    "限时", "折扣", "活动"),
            doc("coupon_expire", "优惠券过期与补发",
                    "优惠券过期后不可使用；因平台原因导致无法使用的，可联系客服申请等额补偿。",
                    "优惠券", "过期", "补发"),
            doc("gift_item", "赠品规则",
                    "赠品随主商品一同发出，主商品退货时需一并退回赠品，赠品缺失按市价扣除。",
                    "赠品", "退货", "扣除"),
            doc("points_deduct", "积分抵现规则",
                    "结算时可用积分抵扣，100 积分抵 1 元，单笔最多抵扣订单金额的 20%。",
                    "积分", "抵扣", "上限"),
            doc("bundle_sale", "组合套餐优惠",
                    "组合套餐比单买便宜，套餐内商品不支持单独退换；退货需整单退回并扣除优惠。",
                    "套餐", "组合", "退货"),

            // —— 支付与发票（10） ——
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
            doc("payment_fail", "支付失败怎么办",
                    "支付失败通常是银行限额或网络问题，款项会在 24 小时内自动退回，可更换方式重试。",
                    "支付", "失败", "退回"),
            doc("balance_pay", "余额支付说明",
                    "账户余额由退款与充值产生，余额支付可享 99 折，单笔上限 5000 元，不可提现。",
                    "余额", "支付", "提现"),
            doc("payment_limit", "支付限额说明",
                    "单笔支付限额由支付渠道决定，一般单笔 5 万元、单日 10 万元，超出需分次支付。",
                    "限额", "支付", "渠道"),
            doc("refund_original", "退款为什么是原路返回",
                    "为防范洗钱与盗刷，退款只能原路返回至付款账户，不支持变更收款账户。",
                    "退款", "原路", "安全"),
            doc("tax_fee", "商品税费说明",
                    "页面价格已含税，跨境商品另附关税与行邮税，下单时会单独列示。",
                    "税费", "关税", "跨境"),
            doc("pay_later", "先用后付服务",
                    "符合条件的用户可先用后付，确认收货后 15 天内自动扣款，逾期会影响信用评分。",
                    "先用后付", "信用", "扣款"),

            // —— 会员与积分（8） ——
            doc("member", "会员等级与权益",
                    "普通会员累计消费满 1000 元升级银卡，享受包邮与专属客服；满 5000 元升级金卡。",
                    "会员", "等级", "权益"),
            doc("points", "积分获取与使用",
                    "每消费 1 元累计 1 积分，积分可在结算时抵扣现金，100 积分抵 1 元，有效期两年。",
                    "积分", "抵扣", "有效期"),
            doc("points_expire", "积分有效期与过期",
                    "积分自获得起两年内有效，按获得时间先后顺序消耗，过期部分不予补发。",
                    "积分", "过期", "有效期"),
            doc("growth_value", "成长值怎么算",
                    "成长值按实付金额累计，退款会扣减对应成长值；成长值只升不降，决定会员等级。",
                    "成长值", "会员", "退款"),
            doc("member_service", "专属客服通道",
                    "银卡及以上会员可在客服页走专属通道，平均响应时间小于 30 秒，7×24 小时在线。",
                    "会员", "客服", "专属"),
            doc("member_shipping", "会员包邮权益",
                    "银卡及以上会员全站包邮，不受 99 元门槛限制；偏远地区附加费仍需自理。",
                    "会员", "包邮", "权益"),
            doc("points_task", "积分任务",
                    "每日签到得 5 积分，评价晒图得 20 积分，邀请好友注册双方各得 100 积分。",
                    "积分", "任务", "签到"),
            doc("member_upgrade", "会员升级与降级",
                    "会员等级按自然年内累计消费判定，次年 1 月 1 日重新计算，不设降级。",
                    "会员", "升级", "周期"),

            // —— 账号与安全（8） ——
            doc("account_security", "账号安全说明",
                    "建议开启登录二次验证，异常登录会触发短信提醒；账号不支持转让与共享。",
                    "账号", "安全", "验证"),
            doc("privacy", "隐私与数据保护",
                    "平台仅在履约范围内使用个人信息，不会向第三方出售；用户可申请导出或删除数据。",
                    "隐私", "个人信息", "数据"),
            doc("real_name", "实名认证说明",
                    "购买跨境商品与使用先用后付需完成实名认证，认证信息仅用于合规校验。",
                    "实名", "认证", "合规"),
            doc("password_reset", "密码找回流程",
                    "忘记密码可通过绑定手机号或邮箱重置，重置链接 30 分钟内有效，仅可使用一次。",
                    "密码", "找回", "重置"),
            doc("abnormal_login", "异地登录提醒",
                    "检测到异地登录会发送短信并要求二次验证；非本人操作可一键冻结账号。",
                    "登录", "异常", "冻结"),
            doc("account_delete", "注销账号说明",
                    "账号可申请注销，注销前需结清所有订单与余额；注销后数据不可恢复，7 天冷静期。",
                    "注销", "账号", "数据"),
            doc("phone_bind", "更换绑定手机号",
                    "更换绑定手机需原手机号验证；原号已停用的可通过人工客服核验身份后更换。",
                    "手机", "绑定", "更换"),
            doc("third_party_login", "第三方账号登录",
                    "支持微信与支付宝快捷登录，首次登录需绑定手机号；解绑后需重新设置密码。",
                    "第三方", "登录", "绑定"),

            // —— 商品与选购（10） ——
            doc("guide", "百元耳机选购建议",
                    "学生党选择百元耳机时，优先看佩戴舒适度、麦克风通话清晰度和续航，通勤场景重视低延迟和抗风噪。",
                    "耳机", "学生党", "推荐"),
            doc("laptop_guide", "笔记本选购要点",
                    "日常办公优先看重量与续航，16GB 内存起步；涉及剪辑或建模需独显，散热规格比纸面参数更重要。",
                    "笔记本", "选购", "配置"),
            doc("phone_guide", "手机选购建议",
                    "选手机先定预算再看影像与续航，中端机芯片够用；注意辨别是否为库存机与演示机。",
                    "手机", "选购", "预算"),
            doc("size_chart", "尺码对照表怎么用",
                    "服饰类商品提供身高体重对照表，建议结合已有衣物实测数据选择，误差通常在一码以内。",
                    "尺码", "对照", "服饰"),
            doc("product_spec", "商品参数怎么读",
                    "参数页标注的是实验室条件下数据，实际体验受环境影响；重点关注标注了测试条件的项。",
                    "参数", "规格", "说明"),
            doc("stock_status", "库存状态说明",
                    "页面显示「现货」表示可当日发出，「预售」需等待到货，「缺货」可订阅到货提醒。",
                    "库存", "现货", "预售"),
            doc("product_compare", "商品对比功能",
                    "可将最多四件商品加入对比，系统高亮参数差异；同类目商品才支持对比。",
                    "对比", "参数", "选购"),
            doc("refurbished", "翻新机与官翻说明",
                    "官方翻新机为原厂检测维修后重新销售，享一年保修但价格更低，页面会明确标注。",
                    "翻新", "保修", "二手"),
            doc("custom_product", "定制商品流程",
                    "定制商品需先与客服确认方案，付款后进入生产，一般 7 到 15 天发货，不支持退换。",
                    "定制", "流程", "发货"),
            doc("off_shelf", "商品下架与缺货",
                    "商品下架后已支付的订单仍会正常发货；缺货订单可选择等待补货或全额退款。",
                    "下架", "缺货", "退款"),

            // —— 订单与评价（8） ——
            doc("order_cancel", "订单取消规则",
                    "未发货订单可直接取消并全额退款；已发货订单需拒收或签收后申请退货。",
                    "取消", "退款", "拒收"),
            doc("address_change", "收货地址修改",
                    "订单发货前可自助修改收货地址，已发货的需联系客服拦截，拦截失败则无法更改。",
                    "地址", "修改", "拦截"),
            doc("review", "评价规则",
                    "确认收货后可对商品评价，支持图文与视频；评价内容需真实，恶意差评将被折叠。",
                    "评价", "晒图", "差评"),
            doc("order_merge", "订单合并支付",
                    "同一店铺的多个待支付订单可合并结算，合并后优惠券按整单重新计算，可能不再满足门槛。",
                    "订单", "合并", "支付"),
            doc("additional_review", "追评说明",
                    "确认收货后 30 天内可追加一次评价，追评不可修改；追评内容同样计入商品评分。",
                    "追评", "评价", "时限"),
            doc("bad_review", "差评可以删吗",
                    "真实差评不支持删除；若评价含人身攻击或泄露隐私，可举报由平台审核处理。",
                    "差评", "删除", "举报"),
            doc("order_query", "订单查询方式",
                    "可在「我的订单」按状态筛选，支持按商品名与下单时间检索；历史订单保留三年。",
                    "订单", "查询", "筛选"),
            doc("price_change", "订单改价说明",
                    "商家改价仅适用于议价商品，改价后需在 24 小时内支付；平台自营订单不支持改价。",
                    "改价", "订单", "议价"),

            // —— 客服与投诉（6） ——
            doc("complaint", "投诉与举报渠道",
                    "对商家服务不满可在订单页发起投诉，平台 48 小时内介入；举报违规商品有奖励。",
                    "投诉", "举报", "介入"),
            doc("service_hours", "客服服务时间",
                    "在线客服 9:00 到 22:00，电话客服 9:00 到 18:00；会员专属通道 7×24 小时。",
                    "客服", "时间", "在线"),
            doc("human_service", "怎么转人工客服",
                    "在线客服页面连续输入两次「转人工」即可接入；高峰期需排队，可留言等待回电。",
                    "人工", "客服", "排队"),
            doc("ticket_progress", "工单进度查询",
                    "提交售后或投诉后会生成工单号，可在客服中心查看进度，状态变更会短信通知。",
                    "工单", "进度", "查询"),
            doc("service_promise", "服务承诺说明",
                    "平台承诺 48 小时内响应售后诉求，超时未处理将自动升级至高级客服并补偿优惠券。",
                    "承诺", "响应", "补偿"),
            doc("feedback", "意见反馈渠道",
                    "产品建议可通过「我的-意见反馈」提交，被采纳的建议会赠送积分，不逐一回复。",
                    "反馈", "建议", "积分")
    );

    // ==================== 标注查询（120 条，三档各 40） ====================

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
            new RetrievalEvaluator.EvalCase("保修期多久？", List.of("warranty")),
            new RetrievalEvaluator.EvalCase("分期付款手续费怎么算？", List.of("installment")),
            new RetrievalEvaluator.EvalCase("货到付款支持哪些城市？", List.of("cod")),
            new RetrievalEvaluator.EvalCase("拼团没成团会退款吗？", List.of("group_buy")),
            new RetrievalEvaluator.EvalCase("秒杀限购几件？", List.of("flash_sale")),
            new RetrievalEvaluator.EvalCase("价保怎么申请？", List.of("price_protection")),
            new RetrievalEvaluator.EvalCase("预售定金能退吗？", List.of("presale")),
            new RetrievalEvaluator.EvalCase("增值税专用发票怎么开？", List.of("invoice_type")),
            new RetrievalEvaluator.EvalCase("上门取件收费吗？", List.of("pickup_service")),
            new RetrievalEvaluator.EvalCase("退货运费谁出？", List.of("return_shipping")),
            new RetrievalEvaluator.EvalCase("订单怎么取消？", List.of("order_cancel")),
            new RetrievalEvaluator.EvalCase("收货地址能改吗？", List.of("address_change")),
            new RetrievalEvaluator.EvalCase("评价可以追评吗？", List.of("additional_review")),
            new RetrievalEvaluator.EvalCase("怎么投诉商家？", List.of("complaint")),
            new RetrievalEvaluator.EvalCase("客服几点上班？", List.of("service_hours")),
            new RetrievalEvaluator.EvalCase("怎么转人工客服？", List.of("human_service")),
            new RetrievalEvaluator.EvalCase("尺码对照表在哪看？", List.of("size_chart")),
            new RetrievalEvaluator.EvalCase("自提点怎么取件？", List.of("self_pickup")),
            new RetrievalEvaluator.EvalCase("大件商品怎么配送？", List.of("bulky_delivery")),
            new RetrievalEvaluator.EvalCase("运费满多少包邮？", List.of("delivery_fee")),
            new RetrievalEvaluator.EvalCase("新人礼包在哪领？", List.of("new_user_gift")),
            new RetrievalEvaluator.EvalCase("积分会过期吗？", List.of("points_expire")),
            new RetrievalEvaluator.EvalCase("怎么注销账号？", List.of("account_delete")),
            new RetrievalEvaluator.EvalCase("密码忘了怎么找回？", List.of("password_reset")),
            new RetrievalEvaluator.EvalCase("实名认证怎么弄？", List.of("real_name")),
            new RetrievalEvaluator.EvalCase("手机号怎么换绑？", List.of("phone_bind")),
            new RetrievalEvaluator.EvalCase("翻新机有保修吗？", List.of("refurbished")),
            new RetrievalEvaluator.EvalCase("商品缺货怎么办？", List.of("off_shelf")),
            new RetrievalEvaluator.EvalCase("订单怎么合并支付？", List.of("order_merge")),
            new RetrievalEvaluator.EvalCase("工单进度在哪查？", List.of("ticket_progress")),
            new RetrievalEvaluator.EvalCase("先用后付怎么扣款？", List.of("pay_later"))
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
            new RetrievalEvaluator.EvalCase("我想跟客服投诉商家", List.of("complaint")),
            new RetrievalEvaluator.EvalCase("东西寄回去的邮费谁掏", List.of("return_shipping")),
            new RetrievalEvaluator.EvalCase("上门来收要花钱不", List.of("pickup_service")),
            new RetrievalEvaluator.EvalCase("买的衣服尺码不对能换不", List.of("exchange", "size_chart")),
            new RetrievalEvaluator.EvalCase("修东西要自己出邮费吗", List.of("repair_process")),
            new RetrievalEvaluator.EvalCase("国外买的东西能退吗", List.of("cross_border_return")),
            new RetrievalEvaluator.EvalCase("自己刻字的能退不", List.of("custom_goods_return")),
            new RetrievalEvaluator.EvalCase("原包装扔了还能退吗", List.of("return_packaging")),
            new RetrievalEvaluator.EvalCase("售后在哪申请", List.of("after_sale_entry")),
            new RetrievalEvaluator.EvalCase("能指定顺丰吗", List.of("express_choice")),
            new RetrievalEvaluator.EvalCase("快递放驿站行不行", List.of("self_pickup")),
            new RetrievalEvaluator.EvalCase("周末能送货吗", List.of("delivery_appointment")),
            new RetrievalEvaluator.EvalCase("物流一直不动", List.of("logistics_tracking")),
            new RetrievalEvaluator.EvalCase("能快点送到吗", List.of("urgent_delivery")),
            new RetrievalEvaluator.EvalCase("寄到新疆要加钱吗", List.of("delivery_fee", "delivery_range")),
            new RetrievalEvaluator.EvalCase("上次买贵了能补差价吗", List.of("price_protection")),
            new RetrievalEvaluator.EvalCase("会员日是哪天", List.of("member_day")),
            new RetrievalEvaluator.EvalCase("打折的东西还会再降吗", List.of("flash_discount")),
            new RetrievalEvaluator.EvalCase("券过期了能补吗", List.of("coupon_expire")),
            new RetrievalEvaluator.EvalCase("送的小东西要退回去吗", List.of("gift_item")),
            new RetrievalEvaluator.EvalCase("买一整套能便宜吗", List.of("bundle_sale")),
            new RetrievalEvaluator.EvalCase("付款没成功钱扣了", List.of("payment_fail")),
            new RetrievalEvaluator.EvalCase("账户里的钱能提出来吗", List.of("balance_pay")),
            new RetrievalEvaluator.EvalCase("一次最多能付多少钱", List.of("payment_limit")),
            new RetrievalEvaluator.EvalCase("为什么不能退到别的卡", List.of("refund_original")),
            new RetrievalEvaluator.EvalCase("价格含税吗", List.of("tax_fee")),
            new RetrievalEvaluator.EvalCase("会员有什么好处", List.of("member", "member_shipping")),
            new RetrievalEvaluator.EvalCase("积分怎么攒", List.of("points_task")),
            new RetrievalEvaluator.EvalCase("在别的地方登录了", List.of("abnormal_login")),
            new RetrievalEvaluator.EvalCase("能用微信直接登吗", List.of("third_party_login")),
            new RetrievalEvaluator.EvalCase("提点建议有用吗", List.of("feedback"))
    );

    /**
     * 语义鸿沟：口语化短句，且查询用词与目标文档<b>几乎无字面交集</b>。
     * <p>
     * 这组是纯关键词检索的天花板所在——也是引入向量检索最直接的依据。
     * <p>
     * <b>难度还来自同主题的多篇竞争</b>：如「太贵了」的语义邻域里有促销、优惠券、
     * 会员折扣、价保四篇都在讲"更便宜"，命中特定那篇需要的不只是"语义相近"。
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
            new RetrievalEvaluator.EvalCase("会不会泄露我的信息", List.of("privacy")),
            new RetrievalEvaluator.EvalCase("寄过来是破的", List.of("logistics_damage")),
            new RetrievalEvaluator.EvalCase("我没收到货", List.of("logistics_tracking")),
            new RetrievalEvaluator.EvalCase("能不能送到楼下", List.of("bulky_delivery")),
            new RetrievalEvaluator.EvalCase("先寄到门卫那儿", List.of("self_pickup")),
            new RetrievalEvaluator.EvalCase("着急用", List.of("urgent_delivery")),
            new RetrievalEvaluator.EvalCase("会不会再便宜", List.of("flash_discount")),
            new RetrievalEvaluator.EvalCase("囤着以后用", List.of("presale")),
            new RetrievalEvaluator.EvalCase("拼单还差人", List.of("group_buy")),
            new RetrievalEvaluator.EvalCase("抢不到", List.of("flash_sale")),
            new RetrievalEvaluator.EvalCase("学生证有什么用", List.of("student_discount")),
            new RetrievalEvaluator.EvalCase("想分期", List.of("installment")),
            new RetrievalEvaluator.EvalCase("钱不够", List.of("pay_later")),
            new RetrievalEvaluator.EvalCase("重复付了两次", List.of("payment_fail")),
            new RetrievalEvaluator.EvalCase("开个公司能报销的", List.of("invoice_type")),
            new RetrievalEvaluator.EvalCase("这个多少钱来着", List.of("order_query")),
            new RetrievalEvaluator.EvalCase("买多了", List.of("order_merge")),
            new RetrievalEvaluator.EvalCase("能不能便宜卖我", List.of("price_change")),
            new RetrievalEvaluator.EvalCase("想让别人也看看", List.of("review")),
            new RetrievalEvaluator.EvalCase("说了实话被删了", List.of("bad_review")),
            new RetrievalEvaluator.EvalCase("忘了用券", List.of("coupon_expire")),
            new RetrievalEvaluator.EvalCase("发的什么快递", List.of("express_choice")),
            new RetrievalEvaluator.EvalCase("怎么证明是正品", List.of("fake_goods")),
            new RetrievalEvaluator.EvalCase("换新的要等多久", List.of("exchange")),
            new RetrievalEvaluator.EvalCase("钱退到哪儿了", List.of("refund_method")),
            new RetrievalEvaluator.EvalCase("地址填错了", List.of("address_change")),
            new RetrievalEvaluator.EvalCase("怎么知道发货了", List.of("logistics_tracking")),
            new RetrievalEvaluator.EvalCase("等级怎么算的", List.of("growth_value")),
            new RetrievalEvaluator.EvalCase("怎么证明是我", List.of("real_name")),
            new RetrievalEvaluator.EvalCase("不想让你们留我数据", List.of("account_delete")),
            new RetrievalEvaluator.EvalCase("催一下进度", List.of("ticket_progress"))
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
     * 单篇相关文档时，随机取 K 篇至少命中一篇的概率是 {@code 1 - C(n-1, K)/C(n, K)}。
     * <b>必须逐例按各自的相关文档数算再取平均</b>：本夹具里有若干样本标了 2 篇相关文档
     * （如「优惠券能和满减一起用吗」对应 coupon 与 promotion），套用单文档公式会低估基线。
     * <p>
     * （早先这里的注释把公式写成了 {@code 1 - C(n-K,K)/C(n,K)}，代入 30/3 得 0.28，
     * 与代码实际返回的 0.1 对不上——代码是对的，注释是错的。注释里的推导错误比代码错误更危险，
     * 因为它会让后来者按错误前提去推理。）
     */
    static double randomBaselineHitRate(int corpusSize, int topK) {
        return randomBaselineHitRate(corpusSize, topK, allCases());
    }

    static double randomBaselineHitRate(int corpusSize, int topK, List<RetrievalEvaluator.EvalCase> cases) {
        if (cases.isEmpty()) {
            return 0.0;
        }
        double sum = 0;
        for (RetrievalEvaluator.EvalCase evalCase : cases) {
            int relevant = Math.max(1, evalCase.relevantDocIds().size());
            sum += hitProbability(corpusSize, topK, relevant);
        }
        return sum / cases.size();
    }

    /** 随机取 topK 篇，至少命中一篇相关文档的概率。 */
    private static double hitProbability(int corpusSize, int topK, int relevant) {
        if (relevant >= corpusSize) {
            return 1.0;
        }
        // 全部落空的概率 = 从 非相关 里取出 topK 篇 / 从全量里取出 topK 篇
        double miss = 1.0;
        for (int i = 0; i < topK; i++) {
            miss *= (double) (corpusSize - relevant - i) / (corpusSize - i);
            if (miss <= 0) {
                return 1.0;
            }
        }
        return 1.0 - miss;
    }

    private static Document doc(String id, String title, String content, String... tags) {
        return Document.builder().id(id).title(title).content(content).tags(List.of(tags)).build();
    }
}
