package yumefusaka.envoymart.knowledgeservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 商品-文档关联。
 * <p>
 * <b>它回答的是一个此前无人能回答的问题</b>：「这篇文档属于哪个商品」。
 * 在此之前，这个绑定只存在于图谱里一条由模型抽出的边中，所以商品下架时系统
 * 不知道该停用哪几篇文档、也说不清某个商品到底有没有说明书。
 * <p>
 * 建表说明（为什么多对多、为什么需要 role 与 matched_by）见 {@code schema.sql}。
 */
@Data
@TableName("knowledge_doc_product")
public class KnowledgeDocProductEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String docNo;
    private Long spuId;
    /** 为空表示这份文档针对整个 SPU，不针对某个 SKU */
    private Long skuId;
    /** SUBJECT / MENTIONED，见 {@link Role} */
    private String role;
    /** MANUAL / BACKFILL / GRAPH，见 {@link MatchedBy} */
    private String matchedBy;
    private LocalDateTime matchedAt;

    /** 文档在商品上扮演的角色 */
    public enum Role {
        /** 本文档的主体就是该商品（说明书、成分表、质检报告）。**只有它参与生命周期联动** */
        SUBJECT,
        /** 本文档只是提到该商品（领域文档里的举例）。下架商品不会连坐它 */
        MENTIONED
    }

    /** 这条关联是谁定的。人工声明的与模型推出来的不该同等看待 */
    public enum MatchedBy {
        /** 上传时由运营在管理端下拉选择——最可信 */
        MANUAL,
        /** 历史数据回填——一次性脚本按文档标题与目录对齐得出 */
        BACKFILL,
        /** 图谱构建期由模型对齐得出——可信度低于人工声明 */
        GRAPH
    }
}
