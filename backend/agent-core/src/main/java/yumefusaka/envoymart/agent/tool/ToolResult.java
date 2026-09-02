package yumefusaka.envoymart.agent.tool;

import lombok.Builder;
import lombok.Data;

/**
 * 工具执行结果。
 */
@Data
@Builder
public class ToolResult {
    private boolean success;
    private String output;          // 文本结果
    private Object rawData;         // 结构化数据（可选）
    private String errorMessage;
    /** 因缺少用户确认而未执行（区别于执行失败） */
    @Builder.Default
    private boolean pendingApproval = false;
}
