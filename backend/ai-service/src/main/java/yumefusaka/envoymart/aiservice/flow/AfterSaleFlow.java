package yumefusaka.envoymart.aiservice.flow;

import lombok.extern.slf4j.Slf4j;
import yumefusaka.envoymart.agent.flow.DeterministicFlow;
import yumefusaka.envoymart.agent.flow.FlowContext;
import yumefusaka.envoymart.agent.flow.FlowResult;
import yumefusaka.envoymart.agent.tool.ToolCall;
import yumefusaka.envoymart.agent.tool.ToolResult;
import yumefusaka.envoymart.aiservice.model.OrderResponse;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 售后引导 —— 针对具体订单的退货资格判定与操作指引。
 * <p>
 * 为什么值得做成确定性流程：退货有明确的判定规则（订单状态决定能不能退）和固定步骤。
 * 这类流程交给模型即兴推理会引入不确定性——<b>模型可能编造一个不存在的退货期限</b>。
 * 这里由代码判定，只把结论交给用户。
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

    private static final String STATUS_CANCELLED = "CANCELLED";

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
        Long orderId = extractOrderId(context.getUserMessage());
        log.info("[AfterSaleFlow] userId={} orderId={}", context.getUserId(), orderId);

        ToolResult query = context.getToolRegistry().execute(new ToolCall(
                UUID.randomUUID().toString(), "order_query",
                Map.of("userId", context.getUserId(), "orderId", orderId), true));

        if (!query.isSuccess() || !(query.getRawData() instanceof OrderResponse order)) {
            String reason = query.getErrorMessage() == null ? "未查询到订单" : query.getErrorMessage();
            return FlowResult.builder()
                    .success(false)
                    .output("我没能查到订单 " + orderId + "：" + reason + "。请确认订单号是否正确。")
                    .build();
        }

        return evaluate(order);
    }

    /** 按订单状态给出确定性结论，不交给模型判断。 */
    private FlowResult evaluate(OrderResponse order) {
        String status = order.getStatus() == null ? "" : order.getStatus();

        if (STATUS_CANCELLED.equals(status)) {
            return FlowResult.builder()
                    .success(true)
                    .data(order)
                    .output("订单 " + order.getOrderNo() + " 已经是取消状态，无需再申请退货。"
                            + "如果已支付，退款会在 1~3 个工作日原路退回。")
                    .build();
        }

        return FlowResult.builder()
                .success(true)
                .data(order)
                .output("订单 " + order.getOrderNo() + "（" + status + "，金额 " + order.getTotalAmount() + " 元）"
                        + "可以申请退货。步骤如下：\n"
                        + "1. 在订单详情页点击「申请退货」，选择退货原因；\n"
                        + "2. 保持商品与包装完整，定制类和贴身个护商品不支持；\n"
                        + "3. 提交后快递员上门取件，质量问题的运费由平台承担；\n"
                        + "4. 仓库验收通过后退款原路返回，约 1~3 个工作日到账。\n"
                        + "需要我直接帮你提交退货申请吗？")
                .build();
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
