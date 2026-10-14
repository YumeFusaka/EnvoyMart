package yumefusaka.envoymart.aiservice.tool;

import lombok.extern.slf4j.Slf4j;
import yumefusaka.envoymart.agent.tool.Tool;
import yumefusaka.envoymart.agent.tool.ToolCall;
import yumefusaka.envoymart.agent.tool.ToolDefinition;
import yumefusaka.envoymart.agent.tool.ToolResult;
import yumefusaka.envoymart.aiservice.client.OrderClient;
import yumefusaka.envoymart.aiservice.model.AgentCheckoutRequest;
import yumefusaka.envoymart.contract.OrderResponse;

import java.util.Map;

/**
 * 下单工具 —— Agent 能力边界上最重的一步。
 * <p>
 * <b>为什么收货信息必须由用户在这一轮给出、而不是 Agent 去地址簿里挑一个。</b>
 * 「默认地址」是用户的选择，不是事实——他可能这次就想寄到公司。
 * Agent 替他选，选对了没人夸，选错了是一件退不掉的错单。
 * 所以本工具的说明里明确要求：收货信息从用户这一轮的话里取，取不全就**问**，不要猜。
 * <p>
 * <b>下单只做到「已下单待支付」。</b>支付是另一条链路（pay 由用户在收银台完成），
 * 本工具不碰钱。Agent 能承诺的是「订单已创建」，不是「已经买好了」——
 * 说成后者会让用户以为不用再操作，订单则静静躺在待支付里直到超时关闭。
 */
@Slf4j
public class CheckoutTool implements Tool {

    private final OrderClient orderClient;

