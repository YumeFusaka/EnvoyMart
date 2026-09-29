package yumefusaka.envoymart.aiservice.model;

import lombok.Builder;
import lombok.Data;
import yumefusaka.envoymart.agent.rag.EvidenceGate;
import yumefusaka.envoymart.contract.ProductSummary;

import java.util.List;

@Data
@Builder
public class ChatResponse {

    private String sessionId;
    private String reply;
    private List<KnowledgeSnippet> knowledge;
    private List<ToolCallResponse> toolCalls;
    private List<ProductSummary> recommendedProducts;

    /**
     * 等待用户确认的高危操作，前端据此渲染确认卡片。
     * <p>
     * 每项是可直接展示的中文描述，形如 {@code order_cancel(orderId=12)}——
     * 不只是工具名。用户看不到要取消的是哪一单时的「确认」，等于没确认。
     * 非空表示本轮对话<b>被中断</b>、{@code reply} 是确认提示而非回答；
     * 前端确认后带 {@code approved=true} 重发，才真正执行。
     */
    private List<String> pendingActions;

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
}
