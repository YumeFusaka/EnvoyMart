package yumefusaka.envoymart.aiservice.flow;

import lombok.extern.slf4j.Slf4j;
import yumefusaka.envoymart.agent.flow.DeterministicFlow;
import yumefusaka.envoymart.agent.flow.FlowContext;
import yumefusaka.envoymart.agent.flow.FlowResult;
import yumefusaka.envoymart.agent.tool.ToolCall;
import yumefusaka.envoymart.agent.tool.ToolResult;
import yumefusaka.envoymart.aiservice.client.OrderClient;
import yumefusaka.envoymart.aiservice.tool.Money;
import yumefusaka.envoymart.contract.AfterSalePreview;
import yumefusaka.envoymart.contract.OrderItemResponse;
import yumefusaka.envoymart.contract.OrderResponse;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 售后引导 —— 针对具体订单的退货资格判定与操作指引。
 * <p>
 * 为什么值得做成确定性流程：步骤是固定的，交给模型即兴推理会引入不确定性 ——
 * <b>模型可能编造一个不存在的退货期限</b>。
 * <p>
 * <b>但「判定规则」不在这个类里。</b>退货期限、类目政策、可退比例由 order-service 的
 * {@code AfterSalePolicyEngine} 说了算，这里通过 {@code /after-sales/preview} 取结论、
 * 只负责把它说成人话。原先这个类自己写了一条规则 —— 实际等价于「只要不是已取消就能退」——
 * 于是同一个订单，页面上说不符合条件、Agent 却说可以退且报得出步骤；未支付的订单被引导去退货，
 * 已退款完成的订单被再讲一遍退货步骤。<b>判定只该有一个来源</b>：用户按页面说的做还是按 Agent 说的做，
 * 这个疑问本身就已经是故障了。
 * <p>
 * 有几类状态连问都不必问：流程已结束（取消 / 关闭 / 已退款 / 退款中）与尚未付款的订单。
 * 对它们，政策引擎只会回一句「订单尚未收货」，答非所问。
 */
@Slf4j
public class AfterSaleFlow implements DeterministicFlow {

    /**
     * 订单引用：订单号必须<b>紧跟在订单标识词之后</b>，中间最多 3 个非数字字符。
     * <p>
     * 不用「消息里任意数字」——那样"你们退货政策第 3 条是什么"会被误判成查询订单 3，
     * 然后答非所问地返回某个订单的退货步骤。
     */
    private static final Pattern ORDER_REFERENCE =
            Pattern.compile("(?:订单|单号|编号|order)\\D{0,3}(\\d{1,19})", Pattern.CASE_INSENSITIVE);

    private static final Set<String> INTENT_KEYWORDS =
            Set.of("退货", "换货", "退款", "售后", "退掉", "退了");

    private static final String STATUS_CREATED = "CREATED";
    private static final String STATUS_CANCELLED = "CANCELLED";
    private static final String STATUS_CLOSED = "CLOSED";
    private static final String STATUS_REFUNDING = "REFUNDING";
    private static final String STATUS_REFUNDED = "REFUNDED";

    /** 政策类型，取值与 order-service 的 {@code AfterSalePolicyEngine} 一致 */
    private static final String TYPE_RETURN_REFUND = "RETURN_REFUND";
    private static final String TYPE_REFUND_ONLY = "REFUND_ONLY";
    private static final String TYPE_EXCHANGE = "EXCHANGE";

    private final OrderClient orderClient;

    public AfterSaleFlow(OrderClient orderClient) {
        this.orderClient = orderClient;
    }

    @Override
    public String getName() {
        return "after_sale";
    }

    @Override
    public String getDescription() {
        return "用户想对**某个具体订单**申请退货、换货、退款或咨询该订单的售后处理时使用。"
                + "前提是用户消息里明确给出了订单号（如「订单 3」「单号 12」）。"
                + "如果用户只是在问售后政策本身（如「退货政策是什么」「七天无理由怎么算」），"
                + "或者没有给出任何订单号，都不要匹配这条流程。";
    }

