package yumefusaka.envoymart.knowledgeservice.model;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 文档详情 —— 带全文，这是「点引用跳原文」的目的地。
 * <p>
 * 全文与切片一起返回，而不是让前端再发一次请求：高亮要用 {@code charOffset} 在全文里
 * 定位，两者分开取会出现「原文到了、切片还没到」的中间态，用户看到的是一个跳错位置的
 * 高亮块。一次拿全，没有中间态。
 */
@Data
@Builder
public class DocumentDetail {

    private String docNo;
    private String title;
    private String source;
    private String scope;
    private String version;
    private String tags;
    private Integer status;
    /** 正文全文。{@code charOffset} 就是它上面的下标 */
    private String content;
    private LocalDateTime updatedAt;
    /** 切片清单（不含各片正文），供目录式浏览与定位 */
    private List<ChunkRef> chunks;
}
