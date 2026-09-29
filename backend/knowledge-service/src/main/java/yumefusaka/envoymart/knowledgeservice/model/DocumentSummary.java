package yumefusaka.envoymart.knowledgeservice.model;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文档列表项 —— <b>不含正文</b>。
 * <p>
 * 列表页一次要拿十几篇文档，带上全文就是十几万字；而列表页一个字都用不上。
 */
@Data
@Builder
public class DocumentSummary {

    private String docNo;
    private String title;
    /** 文档类型：manual / policy / regulation / spec / guide */
    private String source;
    /** 领域范围：nutrition / after_sale / ... */
    private String scope;
    private String version;
    private String tags;
    /** 0 停用 / 1 启用 */
    private Integer status;
    private Integer chunkCount;
    private Integer contentLength;
    private LocalDateTime updatedAt;
}
