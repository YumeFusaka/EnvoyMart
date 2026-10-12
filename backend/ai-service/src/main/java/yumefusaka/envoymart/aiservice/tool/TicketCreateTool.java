package yumefusaka.envoymart.aiservice.tool;

import lombok.extern.slf4j.Slf4j;
import yumefusaka.envoymart.agent.tool.Tool;
import yumefusaka.envoymart.agent.tool.ToolCall;
import yumefusaka.envoymart.agent.tool.ToolDefinition;
import yumefusaka.envoymart.agent.tool.ToolResult;
import yumefusaka.envoymart.aiservice.client.TicketClient;
import yumefusaka.envoymart.aiservice.model.AgentCreateTicketRequest;

import java.util.LinkedHashMap;
import java.util.Map;

/** 创建客服工单，返回同一事务保存的工单及首条消息回执。 */
@Slf4j
public class TicketCreateTool implements Tool {

    private final TicketClient ticketClient;

    public TicketCreateTool(TicketClient ticketClient) {
        this.ticketClient = ticketClient;
    }

    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.builder()
                .name("ticket_create")
                .description("提交客服工单。category 只能是 ORDER、REFUND、PRODUCT、OTHER；"
                        + "标题和问题描述必须来自用户原话，可选 orderId 关联当前用户订单，需要用户确认。")
                .requiresConfirmation(true)
                .acceptsOperationId(true)
                .parameters(Map.of(
                        "category", ToolDefinition.ParameterSpec.builder().type("string")
                                .description("ORDER/REFUND/PRODUCT/OTHER").required(true).build(),
                        "title", ToolDefinition.ParameterSpec.builder().type("string")
                                .description("工单标题，最多 128 字").required(true).build(),
                        "content", ToolDefinition.ParameterSpec.builder().type("string")
                                .description("问题描述，最多 2000 字").required(true).build(),
                        "orderId", ToolDefinition.ParameterSpec.builder().type("integer")
                                .description("可选的当前用户订单 ID").required(false).build()))
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
            AgentCreateTicketRequest request = new AgentCreateTicketRequest();
            request.setCategory(required(call, "category").toUpperCase(java.util.Locale.ROOT));
            if (!SetOfCategories.contains(request.getCategory())) {
                throw new IllegalArgumentException("工单分类只能是 ORDER / REFUND / PRODUCT / OTHER");
            }
            request.setTitle(required(call, "title"));
            request.setContent(required(call, "content"));
            request.setOrderId(optionalLong(call.getArguments().get("orderId")));
            Map<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("category", request.getCategory()); parameters.put("title", request.getTitle());
            parameters.put("content", request.getContent()); parameters.put("orderId", request.getOrderId());
            var operation = WriteReceipts.context(call, parameters);
            var receipt = WriteReceipts.run(operation, queryOnly, Map.class,
                    () -> ticketClient.operationReceipt(userId, operation.operationId(), operation.toolName(), operation.parametersHash()),
                    () -> ticketClient.createReceipt(userId, operation.operationId(), request));
            Map<String, Object> created = receipt.data();
            WriteReceipts.require(created != null && created.get("ticket") instanceof Map<?, ?> ticket
                    && ticket.get("id") instanceof Number id && id.longValue() > 0 && "OPEN".equals(ticket.get("status"))
                    && created.get("messages") instanceof java.util.List<?> messages && !messages.isEmpty());
            Map<String, Object> authoritative = created;
            return ToolResult.builder().success(true).operationState(ToolResult.OperationState.COMMITTED).receiptReplayed(receipt.replayed())
                    .output("工单已提交，客服会按工单记录跟进。")
                    .rawData(authoritative)
                    .facts(Map.of("工单状态", "OPEN"))
                    .build();
        } catch (Exception e) {
            return WriteReceipts.failure(e, "工单提交");
        }
    }

    private static final java.util.Set<String> SetOfCategories = java.util.Set.of("ORDER", "REFUND", "PRODUCT", "OTHER");

    private static String required(ToolCall call, String field) {
        Object raw = call.getArguments().get(field);
        if (raw == null || String.valueOf(raw).isBlank()) throw new IllegalArgumentException("缺少必填参数：" + field);
        return String.valueOf(raw).strip();
    }

    private static Long optionalLong(Object raw) {
        if (raw == null || String.valueOf(raw).isBlank()) return null;
        try {
            long id = Long.parseLong(String.valueOf(raw));
            if (id <= 0) throw new NumberFormatException();
            return id;
        } catch (Exception e) {
            throw new IllegalArgumentException("orderId 必须是正整数");
        }
    }
}
