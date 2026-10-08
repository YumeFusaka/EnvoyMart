package yumefusaka.envoymart.contract;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class GraphBuildFailurePayload {
    private String batchId;
    private String docNo;
    private String entityKey;
    private String stage;
    private String reasonCode;
    private String detail;
    private boolean retryable;
}
