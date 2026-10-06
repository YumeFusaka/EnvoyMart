package yumefusaka.envoymart.aiservice.model;

import lombok.Builder;
import lombok.Data;
import yumefusaka.envoymart.agent.rag.EvidenceGate;
import yumefusaka.envoymart.contract.ProductSummary;

import java.util.List;

@Data
@Builder(toBuilder = true)
public class ChatResponse {

    private String sessionId;
    private String reply;

    /**
     * 本轮检索<b>实际使用的查询句</b>——仅在发生指代消解改写、且结果与用户原话不同时下发。
     * <p>
     * 追问句「那它呢」原样去检索什么都召不回。界面上有这一句，用户才能看懂这一轮
     * 凭什么给（或不给）证据：答案里的每个引用都能追到它是拿哪个查询检回来的。
     * 判定与改写规则见 {@code QueryRewriter}（首轮不改写、模型无推理能力时不改写、
     * 改写失败一律退回原句）。
     */
    private String retrievalQuery;

    /**
     * 本轮<b>检索扩写</b>实际生效的变体（假想答案 HyDE + 角度改写）。
     * <p>
     * <b>与 {@link #retrievalQuery} 不是一回事，也不能合并。</b>那一项回答「本轮拿哪句话去检索」，
     * 指的是指代消解改写后的最终查询；它回答「检索之前，这句话被扩写成什么」。
     * 用户整句问「太贵了怎么办」时，前者仍是原话，后者才有内容——
     * <b>只看 retrievalQuery，扩写到底跑没跑、跑出了什么，界面上永远看不出来。</b>
     * <p>
     * 这是检索调优的唯一观测口：扩写覆盖率、角度变体质量、HyDE 与角度的增益各占多少，
     * 都得靠它才能逐轮核对。没有它，调召回率只能靠端到端分数反推，
     * 「某一类问题扩写没生效」这类缺陷会被整体分数掩盖。
     * <p>
     * 为 {@code null} 表示本轮没有扩写——降级路径（模型超时、开关关闭、查询已足够清晰）
     * 与「扩写跑了但产出为空」在界面上都不显示，但两者的处置完全不同，
     * 所以由 {@link ExpansionView#textOnly()} 显式区分，而不是靠 null 猜。
     */
    private ExpansionView expansion;

    /**
     * 本轮<b>记忆注入</b>的事实：画像灌了几槽、情节记忆召回几条、各是什么类型。
     * <p>
     * <b>与 {@link #expansion} 同属「可观测性」，但补的是另一个盲区。</b>扩写那一栏让
     * 「检索前发生了什么」可见；这一栏让「回答前，系统记不记得这个用户」可见。
     * 在此之前，记忆注入没有任何对外观测口——「这轮到底注入了没有」只能翻日志。
     * <p>
     * <b>冷启动的新用户读数是 0，而不是 null。</b>「这一轮查过、确实没有可注入的记忆」
     * 与「这一轮根本没去查记忆」是两件事：前者是新用户正常的起点，后者是链路断了。
     * 用 null 表达前者会让这两种情况在界面上长得一样。
     */
    private MemoryTraceView memoryTrace;

    /**
     * 本轮的请求标识 —— 与后台日志里的 {@code [requestId]} 是同一个值。
     * <p>
     * 下发给界面是为了让「用户报障」这件事可操作：他说得出「刚才那次回答不对」，
     * 但说不出时间；界面上有这一串，就能直接定位到日志里的那一段，
     * 包括它经过了哪些服务、调了哪些工具、花了多少 token。
     * <p>
     * 它是一个<b>关联字段，不是凭证</b>：拿到它不获得任何权限。
     */
    private String requestId;
    private List<KnowledgeSnippet> knowledge;
    private List<ToolCallResponse> toolCalls;
    private List<ProductSummary> recommendedProducts;

