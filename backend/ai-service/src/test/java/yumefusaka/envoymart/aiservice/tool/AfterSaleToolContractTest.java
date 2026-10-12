package yumefusaka.envoymart.aiservice.tool;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import yumefusaka.envoymart.agent.tool.ToolCall;
import yumefusaka.envoymart.aiservice.client.OrderClient;
import yumefusaka.envoymart.aiservice.model.AgentAfterSaleRequest;
import yumefusaka.envoymart.aiservice.model.AgentAfterSaleResult;
import yumefusaka.envoymart.common.result.Result;
import yumefusaka.envoymart.contract.AfterSalePreview;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class AfterSaleToolContractTest {

    private static ToolCall call(String type, String reason) {
        return new ToolCall("售后契约", "after_sale_apply",
                Map.of("orderItemId", 7, "type", type, "reason", reason, "qualityIssue", true), true, "alice", "op-after-sale-" + type);
    }

    private static AgentAfterSaleResult receipt() {
        AgentAfterSaleResult result = new AgentAfterSaleResult();
        result.setId(1L);
        result.setAfterSaleNo("AS-契约");
        result.setStatusText("待审核");
        result.setRefundAmount(8900L);
        return result;
    }

    @Test
    void 交易三种类型通过持久回执接口且保持真实状态金额() {
        for (String type : new String[]{"REFUND_ONLY", "RETURN_REFUND", "EXCHANGE"}) {
            OrderClient client = mock(OrderClient.class);
            stubMissing(client);
            when(client.applyAfterSaleReceipt(anyString(), anyString(), any())).thenAnswer(invocation -> {
                AgentAfterSaleRequest request = invocation.getArgument(2);
                var parameters = new java.util.LinkedHashMap<String, Object>();
                parameters.put("orderItemId", request.getOrderItemId()); parameters.put("type", request.getType());
                parameters.put("reason", request.getReason()); parameters.put("description", request.getDescription()); parameters.put("qualityIssue", request.getQualityIssue());
                var operation = yumefusaka.envoymart.common.operation.OperationContext.of("alice", invocation.getArgument(1), "after_sale_apply", parameters);
                return new yumefusaka.envoymart.common.operation.OperationReceiptReply<>(operation, Result.success(receipt()), false).response();
            });
            var result = new AfterSaleTool(client).execute(call(type.toLowerCase(), "商品破损"));
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getOutput()).contains("AS-契约", "待审核", "89.00");
            var captured = org.mockito.ArgumentCaptor.forClass(AgentAfterSaleRequest.class);
            var order = inOrder(client);
            order.verify(client).operationReceipt(anyString(), anyString(), anyString(), anyString());
            order.verify(client).applyAfterSaleReceipt(eq("alice"), anyString(), captured.capture());
            assertThat(captured.getValue().getType()).isEqualTo(type);
            assertThat(captured.getValue().getReason()).isEqualTo("商品破损");
            assertThat(captured.getValue().getQualityIssue()).isTrue();
        }
    }

    @Test
    void 旧私有类型和缺失原因不能触达交易接口() {
        for (String type : new String[]{"REFUND", "RETURN"}) {
            OrderClient client = mock(OrderClient.class);
            assertThat(new AfterSaleTool(client).execute(call(type, "破损")).isSuccess()).isFalse();
            verifyNoInteractions(client);
        }
        OrderClient client = mock(OrderClient.class);
        assertThat(new AfterSaleTool(client).execute(call("RETURN_REFUND", " ")).isSuccess()).isFalse();
        verifyNoInteractions(client);
    }

    @Test
    void 政策拒绝原样返回且不会提交() {
        OrderClient client = mock(OrderClient.class);
        stubMissing(client);
        when(client.applyAfterSaleReceipt(anyString(), anyString(), any())).thenReturn(org.springframework.http.ResponseEntity.ok(Result.error(409, "超过受理期限")));
        var result = new AfterSaleTool(client).execute(call("RETURN_REFUND", "破损"));
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("超过受理期限");
        verify(client).applyAfterSaleReceipt(anyString(), anyString(), any());
    }

    @Test
    void 回执查询无结果响应不能放行提交() {
        OrderClient client = mock(OrderClient.class);
        when(client.operationReceipt(anyString(), anyString(), anyString(), anyString())).thenReturn(null);
        var result = new AfterSaleTool(client).execute(call("EXCHANGE", "破损"));
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("回执");
        verify(client, never()).applyAfterSaleReceipt(anyString(), anyString(), any());
    }

    @Test
    void 工具描述只列交易类型不引用不存在的预览工具且原因必填() {
        var definition = new AfterSaleTool(null).getDefinition();
        assertThat(definition.getDescription()).contains("预检").doesNotContain("after_sale_preview");
        assertThat(definition.getParameters().get("type").getDescription())
                .contains("REFUND_ONLY", "RETURN_REFUND", "EXCHANGE");
        assertThat(definition.getParameters().get("reason").isRequired()).isTrue();
    }

    @Test
    void 写请求失败不得重试且告知查询当前状态() {
        OrderClient client = mock(OrderClient.class);
        stubMissing(client);
        when(client.applyAfterSaleReceipt(anyString(), anyString(), any())).thenReturn(org.springframework.http.ResponseEntity.ok(Result.error(500, "写入后回执异常")));
        var result = new AfterSaleTool(client).execute(call("EXCHANGE", "破损"));
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("查询原操作结果", "不要重新创建操作");
        verify(client, times(1)).applyAfterSaleReceipt(anyString(), anyString(), any());
    }

    @Test
    void 订单行原因和质量标记格式不合法时不触达下游() {
        for (Map<String, Object> args : java.util.List.of(
                Map.<String, Object>of("orderItemId", 0, "type", "EXCHANGE", "reason", "破损"),
                Map.<String, Object>of("orderItemId", 7, "type", "EXCHANGE", "reason", "长".repeat(65)),
                Map.<String, Object>of("orderItemId", 7, "type", "EXCHANGE", "reason", "破损", "qualityIssue", "是"))) {
            OrderClient client = mock(OrderClient.class);
            var result = new AfterSaleTool(client).execute(new ToolCall("边界", "after_sale_apply", args, false, "alice"));
            assertThat(result.isSuccess()).isFalse();
            verifyNoInteractions(client);
        }
    }

    @Test
    void 交易响应状态与金额字段无需工具自行翻译() {
        var result = new ObjectMapper().readValue("""
                {"id":1,"afterSaleNo":"AS-契约","status":"APPLIED","statusText":"待审核", "type":"RETURN_REFUND",
                 "orderItemId":7,"orderNo":"EM-契约","refundAmount":8900,"docRef":"KB-0004","images":[],"spuName":"商品"}
                """, AgentAfterSaleResult.class);
        assertThat(result.getStatusText()).isEqualTo("待审核");
        assertThat(result.getRefundAmount()).isEqualTo(8900L);
        assertThat(result.getDocRef()).isEqualTo("KB-0004");
    }
    private static void stubMissing(OrderClient client) {
        when(client.operationReceipt(anyString(), anyString(), anyString(), anyString())).thenAnswer(invocation ->
                yumefusaka.envoymart.common.operation.OperationReceiptReply.notFound(
                        yumefusaka.envoymart.common.operation.OperationContext.expected(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2), invocation.getArgument(3))));
    }
}
