package yumefusaka.envoymart.knowledge.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class KnowledgeSnippet {

    private String title;
    private String content;
    private String scope;
}
