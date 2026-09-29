package yumefusaka.envoymart.knowledgeservice.model;

import lombok.Builder;
import lombok.Data;

/**
 * 切片索引项 —— 只够在原文里定位，不含切片正文。
 * <p>
 * 文档详情已经带了全文，再带一遍各片正文等于把同一段字发两遍；而前端要的只是
 * 「有哪几片、分别对应原文的哪一段」。
 */
@Data
@Builder
public class ChunkRef {

    private String chunkId;
    private Integer chunkIndex;
    /** 《文档标题》 > 第三章 用法用量 */
    private String position;
    /** UTF-16 码元偏移，指向文档正文里这一片的起点 */
    private Integer charOffset;
    /**
     * 这一片在原文中的结束位置（不含）。
     * <p>
     * 用「下一片的起点」而不是「本片正文长度」：切片正文前面拼了位置前缀
     * （{@code 《文档》 > 第三章}），原文里没有这段字，拿它的长度当区间会多高亮一截。
     * 最后一片取正文长度。
     */
    private Integer charEnd;
}
