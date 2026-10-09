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

/** 创建客服工单；返回创建回执并尝试用详情接口复核归属与状态。 */
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
            Map<String, Object> created = Downstream.mutate("工单", () -> ticketClient.create(userId, request));
            if (created == null || created.isEmpty()) {
                throw new IllegalStateException("工单创建没有返回权威回执");
            }
            Map<String, Object> authoritative = created;
            Object ticket = created.get("ticket");
            if (ticket instanceof Map<?, ?> ticketMap && ticketMap.get("id") != null) {
                try {
                    Map<String, Object> detail = Downstream.read("工单", () -> ticketClient.detail(
                            userId, Long.parseLong(String.valueOf(ticketMap.get("id")))));
                    if (detail != null && !detail.isEmpty()) authoritative = detail;
                } catch (RuntimeException e) {
                    log.warn("[TicketCreateTool] 工单创建后详情复核失败，将返回创建回执：{}", e.getMessage());
                }
            }
            return ToolResult.builder().success(true)
                    .output("工单已提交，客服会按工单记录跟进。")
                    .rawData(authoritative)
                    .facts(Map.of("工单状态", "OPEN"))
                    .build();
        } catch (Exception e) {
            return Downstream.failure("工单提交", e);
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
