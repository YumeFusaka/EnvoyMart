package yumefusaka.envoymart.aiservice.skill;

import lombok.extern.slf4j.Slf4j;
import yumefusaka.envoymart.agent.skill.Skill;
import yumefusaka.envoymart.agent.skill.SkillContext;
import yumefusaka.envoymart.agent.skill.SkillResult;
import yumefusaka.envoymart.agent.tool.ToolCall;
import yumefusaka.envoymart.agent.tool.ToolResult;
import yumefusaka.envoymart.aiservice.model.OrderResponse;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 售后引导 Skill —— 把标准化的退货流程从模型手里拿回来。
 * <p>
 * 为什么用 Skill 而不是让模型自由发挥：退货有明确的判定规则
 * （订单状态决定能不能退）和固定的操作步骤。这类流程的正确性要求高、
 * 路径固定，交给模型即兴推理反而会引入不确定性——模型可能编造一个
 * 不存在的退货期限。Skill 用确定性代码走完，只把结论交给模型润色。
 */
@Slf4j
public class AfterSaleSkill implements Skill {

    private static final Pattern ORDER_ID = Pattern.compile("\\d{1,19}");

    /** 售后意图关键词 */
    private static final Set<String> INTENT_KEYWORDS =
            Set.of("退货", "换货", "退款", "售后", "退掉");

    /** 已取消的订单不能再走售后 */
    private static final String STATUS_CANCELLED = "CANCELLED";

    @Override
    public String getName() {
        return "after_sale_guide";
    }

    @Override
    public String getDescription() {
        return "针对指定订单的售后引导：查询订单状态并给出退货可行性判断与操作步骤。";
    }

    /**
     * 命中条件：既表达了售后意图，又给出了具体订单号。
     * <p>
     * 只问政策（"七天无理由怎么算"）不该命中——那属于知识问答，走 ReAct + RAG 更合适；
     * 只有落到具体订单上，才有确定性流程可走。
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
    public SkillResult execute(SkillContext context) {
        Long orderId = extractOrderId(context.getUserMessage());
        log.info("[AfterSaleSkill] userId={} orderId={}", context.getUserId(), orderId);

        ToolResult query = context.getToolRegistry().execute(new ToolCall(
                UUID.randomUUID().toString(), "order_query",
                Map.of("userId", context.getUserId(), "orderId", orderId)));

        if (!query.isSuccess() || !(query.getRawData() instanceof OrderResponse order)) {
            String reason = query.getErrorMessage() == null ? "未查询到订单" : query.getErrorMessage();
            return SkillResult.builder()
                    .success(false)
                    .output("我没能查到订单 " + orderId + "：" + reason + "。请确认订单号是否正确。")
                    .build();
        }

        return evaluate(order);
    }

    /** 按订单状态给出确定性结论，不交给模型判断。 */
    private SkillResult evaluate(OrderResponse order) {
        String status = order.getStatus() == null ? "" : order.getStatus();

        if (STATUS_CANCELLED.equals(status)) {
            return SkillResult.builder()
                    .success(true)
                    .data(order)
                    .output("订单 " + order.getOrderNo() + " 已经是取消状态，无需再申请退货。"
                            + "如果已支付，退款会在 1~3 个工作日原路退回。")
                    .build();
        }

        return SkillResult.builder()
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

    private Long extractOrderId(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher matcher = ORDER_ID.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        try {
            return Long.valueOf(matcher.group());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
