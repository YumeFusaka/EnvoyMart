package yumefusaka.envoymart.knowledgeservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识库文档。
 * <p>
 * 表里存 {@code content} 全文，不存文件路径——文件是装载的入口，不是事实源。
 * 引用要能指向「哪一版」，而版本是这条记录的属性。
 */
@Data
@TableName("knowledge_document")
public class KnowledgeDocumentEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    /** 对外引用的文档编号，形如 KB-0005。售后政策的 doc_ref 靠它回指 */
    private String docNo;
    private String title;
    /** manual / policy / regulation / spec / guide */
    private String source;
    /** nutrition / after_sale / logistics / payment / promotion / food_safety */
    private String scope;
    private String version;
    private String tags;
    /** 0 停用 / 1 启用 */
    private Integer status;
    private String content;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
