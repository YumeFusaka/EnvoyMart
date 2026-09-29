package yumefusaka.envoymart.knowledgeservice.model;

import lombok.Builder;
import lombok.Data;

/**
 * 切片详情 —— <b>引用回跳的唯一入口</b>。
 * <p>
 * 回答里那个 {@code [1]} 背后到底是什么？用户要能自己看一眼。这个响应就是那一眼：
 * 切片原文（模型实际看到的那段字）+ 它出自哪份文档的哪一版哪一节 + 在全文里的位置。
 * <p>
 * 文档正文一并带上，前端点开引用就能直接跳过去高亮，不必再发第二次请求。
 */
@Data
@Builder
public class ChunkDetail {

    private String chunkId;
    private Integer chunkIndex;
    /** 渲染后的切片正文，即真正送去向量化、真正出现在 prompt 里的那段字 */
    private String content;
    /** 《文档标题》 > 第三章 用法用量 */
    private String position;
    private Integer charOffset;
    private Integer charEnd;

    // ---- 所属文档 ----
    private String docNo;
    private String title;
    private String source;
    private String scope;
    private String version;
    private Integer status;
    /** 所属文档全文，供前端就地高亮 */
    private String documentContent;
}
