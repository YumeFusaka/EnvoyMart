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
    /** 文档版本。引用要能指明「是哪一版」——同一份说明书改版后，旧版引用会变成错误依据 */
    private String version;
    /**
     * 这篇文档**主体**对应的商品 id（上传时人工声明的归属），可空或有多个。
     * <p>
     * 图谱构建期用它确定性地补出 {@code SPUx -CONTAINS-> 成分} 边——那条边是「商品有没有
     * 资料」的唯一判据，而它原先只靠模型从正文里抽，会漏。归属是人工声明的，不该让模型猜。
     */
    @Builder.Default
    private List<Long> subjectSpuIds = List.of();

}
