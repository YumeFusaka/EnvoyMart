package yumefusaka.envoymart.aiservice.tool;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import yumefusaka.envoymart.agent.llm.ToolExecution;
import yumefusaka.envoymart.agent.loop.LoopGuard;
import yumefusaka.envoymart.agent.loop.ToolContextKeys;
import yumefusaka.envoymart.agent.tool.ToolCall;
import yumefusaka.envoymart.agent.tool.ToolDefinition;
import yumefusaka.envoymart.agent.tool.ToolRegistry;
import yumefusaka.envoymart.agent.tool.ToolResult;
import yumefusaka.envoymart.common.context.BaseContext;

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
    private final MeterRegistry meterRegistry;

    public ToolRegistryToolCallback(ToolRegistry toolRegistry, ToolDefinition definition, List<ToolExecution> sink,
                                    MeterRegistry meterRegistry) {
        this.toolRegistry = toolRegistry;
        this.definition = definition;
        this.sink = sink;
        this.meterRegistry = meterRegistry;
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
        return call(toolInput, null);
    }

    /**
     * 工具循环由框架驱动，但两件事必须由我们把关，所以从 toolContext 里取出来在这里生效：
     * <ul>
     *   <li><b>循环护栏</b>——超出预算或重复调用时拒绝执行，把原因交回给模型；</li>
     *   <li><b>高危确认</b>——未经用户确认的操作不执行。</li>
     * </ul>
     */
    @Override
    public String call(String toolInput, ToolContext context) {
        Map<String, Object> arguments = parseArguments(toolInput);
        Map<String, Object> toolContext = context == null ? Map.of() : context.getContext();

        LoopGuard guard = (LoopGuard) toolContext.get(ToolContextKeys.LOOP_GUARD);
        if (guard != null && !guard.allowToolCall(definition.getName(), arguments)) {
            log.warn("[Tool] {} blocked by loop guard: {}", definition.getName(), guard.getStopReason());
            recordToolMetric("blocked", 0);
            return guard.getStopReason() + "。请基于已有信息作答，不要再调用工具。";
        }

        boolean approved = Boolean.TRUE.equals(toolContext.get(ToolContextKeys.APPROVED));
        // 身份只认认证结果。arguments 里的同名项无条件剔除——
        // 工具定义已经不声明 userId，但 MCP 客户端的入参是任意 JSON，留着就是一条旁路。
        String userId = (String) toolContext.get(ToolContextKeys.USER_ID);
        if (userId == null) {
            // MCP 路径：MCP Server 不携带我们的 toolContext，身份来自 McpAuthFilter 校验 JWT 后的结果。
            // 走 API Key（机器凭证、无用户身份）时这里仍为 null，需要身份的工具会 fail-closed。
            userId = BaseContext.getCurrentId();
        }
        arguments = new LinkedHashMap<>(arguments);
        arguments.remove(ToolContextKeys.USER_ID);

        long startedAt = System.nanoTime();
        ToolResult result = toolRegistry.execute(
                new ToolCall(UUID.randomUUID().toString(), definition.getName(), arguments, approved, userId));
        long latencyMs = (System.nanoTime() - startedAt) / 1_000_000;

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
        // 拦截也计入指标：护栏触发率是判断"预算是否过紧"还是"模型确实在失控"的唯一依据
        recordToolMetric(result.isSuccess() ? "success" : "error", latencyMs);
        log.debug("[Tool] {} success={} output={}", definition.getName(), result.isSuccess(), output);
        return output;
    }

    /** 单个工具的调用次数与耗时，按结果分类——成功率与耗时趋势都从这两个指标来。 */
    private void recordToolMetric(String outcome, long latencyMs) {
        if (meterRegistry == null) {
            return;
        }
        Counter.builder("agent.tool.calls")
                .tag("tool", definition.getName()).tag("outcome", outcome)
                .register(meterRegistry).increment();
        if (latencyMs > 0) {
            Timer.builder("agent.tool.latency")
                    .tag("tool", definition.getName())
                    .register(meterRegistry)
                    .record(java.time.Duration.ofMillis(latencyMs));
        }
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
