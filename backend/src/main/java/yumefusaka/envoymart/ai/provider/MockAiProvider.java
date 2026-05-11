package yumefusaka.envoymart.ai.provider;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import yumefusaka.envoymart.ai.model.response.ToolCallResponse;
import yumefusaka.envoymart.knowledge.model.KnowledgeSnippet;
import yumefusaka.envoymart.product.model.response.ProductResponse;

import java.util.List;

@Component
@Primary
public class MockAiProvider implements AiProvider {

    @Override
    public String generateReply(AiPrompt prompt) {
        StringBuilder builder = new StringBuilder("已为你整理当前问题的建议：");
        appendKnowledge(builder, prompt.getKnowledge());
        appendTools(builder, prompt.getToolCalls());
        appendRecommendations(builder, prompt.getRecommendedProducts());
        if (!StringUtils.hasText(builder.toString().replace("已为你整理当前问题的建议：", "").trim())) {
            builder.append(" 你可以继续告诉我预算、品类或具体订单问题，我会进一步帮你分析。");
        }
        return builder.toString().trim();
    }

    private void appendKnowledge(StringBuilder builder, List<KnowledgeSnippet> knowledge) {
        if (knowledge == null || knowledge.isEmpty()) {
            return;
        }
        KnowledgeSnippet first = knowledge.get(0);
        builder.append(" ").append(first.getContent());
    }

    private void appendTools(StringBuilder builder, List<ToolCallResponse> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return;
        }
        for (ToolCallResponse toolCall : toolCalls) {
            builder.append(" [")
                    .append(toolCall.getTool())
                    .append("] ")
                    .append(toolCall.getOutput());
        }
    }

    private void appendRecommendations(StringBuilder builder, List<ProductResponse> products) {
        if (products == null || products.isEmpty()) {
            return;
        }
        ProductResponse first = products.get(0);
        builder.append(" 推荐你优先看 ")
                .append(first.getName())
                .append("，当前价格 ")
                .append(first.getPrice())
                .append(" 元。");
    }
}
