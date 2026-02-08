package yumefusaka.envoymart.agent.tool.mcp;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * MCP 服务端定义 —— 描述一个 MCP server 暴露的工具和资源。
 * <p>
 * MCP Server 可以是本地进程（stdio）、远程 HTTP（SSE/WebSocket）
 * 或嵌入式的内存服务。
 */
@Data
@Builder
public class MCPServerDefinition {
    private String name;
    private TransportType transportType;
    private String endpoint;             // URL 或命令路径
    private List<String> tools;          // 该 server 提供的工具名列表
    private List<String> resourceUris;   // 该 server 暴露的资源 URI

    public enum TransportType {
        STDIO,
        SSE,
        WEBSOCKET,
        EMBEDDED
    }
}
