package yumefusaka.envoymart.aiservice.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class ChatResponse {

    private String sessionId;
    private String reply;
    private List<KnowledgeSnippet> knowledge;
    private List<ToolCallResponse> toolCalls;
    private List<ProductResponse> recommendedProducts;

    /** 等待用户确认的高危工具，前端据此渲染确认按钮 */
    private List<String> pendingActions;
}
