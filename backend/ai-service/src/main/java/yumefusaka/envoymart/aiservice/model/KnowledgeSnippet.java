package yumefusaka.envoymart.aiservice.model;

import lombok.Builder;
import lombok.Data;

/**
 * 引用片段 —— 随回答一起下发给前端的「依据」。
 * <p>
 * 字段取舍的唯一标准是：<b>用户拿到它能不能自己核对</b>。
 * 「写了什么」（{@link #content}）与「出自哪一篇的哪一节」（{@link #position}）是第一位的；
 * {@link #chunkId} 与 {@link #charOffset} 是给「点引用跳原文」用的锚点；
 * {@link #docId}、{@link #version}、{@link #source} 决定这条依据的可信度与失效判断
 * ——同一份说明书的旧版本引用，本身就是一条错误依据。
 */
@Data
@Builder
public class KnowledgeSnippet {

    /** 切片标识（「跳原文」的锚点） */
    private String chunkId;

    /** 所属文档标识 */
    private String docId;

    /** 文档标题。曾经这里塞的是 docId —— 一串对用户毫无意义的内部 ID */
    private String title;

    /** 领域范围（promotion / after_sale / nutrition …） */
    private String scope;

    /** 来源标识（manual / faq / spec …）：决定这条依据属于厂商说明还是平台规则 */
    private String source;

    /** 文档版本 */
    private String version;

    /** 位置，形如 {@code 《维生素D3说明书》 > 第二章 > 3.2} */
    private String position;

    /** 命中位置在原文中的字符偏移，供前端高亮 */
    private Integer charOffset;

    /** 相关性分，取值 [0,1]；为 null 表示该链路未提供相关性信号 */
    private Double score;

    /** 分数是否来自重排（cross-encoder）。前端可据此展示不同措辞的可信度 */
    private Boolean reranked;

    private String content;
}
