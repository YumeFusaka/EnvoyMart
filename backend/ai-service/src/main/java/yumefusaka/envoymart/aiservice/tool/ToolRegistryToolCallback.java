package yumefusaka.envoymart.aiservice.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import yumefusaka.envoymart.agent.llm.ToolExecution;
import yumefusaka.envoymart.agent.tool.ToolCall;
import yumefusaka.envoymart.agent.tool.ToolDefinition;
import yumefusaka.envoymart.agent.tool.ToolRegistry;
import yumefusaka.envoymart.agent.tool.ToolResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 把 agent-core 的 Tool 适配成 Spring AI 的 ToolCallback。
 * <p>
 * 同一份工具定义既供模型调用（Agent 链路），也供 MCP 协议对外发布，
 * 避免两处维护工具签名。
 */
@Slf4j
public class ToolRegistryToolCallback implements ToolCallback {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ToolRegistry toolRegistry;
    private final ToolDefinition definition;
    /** 调用轨迹收集器，可为 null（如 MCP 外部调用） */
    private final List<ToolExecution> sink;

    public ToolRegistryToolCallback(ToolRegistry toolRegistry, ToolDefinition definition, List<ToolExecution> sink) {
        this.toolRegistry = toolRegistry;
        this.definition = definition;
        this.sink = sink;
    }

    @Override
    public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
        return DefaultToolDefinition.builder()
                .name(definition.getName())
                .description(definition.getDescription())
                .inputSchema(toJsonSchema(definition))
                .build();
    }

    @Override
    public String call(String toolInput) {
        Map<String, Object> arguments = parseArguments(toolInput);
        ToolResult result = toolRegistry.execute(
                new ToolCall(UUID.randomUUID().toString(), definition.getName(), arguments));

        String output = result.isSuccess()
                ? String.valueOf(result.getOutput())
                : "工具执行失败: " + result.getErrorMessage();

        if (sink != null) {
            sink.add(ToolExecution.builder()
                    .tool(definition.getName())
                    .input(toolInput)
                    .output(output)
                    .success(result.isSuccess())
                    .rawData(result.getRawData())
                    .build());
        }
        log.debug("[Tool] {} success={} output={}", definition.getName(), result.isSuccess(), output);
        return output;
    }

    /** 由工具参数定义生成 JSON Schema，供模型/MCP 客户端理解工具签名。 */
    private String toJsonSchema(ToolDefinition definition) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        definition.getParameters().forEach((name, spec) -> {
            Map<String, Object> property = new LinkedHashMap<>();
            property.put("type", spec.getType() == null ? "string" : spec.getType());
            property.put("description", spec.getDescription() == null ? "" : spec.getDescription());
            properties.put(name, property);
            if (spec.isRequired()) {
                required.add(name);
            }
        });

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        return toJson(schema);
    }

    private Map<String, Object> parseArguments(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            log.warn("[Tool] cannot parse arguments: {}", json);
            return Map.of();
        }
    }

    private String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }
}
