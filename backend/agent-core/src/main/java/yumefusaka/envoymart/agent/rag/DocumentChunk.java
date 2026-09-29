package yumefusaka.envoymart.agent.rag;

import lombok.Builder;
import lombok.Data;

/**
 * 文档切片 —— 向量化和检索的基本粒度。
 * <p>
 * 它同时承担两件事：<b>写入时</b>是向量化的输入，<b>读出时</b>是答案的证据。
 * 后者要求它必须携带足够的定位信息 —— 一个不能指回原文的切片，对「可追溯」
 * 这件事毫无价值。
 */
@Data
@Builder
public class DocumentChunk {

    private String chunkId;
    private String docId;
    private String content;
    private int chunkIndex;

    /** 文档向量（Embedding 结果），维度由 `envoymart.embedding.dimemsion` 决定（默认 1024）。float 比 double 省一半空间。 */
    private float[] embedding;

    /**
     * BM25 索引文本；为空时退化为 {@link #content}。
     * <p>
     * 存在的理由：文档级检索要把标题与标签一并纳入关键词匹配（它们常含用户会说的词），
     * 但这些元信息不该出现在返回给模型的内容里。切片级检索由文档结构自带语境，
     * 留空即可。
     */
    private String indexText;

    // ==================== 溯源用（检索时填充，不参与向量化） ====================

    /**
     * 文档标题。
     * <p>
     * 单独存一份而不是让调用方去查：答案里的引用要显示《文档名》，
     * 而检索结果到生成 prompt 之间没有别的机会拿到它。
     */
    private String title;

    /** 来源标识（manual / faq / policy / spec …）。同样是政策，厂商说明书与平台规则的可信度不同 */
    private String source;

    /** 文档版本。同一份说明书会有多个版本，引用必须指明是哪一版 */
    private String version;

    /**
     * 切片在原文中的位置，形如 {@code 《维生素D3说明书》 > 第二章 > 3.2}。
     * <p>
     * 由结构分层切分时生成，随切片一起走完全程 —— 它是「点引用跳原文」的落点。
     */
    private String position;

    /** 文档内的字符偏移。位置文本给用户看，偏移给程序定位 */
    private Integer charOffset;

    /**
     * 检索得分。
     * <p>
     * <b>只在下游有意义，不参与持久化</b>：它是「这一次查询下这个切片有多相关」，
     * 而不是切片的固有属性。之前这个分数在 RRF 融合后就丢了 ——
     * 于是「检索到了什么」和「有多确信」这两件事在下游只剩前者，
     * 拒答门（低于阈值就不答）根本无从建立。
     */
    private Double score;

    /** 该切片是否被重排过。重排后的分数与召回分数不同量纲，要能区分 */
    private Boolean reranked;
}
