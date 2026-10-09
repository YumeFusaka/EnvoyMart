package yumefusaka.envoymart.contract;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class GraphBuildFailurePayload {
    private String batchId;
    private String docNo;
    private String entityKey;
    private String stage;
    private String reasonCode;
    private String detail;
    private boolean retryable;
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

    public GraphBuildFailurePayload(String batchId, String docNo, String entityKey, String stage,
                                    String reasonCode, String detail, boolean retryable) {
        this.batchId = batchId;
        this.docNo = docNo;
        this.entityKey = entityKey;
        this.stage = stage;
        this.reasonCode = reasonCode;
        this.detail = detail;
        this.retryable = retryable;
    }
}
