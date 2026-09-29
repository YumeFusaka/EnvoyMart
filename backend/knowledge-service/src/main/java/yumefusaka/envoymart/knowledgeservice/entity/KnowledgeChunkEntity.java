package yumefusaka.envoymart.knowledgeservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识库切片。
 * <p>
 * 与 Milvus 里的向量一一对应，{@code chunkId} 是两边的连接键。
 * 向量只回答「哪一片最相关」，位置、所属文档、原文字符偏移都要回到这张表来取。
 */
@Data
@TableName("knowledge_chunk")
public class KnowledgeChunkEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long docId;
    /** 与 Milvus 中的向量主键同值 */
    private String chunkId;
    private Integer chunkIndex;
    /** 渲染后的切片正文（含位置前缀），即真正送去向量化、真正出现在 prompt 里的那段字 */
    private String content;
    /** 《文档标题》 > 第三章 用法用量 */
    private String position;
    /** UTF-16 码元偏移，前端凭它跳回原文高亮 */
    private Integer charOffset;
    private LocalDateTime createdAt;
}