    public CheckoutTool(OrderClient orderClient) {
        this.orderClient = orderClient;
    }

    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.builder()
                .name("cart_checkout")
                .description("把当前用户购物车里<b>已勾选</b>的商品结算成订单。"
                        + "用户说「帮我下单」「结算」「买了」时使用。"
                        + "调用前必须已经拿到完整的收货信息（收货人、手机号、省、市、区、详细地址）。"
                        + "**用户没有在话里给出地址时，先调用 address_list 查地址簿**："
                        + "查到了就把那条地址念给用户听、让他确认后再调用本工具；"
                        + "用户说不要默认地址/还没确定寄哪里时同样先查地址簿让他挑，不许跳过查询直接索要；"
                        + "只有地址簿为空、或他要寄地址簿之外的新地址，才向他索要完整收货信息。"
                        + "**任何一项都不能编、也不能替用户做主**——"
                        + "地址是用户的选择，这次可能就想寄到公司。"
                        + "下单后订单是待支付状态，要提醒用户还需完成支付。")
                .requiresConfirmation(true)
                .acceptsOperationId(true)
                .parameters(Map.of(
                        "receiverName", ToolDefinition.ParameterSpec.builder()
                                .type("string").description("收货人姓名").required(true).build(),
                        "receiverPhone", ToolDefinition.ParameterSpec.builder()
                                .type("string").description("收货人手机号，11 位").required(true).build(),
                        "receiverProvince", ToolDefinition.ParameterSpec.builder()
                                .type("string").description("省").required(true).build(),
                        "receiverCity", ToolDefinition.ParameterSpec.builder()
                                .type("string").description("市").required(true).build(),
                        "receiverDistrict", ToolDefinition.ParameterSpec.builder()
                                .type("string").description("区/县").required(true).build(),
                        "receiverDetail", ToolDefinition.ParameterSpec.builder()
                                .type("string").description("详细地址（街道门牌）").required(true).build(),
                        "remark", ToolDefinition.ParameterSpec.builder()
                                .type("string").description("订单备注，用户没提就不传").required(false).build()
                ))
                .build();
    }

    @Override
    public ToolResult execute(ToolCall call) {
        return execute(call, false);
    }
    @Override public ToolResult recover(ToolCall call) { return execute(call, true); }
    private ToolResult execute(ToolCall call, boolean queryOnly) {
        try {
            String userId = call.requireUserId();
            AgentCheckoutRequest request = new AgentCheckoutRequest();
            request.setReceiverName(required(call, "receiverName"));
            request.setReceiverPhone(required(call, "receiverPhone"));
            request.setReceiverProvince(required(call, "receiverProvince"));
            request.setReceiverCity(required(call, "receiverCity"));
            request.setReceiverDistrict(required(call, "receiverDistrict"));
            request.setReceiverDetail(required(call, "receiverDetail"));
            request.setRemark(optional(call, "remark"));
            // 每个签名动作独立派生订单请求号，同一批中的多个结算不共享业务幂等键。
            request.setRequestId(yumefusaka.envoymart.common.operation.OperationParameters.sha256(userId + ":" + call.getOperationId()));

            // 首次回执与订单提交共用业务事务，恢复时不回读后来变化的订单快照。
            Map<String, Object> parameters = new java.util.LinkedHashMap<>();
            parameters.put("receiverName", request.getReceiverName()); parameters.put("receiverPhone", request.getReceiverPhone());
            parameters.put("receiverProvince", request.getReceiverProvince()); parameters.put("receiverCity", request.getReceiverCity());
            parameters.put("receiverDistrict", request.getReceiverDistrict()); parameters.put("receiverDetail", request.getReceiverDetail());
            parameters.put("remark", request.getRemark()); parameters.put("userCouponId", null);
            var operation = WriteReceipts.context(call, parameters);
            var receipt = WriteReceipts.run(operation, queryOnly, OrderResponse.class,
                    () -> orderClient.operationReceipt(userId, operation.operationId(), operation.toolName(), operation.parametersHash()),
                    () -> orderClient.checkoutReceipt(userId, operation.operationId(), request));
            OrderResponse order = receipt.data();
            WriteReceipts.require(order != null && order.getId() != null && order.getId() > 0
                    && order.getOrderNo() != null && !order.getOrderNo().isBlank()
                    && order.getPayAmount() != null && order.getPayAmount() >= 0 && "CREATED".equals(order.getStatus()));
            return ToolResult.builder()
                    .success(true)
                    .operationState(ToolResult.OperationState.COMMITTED).receiptReplayed(receipt.replayed())
                    .output("订单已创建：单号 " + order.getOrderNo()
                            + "，应付 " + Money.yuan(order.getPayAmount())
                            + "。当前状态是待支付——务必提醒用户还要完成支付，"
                            + "否则订单会超时关闭。")
                    // 用户看到的那一版：同一件事，去掉「务必提醒用户」这层对模型说的话
                    .userFacingText("订单已创建：单号 " + order.getOrderNo()
                            + "，应付 " + Money.yuan(order.getPayAmount())
                            + "。当前状态是待支付，还要完成支付，否则订单会超时关闭。")
                    .rawData(order)
                    .build();
        } catch (Exception e) {
            return WriteReceipts.failure(e, "下单");
        }
    }

    /**
     * 必填参数缺失时直接失败，不用空串兜底。
     * <p>
     * 空串会被下游的校验注解拦下，回来的是一句用户看不懂的参数错误；
     * 在这里拦下，模型拿到的是一句「缺哪个字段」——它可以据此回去问用户，
     * 那正是我们希望它做的事。
     */
    private String required(ToolCall call, String field) {
        Object raw = call.getArguments().get(field);
        if (raw == null || String.valueOf(raw).isBlank()) {
            throw new IllegalArgumentException("缺少收货信息字段：" + field);
        }
        return String.valueOf(raw).strip();
    }

    private String optional(ToolCall call, String field) {
        Object raw = call.getArguments().get(field);
        if (raw == null) {
            return null;
        }
        String text = String.valueOf(raw).strip();
        return text.isEmpty() ? null : text;
    }
}
