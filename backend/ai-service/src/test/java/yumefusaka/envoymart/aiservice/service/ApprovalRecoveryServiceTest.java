package yumefusaka.envoymart.aiservice.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import yumefusaka.envoymart.agent.core.Agent;
import yumefusaka.envoymart.agent.core.TaskStage;
import yumefusaka.envoymart.agent.llm.ToolExecution;
import yumefusaka.envoymart.agent.tool.PendingAction;
import yumefusaka.envoymart.agent.tool.ToolProgressListener;
import yumefusaka.envoymart.agent.tool.ToolResult;
import yumefusaka.envoymart.aiservice.llm.ModelPricing;
import yumefusaka.envoymart.aiservice.memory.ChatHistoryStore;
import yumefusaka.envoymart.aiservice.memory.ChatIdempotencyStore;
import yumefusaka.envoymart.aiservice.model.ChatRequest;
import yumefusaka.envoymart.aiservice.service.impl.AiAssistantServiceImpl;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ApprovalRecoveryServiceTest {
    private Agent agent;
    private ChatHistoryStore history;
    private ChatIdempotencyStore transport;
    private AiAssistantServiceImpl service;

    @BeforeEach
    void setup() {
        agent = mock(Agent.class); history = mock(ChatHistoryStore.class); transport = mock(ChatIdempotencyStore.class);
        service = new AiAssistantServiceImpl(agent, mock(ModelPricing.class), history, transport, mock(CommerceCardAssembler.class));
        when(agent.verifyApproval("u", "s", "signed", true)).thenReturn(Optional.of(List.of(PendingAction.of("cart_add", Map.of("skuId", 1), "original-op"))));
        when(agent.verifyApprovalGrant("u", "s", "signed")).thenReturn(Optional.of(new yumefusaka.envoymart.agent.tool.ApprovalTokens.VerifiedGrant(List.of(PendingAction.of("cart_add", Map.of("skuId", 1), "original-op")), Long.MAX_VALUE / 1000 - 1, 0)));
        when(agent.verifyApproval("u", "s", "signed", false)).thenReturn(Optional.of(List.of(PendingAction.of("cart_add", Map.of("skuId", 1), "original-op"))));
        when(history.beginApproval("u", "s", "signed", false, false, 0, Long.MAX_VALUE / 1000 - 1, true)).thenReturn(new ChatHistoryStore.ApprovalLease(batch("UNKNOWN", 0), null, false));
    }

    private ChatHistoryStore.ApprovalBatch batch(String status, long leaseUntil) {
        var original = new ChatHistoryStore.StoredMessage("a-original", "assistant", "请确认", Instant.now(),
                Map.of("approvalToken", "signed", "pendingActions", List.of("cart_add(skuId=1)"),
                        "pendingActionDetails", List.of(Map.of("tool", "cart_add", "arguments", Map.of("skuId", 1))),
                        "turnId", "turn-original", "userMessageId", "u-original"));
        return new ChatHistoryStore.ApprovalBatch("signed", original, status, 1, null, leaseUntil, null);
    }

    private Agent.AgentResponse result(ToolResult.OperationState state) {
        return Agent.AgentResponse.builder().reply(state == ToolResult.OperationState.COMMITTED ? "原回执" : "结果待确认")
                .source("approval-recovery").stage(state == ToolResult.OperationState.COMMITTED ? TaskStage.DONE : TaskStage.WAITING_USER)
                .toolExecutions(List.of(ToolExecution.builder().tool("cart_add").operationId("original-op")
                        .operationState(state).receiptReplayed(true).success(state == ToolResult.OperationState.COMMITTED).build())).build();
    }

    @Test
    void 只读恢复使用原消息身份且不调用普通模型或请求号占位() {
        when(history.beginApproval("u", "s", "signed", false, false, 0, Long.MAX_VALUE / 1000 - 1, true)).thenReturn(new ChatHistoryStore.ApprovalLease(batch("UNKNOWN", 0), null, false));
        when(agent.recoverApproved(eq("u"), eq("s"), eq("signed"), eq(false), any())).thenReturn(result(ToolResult.OperationState.COMMITTED));

        var response = service.recoverApproval("u", "s", "signed", false);

        assertThat(response.getApprovalStatus()).isEqualTo("CONFIRMED");
        assertThat(response.getAssistantMessageId()).isEqualTo("a-original");
        assertThat(response.getApprovalToken()).isEqualTo("signed");
        assertThat(response.getToolCalls().getFirst().getOperationId()).isEqualTo("original-op");
        verifyNoInteractions(transport);
        verify(agent, never()).chat(any(), any(), any(), any());
        verify(agent, never()).chatStream(any(), any(), any(), any(), any(), any());
        verify(history, never()).recordTurn(anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void 过期令牌和取消批次恢复只能查询已提交回执() {
        when(agent.verifyApproval("u", "s", "signed", false)).thenReturn(Optional.empty());
        when(history.beginApproval("u", "s", "signed", false, true, 0, Long.MAX_VALUE / 1000 - 1, false)).thenReturn(new ChatHistoryStore.ApprovalLease(batch("CANCELLED", 0), null, false));
        when(history.beginApproval("u", "s", "signed", false, false, 0, Long.MAX_VALUE / 1000 - 1, false)).thenReturn(new ChatHistoryStore.ApprovalLease(batch("CANCELLED", 0), null, false));
        when(history.beginApproval("u", "s", "signed", false, false, 0, Long.MAX_VALUE / 1000 - 1, true)).thenReturn(new ChatHistoryStore.ApprovalLease(batch("CANCELLED", 0), null, false));
        when(agent.recoverApproved(eq("u"), eq("s"), eq("signed"), eq(false), any())).thenReturn(result(ToolResult.OperationState.COMMITTED));

        var response = service.recoverApproval("u", "s", "signed", true);

        assertThat(response.getApprovalStatus()).isEqualTo("CANCELLED");
        assertThat(response.isApprovalRetryAllowed()).isFalse();
        verify(agent, never()).recoverApproved(any(), any(), any(), eq(true), any());
    }

    @Test
    void 同token在有效租约内只查结果不第二次写且未知不报完成() {
        when(history.beginApproval("u", "s", "signed", false, true, 0, Long.MAX_VALUE / 1000 - 1, true)).thenReturn(new ChatHistoryStore.ApprovalLease(batch("IN_FLIGHT", System.currentTimeMillis() + 60000), null, false));
        when(agent.recoverApproved(eq("u"), eq("s"), eq("signed"), eq(false), any())).thenReturn(result(ToolResult.OperationState.UNKNOWN));

        var response = service.recoverApproval("u", "s", "signed", true);

        assertThat(response.getApprovalStatus()).isEqualTo("IN_FLIGHT");
        assertThat(response.isApprovalRetryAllowed()).isFalse();
        verify(agent, never()).recoverApproved(any(), any(), any(), eq(true), any());
    }

    @Test
    void 明确继续确认沿原令牌执行且未知结果保存后仍可恢复() {
        var batch = batch("UNKNOWN", 0);
        when(history.beginApproval("u", "s", "signed", false, true, 0, Long.MAX_VALUE / 1000 - 1, true)).thenReturn(new ChatHistoryStore.ApprovalLease(batch, "owner", true));
        when(agent.recoverApproved(eq("u"), eq("s"), eq("signed"), eq(true), any())).thenReturn(result(ToolResult.OperationState.UNKNOWN));

        var response = service.recoverApproval("u", "s", "signed", true);

        assertThat(response.getApprovalStatus()).isEqualTo("UNKNOWN");
        assertThat(response.isApprovalRetryAllowed()).isTrue();
        verify(history).finishApproval(eq("u"), eq("s"), eq("signed"), eq("owner"), eq("UNKNOWN"), eq(response));
        assertThat(response.getPendingActions()).containsExactly("cart_add(skuId=1)");
    }

    @Test
    void 签名不匹配在读取账本和工具执行之前拒绝() {
        when(agent.verifyApproval("u", "s", "signed", true)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.recoverApproval("u", "s", "signed", false)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(history, transport);
    }

    @Test
    void 租约撤销后不开始下一条原批次写操作() {
        var batch = batch("UNKNOWN", 0);
        when(history.beginApproval("u", "s", "signed", false, true, 0, Long.MAX_VALUE / 1000 - 1, true)).thenReturn(new ChatHistoryStore.ApprovalLease(batch, "owner", true));
        when(history.renewApproval("u", "s", "signed", "owner")).thenReturn(false);
        when(agent.recoverApproved(eq("u"), eq("s"), eq("signed"), eq(true), any())).thenAnswer(call -> {
            ToolProgressListener listener = call.getArgument(4); listener.onStart("cart_add"); return result(ToolResult.OperationState.COMMITTED);
        });

        assertThatThrownBy(() -> service.recoverApproval("u", "s", "signed", true))
                .isInstanceOf(IllegalStateException.class);
        verify(history).finishApproval(eq("u"), eq("s"), eq("signed"), eq("owner"), eq("UNKNOWN"), any());
    }

    @Test
    void 账本损坏只读仍查原回执但不伪造未知历史身份或开放重试() {
        when(history.beginApproval("u", "s", "signed", false, false, 0, Long.MAX_VALUE / 1000 - 1, true)).thenThrow(
                new ChatHistoryStore.ApprovalRecordUnreadableException(new IllegalArgumentException("损坏JSON")));
        when(agent.recoverApproved(eq("u"), eq("s"), eq("signed"), eq(false), any())).thenReturn(result(ToolResult.OperationState.COMMITTED));

        var response = service.recoverApproval("u", "s", "signed", false);

        assertThat(response.getApprovalRecordAvailable()).isFalse();
        assertThat(response.getApprovalRecordError()).contains("无法读取");
        assertThat(response.getAssistantMessageId()).isNull();
        assertThat(response.getTurnId()).isNull();
        assertThat(response.isApprovalRetryAllowed()).isFalse();
        assertThat(response.getToolCalls().getFirst().getOperationState()).isEqualTo(ToolResult.OperationState.COMMITTED);
        verify(agent, never()).recoverApproved(any(), any(), any(), eq(true), any());
    }

    @Test
    void 账本损坏未过期明确重试不能借只读降级获得写权限() {
        when(history.beginApproval("u", "s", "signed", false, true, 0, Long.MAX_VALUE / 1000 - 1, true)).thenThrow(
                new ChatHistoryStore.ApprovalRecordUnreadableException(new IllegalArgumentException("损坏JSON")));
        assertThatThrownBy(() -> service.recoverApproval("u", "s", "signed", true))
                .isInstanceOf(ChatHistoryStore.ApprovalRecordUnreadableException.class);
        verify(agent, never()).recoverApproved(any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void 回执查询期间取消返回最终取消状态并保留已提交工具事实() {
        when(history.beginApproval("u", "s", "signed", false, false, 0, Long.MAX_VALUE / 1000 - 1, true)).thenReturn(new ChatHistoryStore.ApprovalLease(batch("UNKNOWN", 0), null, false));
        when(agent.recoverApproved(eq("u"), eq("s"), eq("signed"), eq(false), any())).thenAnswer(call -> {
            when(history.beginApproval("u", "s", "signed", false, false, 0, Long.MAX_VALUE / 1000 - 1, true))
                    .thenReturn(new ChatHistoryStore.ApprovalLease(batch("CANCELLED", 0), null, false));
            return result(ToolResult.OperationState.COMMITTED);
        });

        var response = service.recoverApproval("u", "s", "signed", false);

        assertThat(response.getApprovalStatus()).isEqualTo("CANCELLED");
        assertThat(response.isApprovalRetryAllowed()).isFalse();
        assertThat(response.getToolCalls().getFirst().getOperationState()).isEqualTo(ToolResult.OperationState.COMMITTED);
        assertThat(response.getAssistantMessageId()).isEqualTo("a-original");
    }

    @Test
    void 查询期间另owner租约有效返回处理中且不能开放写重试() {
        when(history.beginApproval("u", "s", "signed", false, false, 0, Long.MAX_VALUE / 1000 - 1, true)).thenReturn(new ChatHistoryStore.ApprovalLease(batch("UNKNOWN", 0), null, false));
        when(agent.recoverApproved(eq("u"), eq("s"), eq("signed"), eq(false), any())).thenAnswer(call -> {
            when(history.beginApproval("u", "s", "signed", false, false, 0, Long.MAX_VALUE / 1000 - 1, true))
                    .thenReturn(new ChatHistoryStore.ApprovalLease(batch("IN_FLIGHT", System.currentTimeMillis() + 60000), null, false));
            return result(ToolResult.OperationState.UNKNOWN);
        });

        var response = service.recoverApproval("u", "s", "signed", false);
        assertThat(response.getApprovalStatus()).isEqualTo("IN_FLIGHT");
        assertThat(response.isApprovalRetryAllowed()).isFalse();
    }

    @Test
    void 初次确认期间被取消或换owner不能复活后续新审批卡() {
        when(history.beginApproval("u", "s", "signed", true, true, 0, Long.MAX_VALUE / 1000 - 1, true))
                .thenReturn(new ChatHistoryStore.ApprovalLease(batch("IN_FLIGHT", System.currentTimeMillis() + 30000), "owner", true));
        when(agent.confirmApproved(eq("u"), eq("s"), anyString(), eq("signed"), any(), any())).thenReturn(
                Agent.AgentResponse.builder().reply("确认后续操作").toolExecutions(result(ToolResult.OperationState.COMMITTED).getToolExecutions()).approvalToken("next-token")
                        .pendingActions(List.of("cart_checkout")).stage(TaskStage.WAITING_USER).build());
        when(history.beginApproval("u", "s", "signed", false, false, 0, Long.MAX_VALUE / 1000 - 1, true))
                .thenReturn(new ChatHistoryStore.ApprovalLease(batch("CANCELLED", 0), null, false));
        when(history.finishApproval(eq("u"), eq("s"), eq("signed"), eq("owner"), anyString(), any())).thenReturn(false);
        var request = new ChatRequest(); request.setSessionId("s"); request.setApprovalToken("signed"); request.setMessage("确认执行");

        var response = service.chat("u", request);

        assertThat(response.getApprovalToken()).isNotEqualTo("next-token");
        assertThat(response.getApprovalStatus()).isEqualTo("CANCELLED");
        verify(history, never()).recordTurn(anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void 签名过期已接受恢复使用独立授权检查而不调用普通确认或模型() {
        long expires = System.currentTimeMillis() / 1000 - 10;
        var legacy = batch("UNKNOWN", 0);
        long acceptedAt = System.currentTimeMillis() - 100000;
        var accepted = new ChatHistoryStore.ApprovalBatch("signed", legacy.original(), "IN_FLIGHT", acceptedAt,
                "owner", System.currentTimeMillis() + 30000, null, acceptedAt + 86400000);
        when(agent.verifyApproval("u", "s", "signed", false)).thenReturn(Optional.empty());
        when(agent.verifyApprovalGrant("u", "s", "signed")).thenReturn(Optional.of(
                new yumefusaka.envoymart.agent.tool.ApprovalTokens.VerifiedGrant(
                        List.of(PendingAction.of("cart_add", Map.of("skuId", 1), "original-op")), expires, 86400)));
        when(history.beginApproval("u", "s", "signed", false, true, 86400, expires, false))
                .thenReturn(new ChatHistoryStore.ApprovalLease(accepted, "owner", true));
        when(history.beginApproval("u", "s", "signed", false, false, 86400, expires, false))
                .thenReturn(new ChatHistoryStore.ApprovalLease(accepted, null, false));
        when(agent.recoverAcceptedApproved(eq("u"), eq("s"), eq("signed"), any(), any())).thenAnswer(call -> {
            java.util.function.BooleanSupplier authorization = call.getArgument(3);
            assertThat(authorization.getAsBoolean()).isTrue();
            return result(ToolResult.OperationState.COMMITTED);
        });
        var response = service.recoverApproval("u", "s", "signed", true);
        assertThat(response.getApprovalRecoverySeconds()).isEqualTo(86400);
        assertThat(response.getApprovalRecoveryUntil()).isEqualTo(accepted.recoveryUntil());
        verify(agent, never()).recoverApproved(any(), any(), any(), eq(true), any());
        verify(agent, never()).confirmApproved(any(), any(), any(), any(), any(), any());
    }
}
