package yumefusaka.envoymart.agent.tool.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import yumefusaka.envoymart.agent.tool.Tool;
import yumefusaka.envoymart.agent.tool.ToolCall;
import yumefusaka.envoymart.agent.tool.ToolDefinition;
import yumefusaka.envoymart.agent.tool.ToolResult;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP 适配器 —— 将 MCP 协议的工具包装为框架内部的 Tool 接口。
 * <p>
 * 每个 MCPAdapter 实例对应一个远程 MCP Server 的工具。
 * 通信协议由注入的 MCPTransport 决定。
 */
@Slf4j
public class MCPAdapter implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ToolDefinition definition;
    private final MCPTransport transport;

    public MCPAdapter(ToolDefinition definition, MCPTransport transport) {
        this.definition = definition;
        this.transport = transport;
    }

    @Override
    public ToolDefinition getDefinition() {
        return definition;
    }

    @Override
    public ToolResult execute(ToolCall call) {
        try {
            ObjectNode payload = MAPPER.createObjectNode();
            payload.put("jsonrpc", "2.0");
            payload.put("method", "tools/call");
            ObjectNode params = payload.putObject("params");
            params.put("name", call.getToolName());
            ObjectNode args = params.putObject("arguments");
            if (call.getArguments() != null) {
                call.getArguments().forEach((k, v) -> args.set(k, MAPPER.valueToTree(v)));
            }

            JsonNode result = transport.send(payload).join();
            log.debug("[MCPAdapter] tool={} result={}", call.getToolName(), result);

            String content = result.has("content")
                    ? result.get("content").asText()
                    : result.toString();

            return ToolResult.builder()
                    .success(true)
                    .output(content)
                    .rawData(result)
                    .build();
        } catch (Exception e) {
            log.error("[MCPAdapter] tool={} failed", call.getToolName(), e);
            return ToolResult.builder()
                    .success(false)
                    .errorMessage(e.getMessage())
                    .build();
        }
    }
}
