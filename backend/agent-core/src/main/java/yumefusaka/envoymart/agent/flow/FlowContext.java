package yumefusaka.envoymart.agent.flow;

import lombok.Builder;
import lombok.Data;
import yumefusaka.envoymart.agent.tool.ToolRegistry;

/**
 * 确定性流程的执行上下文。
 * <p>
 * 只带流程真正需要的东西：身份、会话、原始消息、工具注册表。
 * 早先还挂了短期/长期记忆与 RAG 引擎，但没有任何流程读它们——接口比实现"看起来"更完备，
 * 会误导后续按不存在的语义去设计。
 */
@Data
@Builder
public class FlowContext {
    private String userId;
    private String sessionId;
    private String userMessage;
    private ToolRegistry toolRegistry;
}
