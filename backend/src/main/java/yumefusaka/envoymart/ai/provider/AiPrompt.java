package yumefusaka.envoymart.ai.provider;

import lombok.Builder;
import lombok.Data;
import yumefusaka.envoymart.ai.model.response.ToolCallResponse;
import yumefusaka.envoymart.knowledge.model.KnowledgeSnippet;
import yumefusaka.envoymart.product.model.response.ProductResponse;

import java.util.List;

@Data
@Builder
public class AiPrompt {

    private String userMessage;
    private List<String> memory;
    private List<KnowledgeSnippet> knowledge;
    private List<ToolCallResponse> toolCalls;
    private List<ProductResponse> recommendedProducts;
}