    /**
     * 本轮产生的待支付订单，前端据此渲染支付卡片。
     * <p>
     * <b>为什么需要它，而不是让前端从正文里认。</b>下单成功时正文里确实写着
     * 「单号 YS…，应付 268.00 元」，但那段话是模型写的，格式每轮都可能变；
     * 前端要从里面可靠地抽出订单号，只能写正则在自由文本上碰运气。
     * 而支付是一次真实跳转（{@code /payment?orderNo=…}），抽错了会把用户带到
     * 别人的订单或一个不存在的页面。<b>订单号是确定的事实，该由服务端以结构化字段交付。</b>
     * <p>
     * 只在「这一轮真的创建了待支付订单」时非空——已支付的订单不该再弹一张支付卡。
     */
    private List<PendingPayment> pendingPayments;

    /**
     * 等待用户确认的高危操作，前端据此渲染确认卡片。
     * <p>
     * 每项是可直接展示的中文描述，形如 {@code order_cancel(orderId=12)}——
     * 不只是工具名。用户看不到要取消的是哪一单时的「确认」，等于没确认。
     * 非空表示本轮对话<b>被中断</b>、{@code reply} 是确认提示而非回答。
     * <p>
     * <b>它是给人看的，执行不认它。</b>用户点确认时前端要原样带回的是
     * {@link #approvalToken}——真正执行什么写在令牌的签名载荷里，
     * 这两者由服务端在同一次响应里一起生成，前端无权改动其中任何一项。
     */
    private List<String> pendingActions;

    /**
     * 确认令牌：下发给前端，用户点确认时原样带回，服务端据此执行签名里的那批调用。
     * <p>
     * 与 {@link #pendingActions} 同时非空、同时为空。它替代了原先的请求级布尔
     * {@code approved=true}：布尔不绑定动作也不绑定会话，用户批的到底是不是执行的那一次，
     * 全看模型重入时有没有从记忆里把意图回忆出来。
     */
    private String approvalToken;

    /**
     * 本轮证据门的判定，决定 {@link #knowledge} 该被说成什么。
     * <p>
     * {@code SUFFICIENT} 才是「依据」；{@code WEAK} 表示检索到了但相关度不足，
     * 只能是「相关度不足的线索」——system prompt 那一侧对同一批切片下的正是这个结论
     * （见 {@code KnowledgePrompt} 的 weak 分支），界面若照旧标题「依据 N 条」，
     * 就等于对模型说「别信」、对用户说「这是依据」。
     * <p>
     * 判定含「重排分与余弦相似度用两把尺子」「图谱依据在场时豁免」这类内部规则，
     * 由后端算好下发，前端不重复实现。
     */
    private EvidenceGate.Level evidenceLevel;
    /**
     * 本轮任务阶段，见 agent-core 的 {@code TaskStage}。
     * <p>
     * 以<b>字符串</b>下发而不是枚举：这是跨服务与跨端的契约，枚举名一旦重命名，
     * 编译期检查不到前端与外部调用方。字符串至少能让「不认识的取值」被显式识别，
     * 而不是静默反序列化失败。
     */
    private String stage;

    /**
     * 本轮任务状态 —— 意图、当前子任务、待执行调用、已完成步骤、阶段、轮次。
     * <p>
     * <b>为什么在 stage 之外还要下发它。</b>stage 只回答「在哪个阶段」；
     * 「这一轮到底在做什么、做到哪了、还在等哪几个调用」是刷新页面与跨轮续接同一任务
     * 要读的账。前端若各自用 stage 去猜这些，猜错了没有任何地方会报错。
     * <p>
     * 字段名沿 agent-core {@code TaskState} 的对外契约（下划线风格），
     * 不随 Java 侧驼峰偏好改。非任务路径（确定性流程、直接对话）为 null。
     */
    private java.util.Map<String, Object> taskState;

