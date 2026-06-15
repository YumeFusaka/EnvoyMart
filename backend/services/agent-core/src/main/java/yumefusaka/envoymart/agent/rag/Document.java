package yumefusaka.envoymart.agent.rag;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 原始文档 —— RAG 管线的输入单元。
 */
@Data
@Builder
public class Document {
    private String id;
    private String title;
    private String content;
    private List<String> tags;
    private String source;       // 来源标识（manual / faq / product_desc …）
    private String scope;        // 领域范围（promotion / logistics / after_sale …）
}
