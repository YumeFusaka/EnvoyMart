package yumefusaka.envoymart.aiservice.service;

import yumefusaka.envoymart.agent.tool.ToolProgressListener;
import yumefusaka.envoymart.aiservice.model.ChatRequest;
import yumefusaka.envoymart.aiservice.model.ChatResponse;

import java.util.function.Consumer;

public interface AiAssistantService {

    ChatResponse chat(String userId, ChatRequest request);

    /**
     * 流式对话：模型输出逐块回调，返回完整结果（含知识命中与工具轨迹）。
     * <p>
     * {@code progress} 是工具执行的实时进度（请求级，绑定到调用方的那条 SSE 连接）；
     * 非流式入口无此需求，传 {@link ToolProgressListener#NOOP}。
     */
    ChatResponse chatStream(String userId, ChatRequest request, Consumer<String> onChunk,
                            ToolProgressListener progress);
}
