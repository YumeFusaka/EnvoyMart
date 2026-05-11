package yumefusaka.envoymart.knowledge.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class KnowledgeDocument {

    private String id;
    private String title;
    private String scope;
    private String content;
    private List<String> tags;
}
