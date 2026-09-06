package yumefusaka.envoymart.agent.flow;

import lombok.Builder;
import lombok.Data;

/**
 * 确定性流程的执行结果。
 */
@Data
@Builder
public class FlowResult {
    private boolean success;
    private String output;
    private Object data;
}