    /**
     * 讲了一条事实却没交代出处、已从 {@link #reply} 中剔除的句子。
     * <p>
     * 剔除了却仍然下发：用户该看到「回答里少了什么、为什么少」。静默删除是更坏的选择——
     * 既没让他读到不可靠的内容，也没让他知道系统替他兜了底。
     * <p>
     * <b>有内容不等于都被剔除了</b>：整篇没有一处有效引用时，逐句剔会把回答砍碎，
     * 此时只报告不剔除，这些句子仍在 {@link #reply} 里。判定规则见 {@code CitationVerifier}。
     */
    private List<String> unsupportedClaims;

    /**
     * {@link #unsupportedClaims} 是否已被移出 {@link #reply}。
     * <p>
     * 必须与列表一起下发：<b>「已经替你拿掉了」和「还留在上面，你自己判断」是两件事</b>，
     * 用同一句话去描述会把后者说成前者——那正是这道闸最不该犯的错。
     */
    private boolean unsupportedStripped;

    /**
     * 整篇回答<b>没有任何依据</b>——没有知识库引用，也没有工具执行记录，
     * 内容是模型凭自身知识生成的。
     * <p>
     * 与 {@link #unsupportedClaims} 是两级粒度：后者点名「哪几句」讲事实没出处，
     * 是一条精准的修订；它是「这一整段都没有平台依据」，是一句免责声明。
     * 两者互斥：有引用时按句报，一个引用也没有、也没有工具执行时才整篇报。
     * <p>
     * 这个信号必须由后端下发而不能由前端推：判据涉及「本轮有没有执行过工具」，
     * 前端只看得到回答正文。
     */
    private boolean ungrounded;

    /**
     * 与工具当场返回的事实对不上、已从 {@link #reply} 中剔除的说明。
     * <p>
     * 与 {@link #unsupportedClaims} 是两种病：那些句子是「没有出处」，这些是「有出处但说错了」——
     * 工具明明返回「应付金额 ¥128.00」，回答里写成别的数。订单类问题走的是工具而不是知识库，
     * 这两句在引用上完全站得住，只有拿工具的返回值去对才看得出来。
     * <p>
     * 剔除规则见 {@code ToolFactVerifier}，它的类注释里写着这道闸刻意留的漏检口子——
     * 类注释同时说明了为什么标签必须无歧义：误删一句正确的话，比漏检严重得多。
     */
    private List<String> factMismatches;

    /** {@link #factMismatches} 是否已被移出 {@link #reply}，语义同 {@link #unsupportedStripped} */
    private boolean factStripped;

    /**
     * 本轮证据之间被发现的矛盾——同一件事在不同文档里有不同说法。
     * <p>
     * 后端不让模型自己挑一个讲，而是要求它把矛盾列出来；这里把那段文字抽成结构化字段，
     * 前端才能渲染成一张能点回原文的卡片（{@link Conflict#refs()} 就是回答里
     * {@code [n]} 的 n，可直接跳转）。见 {@code ConflictReporter}。
     */
    private List<Conflict> conflicts;

    /**
     * @param refs   涉及的证据编号（1 基），可能为空——认不出编号时不猜，只展示文字
     * @param detail 冲突原文
     */
/**
     * @param refs     涉及的证据编号（1 基），可能为空——认不出编号时不猜，只展示文字
     * @param detail   冲突原文
     * @param resolved 能否依据版本信息定夺：{@code true} 表示已按较新版本给出结论，
     *                 {@code false} 表示无法判定先后、必须人工确认。
     *                 前端据此分两种形态渲染——「已定夺」是一条普通提示，
     *                 「待人工确认」才需要用户点开原文自己拿主意
     */
    public record Conflict(List<Integer> refs, String detail, boolean resolved) {
    }

