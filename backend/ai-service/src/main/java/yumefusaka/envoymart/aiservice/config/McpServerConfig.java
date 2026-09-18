package yumefusaka.envoymart.aiservice.config;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;
import yumefusaka.envoymart.agent.loop.ToolContextKeys;
import yumefusaka.envoymart.agent.tool.ToolCall;
import yumefusaka.envoymart.agent.tool.ToolDefinition;
import yumefusaka.envoymart.agent.tool.ToolRegistry;
import yumefusaka.envoymart.agent.tool.ToolResult;
import yumefusaka.envoymart.aiservice.security.McpAuthFilter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;

/**
 * MCP Server 装配 —— 把业务能力以 MCP 协议对外发布。
 * <p>
 * <b>这一层没有 LangChain4j 的对应物</b>：LangChain4j 只提供 MCP client，server 端要自己接。
 * 这里用 MCP 官方 Java SDK 的 servlet 传输，而不是 {@code org.springframework.ai:mcp-spring-webmvc}——
 * 后者虽然也不依赖 spring-ai-core，但会把 {@code org.springframework.ai} 这个 groupId
 * 留在依赖树里，与"AI 接入层已完全基于 LangChain4j"这件事相矛盾。
 * <p>
 * 鉴权不在这里：{@link yumefusaka.envoymart.aiservice.security.McpAuthFilter} 按 URL 模式
 * 挂在 {@code /mcp} 上，是纯 Servlet Filter，不依赖任何 AI 框架。两者通过
 * {@link BaseContext} 交接身份。
 */
@Slf4j
@Configuration
public class McpServerConfig {

    /** transportContext 里承载认证用户身份的键 */
    private static final String TRANSPORT_USER_ID = "userId";

    /**
     * Streamable HTTP 传输。
     * <p>
     * 它本身就是一个 {@code HttpServlet}，由下面的 {@link ServletRegistrationBean} 注册到容器，
     * 不经过 Spring MVC 的 DispatcherServlet——这与迁移前 spring-ai-starter-mcp-server-webmvc
     * 的接入位置一致，{@code /mcp} 上的鉴权过滤器照旧生效。
     */
    @Bean
    public HttpServletStreamableServerTransportProvider mcpTransportProvider(
            @Value("${envoymart.mcp.endpoint:/mcp}") String endpoint) {
        return HttpServletStreamableServerTransportProvider.builder()
                .mcpEndpoint(endpoint)
                // 用 Jackson 3（Spring Boot 4 的默认 JSON 实现），与 MCP SDK 的 jackson3 模块对齐
                .jsonMapper(new JacksonMcpJsonMapper(JsonMapper.builder().build()))
                // 把 McpAuthFilter 的认证结果从 request 带进 transportContext，
                // 由 SDK 随每次工具调用送达执行点——工具跑在别的线程上，ThreadLocal 到不了
                .contextExtractor(request -> {
                    Object userId = request.getAttribute(McpAuthFilter.USER_ID_ATTRIBUTE);
                    return userId == null
                            ? McpTransportContext.EMPTY
                            : McpTransportContext.create(Map.of(TRANSPORT_USER_ID, userId));
                })
                .build();
    }

    @Bean
    public ServletRegistrationBean<HttpServletStreamableServerTransportProvider> mcpServletRegistration(
            HttpServletStreamableServerTransportProvider transport,
            @Value("${envoymart.mcp.endpoint:/mcp}") String endpoint) {
        ServletRegistrationBean<HttpServletStreamableServerTransportProvider> registration =
                new ServletRegistrationBean<>(transport, endpoint, endpoint + "/*");
        registration.setName("mcpServlet");
        registration.setOrder(1);
        return registration;
    }

    /**
     * MCP Server —— 工具清单直接取自 {@link ToolRegistry}。
     * <p>
     * 同一份工具定义既供 Agent 内部的 ReAct 循环调用，也以 MCP 协议对外发布，
     * 避免两处维护工具签名。
     */
    @Bean
    public McpSyncServer mcpSyncServer(HttpServletStreamableServerTransportProvider transport,
                                       ToolRegistry toolRegistry,
                                       @Value("${envoymart.mcp.server-name:envoymart-mcp}") String name,
                                       @Value("${envoymart.mcp.server-version:1.0.0}") String version,
                                       @Value("${envoymart.mcp.instructions:}") String instructions) {
        List<McpServerFeatures.SyncToolSpecification> tools = toolRegistry.listDefinitions().stream()
                .map(definition -> toMcpTool(toolRegistry, definition))
                .toList();

        McpSyncServer server = McpServer.sync(transport)
                .serverInfo(name, version)
                .instructions(instructions)
                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                .tools(tools)
                .build();

        log.info("[MCP] server 已启动，对外发布 {} 个工具: {}", tools.size(),
                toolRegistry.listDefinitions().stream().map(ToolDefinition::getName).toList());
        return server;
    }

    private McpServerFeatures.SyncToolSpecification toMcpTool(ToolRegistry registry, ToolDefinition definition) {
        McpSchema.Tool tool = McpSchema.Tool.builder(definition.getName())
                .description(definition.getDescription())
                .inputSchema(toJsonSchema(definition))
                .build();
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler(callHandler(registry, definition))
                .build();
    }

    /**
     * 一次 MCP 工具调用的落点。
     * <p>
     * 与 Agent 内部那条路径的关键区别是<b>没有护栏也没有用户确认</b>：MCP 的调用方是外部
     * Agent，不存在"我们这边的循环预算"，也没有"用户已确认"这个状态。高危及需确认的工具
     * 由 {@code ToolRegistry.execute} 自己的第二道防线拦下（未确认即拒绝）。
     */
    private BiFunction<McpSyncServerExchange, McpSchema.CallToolRequest, McpSchema.CallToolResult>
    callHandler(ToolRegistry registry, ToolDefinition definition) {
        return (exchange, request) -> {
            Map<String, Object> arguments = request.arguments() == null
                    ? Map.of()
                    : new LinkedHashMap<>(request.arguments());

            // 身份只认认证结果。MCP 客户端的入参是任意 JSON，arguments 里的同名项无条件剔除——
            // 工具定义已经不声明 userId，留着就是一条伪造身份的旁路。
            arguments.remove(ToolContextKeys.USER_ID);
            // 身份来自 McpAuthFilter 校验 JWT 的结果，经 transportContext 送进来。
            // 走 API Key（机器凭证、无用户身份）时为 null，需要身份的工具会 fail-closed。
            Object fromTransport = exchange.transportContext().get(TRANSPORT_USER_ID);
            String userId = fromTransport == null ? null : String.valueOf(fromTransport);

            ToolResult result = registry.execute(new ToolCall(
                    UUID.randomUUID().toString(), definition.getName(), arguments, false, userId));

            String output = result.isSuccess()
                    ? String.valueOf(result.getOutput())
                    : "工具执行失败: " + result.getErrorMessage();
            log.debug("[MCP] {} success={} userId={}", definition.getName(), result.isSuccess(), userId);

            return McpSchema.CallToolResult.builder()
                    .addTextContent(output)
                    .isError(!result.isSuccess())
                    .build();
        };
    }

    /** 由工具参数定义生成 MCP 客户端可读的 JSON Schema。 */
    private Map<String, Object> toJsonSchema(ToolDefinition definition) {
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
        return schema;
    }
}
