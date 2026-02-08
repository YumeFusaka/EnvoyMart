package yumefusaka.envoymart.agent.tool.mcp;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.concurrent.CompletableFuture;

/**
 * MCP 传输层抽象 —— 支持 stdio、SSE、WebSocket 等不同传输协议。
 * <p>
 * MCP (Model Context Protocol) 是 Anthropic 提出的开放协议，
 * 用于 LLM 与外部工具/资源之间的标准化通信。
 */
public interface MCPTransport {

    CompletableFuture<JsonNode> send(JsonNode message);

    void close();
}