    /**
     * 本轮检索扩写的观测视图。
     * <p>
     * {@code applied} 表示「扩写这一步确实运行过并且产出了内容」，与「产出为空」是两件事：
     * 关闭功能、模型超时降级、查询已足够清晰被判定无需扩写——三者都得到空产出，
     * 但只有第一种是用户的选择，后两种是系统的降级或判断，排查时不能混为一谈。
     *
     * @param hypothetical 假想答案（HyDE）原文；无则为 null
     * @param angles       角度改写变体；无则为空列表
     * @param applied      是否真的产出了扩写（{@code hypothetical} 或 {@code angles} 至少一项非空）
     */
    public record ExpansionView(String hypothetical, List<String> angles, boolean applied) {

        /** 无扩写。降级路径与「判定无需扩写」共用这一个落点 */
        public static ExpansionView textOnly() {
            return new ExpansionView(null, List.of(), false);
        }
    }

    /**
     * 记忆注入的观测视图。
     * <p>
     * <b>它不是「记忆有几条」的展示，是「记忆这条路这一轮有没有真的跑、跑出了什么」的证据。</b>
     * 两类数各有各的诊断价值：
     * <ul>
     *   <li>{@code profileSlots} 为 0 而 {@code recalled} 也 0 —— 新用户冷启动，正常；</li>
     *   <li>{@code profileSlots} 长期为 0 而用户已经聊过很多轮 —— 画像抽取那一步没产出，
     *       是故障信号；</li>
     *   <li>{@code byType} 清一色 {@code MESSAGE} —— 召回回来的全是原始对话记录，
     *       说明事实抽取与摘要压缩都没有沉淀出更凝练的条目。</li>
     * </ul>
     *
     * @param profileSlots 注入的用户画像槽位数
     * @param recalled     召回的情节记忆条数
     * @param byType       召回条目按类型计数
     * @param itemIds      召回条目的 id，便于按 id 回查是哪几条
     * @param observationSources 本轮注入的感知观测来源；空是正常的（用户没开商品页）
     */
    public record MemoryTraceView(int profileSlots, int recalled,
                                  java.util.Map<String, Integer> byType,
                                  List<String> itemIds,
                                  List<String> observationSources) {

        public static MemoryTraceView empty() {
            return new MemoryTraceView(0, 0, java.util.Map.of(), List.of(), List.of());
        }
    }

    /**
     * 本轮对话的模型用量与估算花费。
     * <p>
     * <b>为什么要下发到界面。</b>Agent 的每一次「多想一步」都是一次真实计费的调用——
     * 一次带工具的问题可能是 5 到 8 次模型往返。用户付的是这笔钱，看不见它，
     * 就无从判断「这个助手值不值」，也无从判断某次回答为什么慢。
     * <p>
     * <b>它统计的是整轮，不是最后一次调用。</b>计划、ReAct 的每一圈、收口合成、
     * 记忆沉淀、检索的向量化与重排，只要在本轮发生就计入——这些是同一笔开销的组成部分，
     * 只报其中一段会得到一个看着精确、实际偏小几倍的数。
     * <p>
     * <b>金额是估价。</b>单价来自配置（见 {@code ModelPricing}），没配单价的模型只计
     * token 不计钱，且它们的名字会出现在 {@link #unpricedModels} 里——
     * 缺了哪一部分要说出来，否则「总价」会被当成完整账单。
     */
    private Usage usage;

    /**
     * @param promptTokens     输入 token 合计
     * @param completionTokens 输出 token 合计
     * @param totalTokens      两者之和
     * @param costCny          估算金额（元）；为 {@code null} 表示本轮没有任何模型配了单价
     * @param unpricedModels   本轮用到但没配单价的模型名；非空时前端要说明金额不完整
     * @param models           按模型拆开的明细
     */
    public record Usage(long promptTokens, long completionTokens, long totalTokens,
                        Double costCny, List<String> unpricedModels, List<ModelUsage> models) {
    }

    /**
     * @param model            模型名
     * @param promptTokens     输入 token
     * @param completionTokens 输出 token
     */
    public record ModelUsage(String model, long promptTokens, long completionTokens) {
    }
}
