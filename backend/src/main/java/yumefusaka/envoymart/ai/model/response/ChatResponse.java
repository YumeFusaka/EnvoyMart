package yumefusaka.envoymart.ai.model.response;

import lombok.Builder;
import lombok.Data;
import yumefusaka.envoymart.knowledge.model.KnowledgeSnippet;
import yumefusaka.envoymart.product.model.response.ProductResponse;

import java.util.List;

@Data
@Builder
public class ChatResponse {

    private String sessionId;
    private String reply;
    private List<KnowledgeSnippet> knowledge;
    private List<ToolCallResponse> toolCalls;
    private List<ProductResponse> recommendedProducts;
}
