package yumefusaka.envoymart.knowledgeservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("graph_build_failure")
public class GraphBuildFailureEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String batchId;
    private String docNo;
    private String entityKey;
    private String stage;
    private String reasonCode;
    private String detail;
    private Boolean retryable;
    private String rawCandidate;
    private String normalizedHeadKind;
    private String normalizedHead;
    private String normalizedTailKind;
    private String normalizedTail;
    private String relation;
    private String quote;
    private Integer quoteOffsetStart;
    private Integer quoteOffsetEnd;
    private Boolean aliasHit;
    private String chunkId;
    private LocalDateTime occurredAt;
}