    /**
     * 规则判定：售后意图 <b>且</b> 明确的订单引用，两者必须同时满足。
     * <p>
     * 宁可漏判不可误判——漏判还有执行图接着（最多追问一句订单号），
     * 误判会让用户拿到答非所问的结果。
     */
    @Override
    public boolean matches(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return false;
        }
        boolean hasIntent = INTENT_KEYWORDS.stream().anyMatch(userMessage::contains);
        return hasIntent && extractOrderId(userMessage) != null;
    }

    @Override
    public FlowResult execute(FlowContext context) {
        String message = context.getUserMessage();
        Long orderId = extractOrderId(message);
        log.info("[AfterSaleFlow] userId={} orderId={}", context.getUserId(), orderId);

        ToolResult query = context.getToolRegistry().execute(new ToolCall(
                UUID.randomUUID().toString(), "order_query",
                Map.of("orderId", orderId), true, context.getUserId()));

        if (!query.isSuccess() || !(query.getRawData() instanceof OrderResponse order)) {
            String reason = query.getErrorMessage() == null ? "未查询到订单" : query.getErrorMessage();
            return FlowResult.builder()
                    .success(false)
                    .output("我没能查到订单 " + orderId + "：" + reason + "。请确认订单号是否正确。")
                    .build();
        }

        return evaluate(order, message, context);
    }

    /**
     * 先说流程状态，再问政策引擎。
     * <p>
     * 两段的顺序不能反：政策引擎的输入是订单行，它对「这单还没付款」没有话说，
     * 只能回一句「订单尚未收货」——而用户真正需要知道的是「不用退货，直接取消就行」。
     */
    private FlowResult evaluate(OrderResponse order, String message, FlowContext context) {
        String status = order.getStatus() == null ? "" : order.getStatus();
        // 展示用中文状态：给用户看的文本里出现 PAID / DELIVERING 这种枚举名，用户得自己翻译
        String statusLabel = order.getStatusText() == null || order.getStatusText().isBlank()
                ? status : order.getStatusText();

        String settled = settledReply(order, status);
        if (settled != null) {
            return FlowResult.builder().success(true).data(order).output(settled).build();
        }

        if (order.getItems() == null || order.getItems().isEmpty()) {
            // 订单没有明细行 —— 数据不自洽。不猜，也不假装能判定。
            return FlowResult.builder()
                    .success(true)
                    .data(order)
                    .output("订单 " + order.getOrderNo() + "（" + statusLabel + "）没有商品明细，"
                            + "我无法判定售后资格。请在订单详情页发起申请，或联系人工客服。")
                    .build();
        }

        OrderItemResponse item = order.getItems().get(0);
        AfterSalePreview preview = preview(context.getUserId(), order, item, resolveType(message));
        if (preview == null) {
            // 政策服务不可用 ≠ 不能退。让用户去页面申请，而不是替他下结论。
            log.warn("[AfterSaleFlow] 售后判定不可用 orderId={} itemId={}", order.getId(), item.getId());
            return FlowResult.builder()
                    .success(true)
                    .data(order)
                    .output("订单 " + order.getOrderNo() + "（" + statusLabel + "）的售后资格我暂时查不到，"
                            + "先不给你结论。请在订单详情页点「申请退货」，那里会给出判定和可退金额。")
                    .build();
        }

        String itemName = item.getSpuName() == null || item.getSpuName().isBlank()
                ? "第 1 件商品" : item.getSpuName();
        return FlowResult.builder()
                .success(true)
                .data(order)
                .output(render(order, item, itemName, statusLabel, preview))
                .build();
    }

    /** 流程已结束或尚未付款的状态：这些与商品能不能退无关，先给结论再谈其他。 */
    private String settledReply(OrderResponse order, String status) {
        String no = order.getOrderNo();
        return switch (status) {
            case STATUS_CANCELLED -> "订单 " + no + " 已经是取消状态，无需再申请退货。"
                    + "如果已支付，退款会在 1~3 个工作日原路退回。";
            case STATUS_CLOSED -> "订单 " + no + " 已关闭（超时未支付），没有产生需要退回的款项，"
                    + "重新下单即可。";
            case STATUS_REFUNDING -> "订单 " + no + " 的退款正在处理中，不用重复申请。"
                    + "退款到账约 1~3 个工作日，期间可以在订单详情页查看进度。";
            case STATUS_REFUNDED -> "订单 " + no + " 已经退款完成，无需再申请。"
                    + "如果对退款金额有疑问，可以告诉我具体金额，我帮你核对。";
            // 还没付款的订单走的是「取消」，不是「退货」——两件事的入口和后果都不同
            case STATUS_CREATED -> "订单 " + no + " 还没支付，不需要走退货流程。"
                    + "如果不想买了，直接取消订单就行（这一步不可撤销，需要你确认后才会执行）。";
            default -> null;
        };
    }

    /**
     * 问政策引擎要结论。
     * <p>
     * 失败返回 {@code null} 而不是抛：售后引导是增强项，判定服务抖动不该让整轮对话变成错误。
     */
    private AfterSalePreview preview(String userId, OrderResponse order, OrderItemResponse item, String type) {
        try {
            return orderClient.previewAfterSale(userId, item.getId(), type, false).getData();
        } catch (Exception e) {
            log.warn("[AfterSaleFlow] 调用售后判定失败 orderId={} itemId={}: {}",
                    order.getId(), item.getId(), e.getMessage());
            return null;
        }
    }

    /**
     * 从用户这句话里定「申请哪种售后」。
     * <p>
     * 必须定，因为政策是<b>按类型</b>配的（同一类目下仅退款与换货的期限可以不同），
     * 而拿退货的政策去回答换货的问题，报出来的数字就是错的。
     * 只做分词级别的判断，拿不准一律按退货退款——那是「我要退」的默认语义。
     */
    private String resolveType(String message) {
        if (message == null) {
            return TYPE_RETURN_REFUND;
        }
        if (message.contains("换货") || message.contains("换一个")) {
            return TYPE_EXCHANGE;
        }
        // 「仅退款」才是只要钱不要货；单说「退款」在口语里常常等于退货退款
        if (message.contains("仅退款") || (message.contains("退款") && !message.contains("退货"))) {
            return TYPE_REFUND_ONLY;
        }
        return TYPE_RETURN_REFUND;
    }

    /** 把判定结论说成一段用户能直接照做的话。 */
    private String render(OrderResponse order, OrderItemResponse item, String itemName,
                          String statusLabel, AfterSalePreview preview) {
        StringBuilder sb = new StringBuilder();
        // 名字里带商品而不是只说订单号：一个订单可以有多行，多行各有各的判定
        sb.append("订单 ").append(order.getOrderNo())
                .append("（").append(statusLabel).append("）里的「").append(itemName).append("」");

        if (!preview.isEligible()) {
            sb.append("目前不能申请售后：「").append(preview.getReason()).append("」。");
            appendDocRef(sb, preview.getDocRef());
            if (order.getItems().size() > 1) {
                sb.append("\n这个订单有多件商品，其余几件的判定可能不同，可以告诉我具体是哪一件。");
            }
            return sb.toString();
        }

        sb.append("可以申请售后，最多可退 ").append(Money.yuan(preview.getMaxRefundAmount()));
        if (preview.getItemSubtotal() != null && preview.getMaxRefundAmount() != null
                && !preview.getMaxRefundAmount().equals(preview.getItemSubtotal())) {
            // 只有两个数不一样时才并列：相同时并列只是重复，还会让用户以为差价被吞了
            sb.append("（这件商品实付 ").append(Money.yuan(preview.getItemSubtotal())).append("）");
        }
        sb.append("。");
        if (preview.getRequirements() != null && !preview.getRequirements().isBlank()) {
            sb.append("\n注意：").append(preview.getRequirements()).append("。");
        }
        appendDocRef(sb, preview.getDocRef());

        sb.append("\n步骤如下：\n")
                .append("1. 在订单详情页点击「申请退货」，选择退货原因；\n")
                .append("2. 保持商品与包装完整，定制类和贴身个护商品不支持；\n")
                .append("3. 提交后快递员上门取件，质量问题的运费由平台承担；\n")
                .append("4. 仓库验收通过后退款原路返回，约 1~3 个工作日到账。");
        if (order.getItems().size() > 1) {
            sb.append("\n这个订单有多件商品，上面是这一件的判定，其余几件可以单独问我。");
        }
        return sb.toString();
    }

    /** 政策出处：判定是规则给的，规则出自哪份文档要能指出来，否则用户无处复核。 */
    private void appendDocRef(StringBuilder sb, String docRef) {
        if (docRef != null && !docRef.isBlank()) {
            sb.append("\n依据政策：").append(docRef).append("（可以让我把原文找出来）。");
        }
    }

    /** 只为后续业务逻辑提取订单号；提取不到不影响语义判断（那是 IntentRouter 的职责）。 */
    public Long extractOrderId(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher matcher = ORDER_REFERENCE.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        try {
            return Long.valueOf(matcher.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
