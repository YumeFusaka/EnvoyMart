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
import yumefusaka.envoymart.agent.tool.ApprovalTokens;
import yumefusaka.envoymart.agent.tool.ToolCall;
import yumefusaka.envoymart.agent.tool.ToolDefinition;
import yumefusaka.envoymart.agent.tool.ToolRegistry;
import yumefusaka.envoymart.agent.tool.ToolResult;
import yumefusaka.envoymart.aiservice.security.McpCallGuard;
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
     * 高危工具的确认参数名。
     * <p>
     * <b>为什么确认要做成协议参数</b>：内部对话链路的确认信号来自「用户点了批准按钮」，
     * 由服务端自己产生；MCP 的调用方是外部 Agent，我们看不到它那一侧有没有问过用户。
     * 与其假装知道，不如把确认变成签名里的显式契约——调用方先拿到用户的明确同意，
     * 才能把这个参数置为 true；不传或传 false 会被 {@code ToolRegistry.execute}
     * 的第二道防线拒绝，与对话侧同一条规则。
     */
    static final String CONFIRMED_ARG = "confirmed";

    /**
     * 高危工具的服务端确认令牌参数名。
     * <p>
     * <b>与 {@link #CONFIRMED_ARG} 的关系</b>：{@code confirmed} 保留为「这次调用属于需要确认的
     * 高危动作」的显式声明（也是 MCP SDK 侧参数校验通过的前提），但它不再构成授权——
     * 真正的授权是这里携带的令牌，由服务端在拦下一次高危调用时签发。
     */
    static final String APPROVAL_TOKEN_ARG = "approvalToken";

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
                                       McpCallGuard mcpCallGuard,
                                       ApprovalTokens approvalTokens,
                                       @Value("${envoymart.mcp.server-name:envoymart-mcp}") String name,
                                       @Value("${envoymart.mcp.server-version:1.0.0}") String version,
                                       @Value("${envoymart.mcp.instructions:}") String instructions) {
        List<McpServerFeatures.SyncToolSpecification> tools = toolRegistry.listDefinitions().stream()
                .map(definition -> toMcpTool(toolRegistry, mcpCallGuard, approvalTokens, definition))
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

    /**
     * MCP 调用护栏 —— 进程内单例。
     * <p>
     * <b>为什么挂在这一层</b>：配额与振荡状态是「按调用方 + 时间窗」的，只有
     * 越过鉴权、知道「这是谁」之后才有意义；而每次工具的调用都必然经过
     * {@link #callHandler}，是唯一一个既能拿到身份、又无法被绕过的窄腰。
     * 做成 Bean 而不是静态字段，是为了让测试能用一个配额很小的实例直接验边界，
     * 不用把真实阈值写死在测试里。
     */
    @Bean
    public McpCallGuard mcpCallGuard() {
        return new McpCallGuard();
    }

    /**
     * MCP 侧的高危确认校验器 —— 与对话链路<b>共用同一个密钥与同一套载荷格式</b>。
     * <p>
     * <b>为什么不另起一套</b>：两处都要回答同一个问题「这张卡片对应的操作，用户确实批准过吗」。
     * 各起一套的结果是两条信任链各自演进，迟早分叉（比如一处改 TTL、一处改载荷字段，
     * 就会出现「对话里有效、MCP 里无效」这类只在某条路上复现的故障）。共用也直接决定了
     * 令牌可以跨入口使用：用户在页面上确认卡片拿到的令牌，外部 Agent 拿着同一枚令牌
     * 也能走 MCP 执行——这正是「确认卡片是真正发生了的那次确认」的凭据。
     * <p>
     * 密钥来自与 {@code Agent} 相同的 {@code envoymart.agent.approval-secret}；留空时
     * {@link ApprovalTokens} 会生成进程级随机密钥（单机开发照常可用，多实例会互相验不过，
     * 启动日志里有告警）。
     */
    @Bean
    public ApprovalTokens approvalTokens(@Value("${envoymart.agent.approval-secret:}") String approvalSecret) {
        return new ApprovalTokens(approvalSecret);
    }

    private McpServerFeatures.SyncToolSpecification toMcpTool(ToolRegistry registry,
                                                              McpCallGuard guard,
                                                              ApprovalTokens approvals,
                                                              ToolDefinition definition) {
        String description = definition.getDescription();
        if (definition.isRequiresConfirmation()) {
            description = description + "（高危操作：仅在已获得用户明确确认后调用，" + CONFIRMED_ARG + " 传 true）";
        }
        McpSchema.Tool tool = McpSchema.Tool.builder(definition.getName())
                .description(description)
                .inputSchema(toJsonSchema(definition))
                .build();
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler(callHandler(registry, guard, approvals, definition))
                .build();
    }

    /**
     * 一次 MCP 工具调用的落点。
     * <p>
     * 与 Agent 内部那条路径的关键区别是<b>循环不在我们这边</b>：外部 Agent 调到第几次
     * 不由我们决定，所以护栏不能沿用它那套「一次请求一份预算」，只能按调用方身份做
     * 时间窗限流与振荡识别（见 {@link McpCallGuard}）。用户确认则以协议参数表达：
     * 高危工具的签名里多一个 {@value #CONFIRMED_ARG} 布尔参数（见 {@link #toJsonSchema}），
     * 不传或传 false 会被 {@code ToolRegistry.execute} 的第二道防线拦下——
     * 与对话侧「等待用户批准」是同一条规则，只是确认信号从哪里来不同。
     */
    private BiFunction<McpSyncServerExchange, McpSchema.CallToolRequest, McpSchema.CallToolResult>
    callHandler(ToolRegistry registry, McpCallGuard guard, ApprovalTokens approvals,
                ToolDefinition definition) {
        return (exchange, request) -> {
            Map<String, Object> arguments = request.arguments() == null
                    ? Map.of()
                    : new LinkedHashMap<>(request.arguments());

            // 身份只认认证结果。MCP 客户端的入参是任意 JSON，arguments 里的同名项无条件剔除——
            // 工具定义已经不声明 userId，留着就是一条伪造身份的旁路。
            arguments.remove(ToolContextKeys.USER_ID);
            // 确认信号与身份同理：是协议层入参，取走后删除，业务工具看不到它。
            // 高危工具的确认在这里兑换成服务端签名令牌的校验结果——见下方「确认授权」段
            boolean confirmed = definition.isRequiresConfirmation() && parseConfirmed(arguments.remove(CONFIRMED_ARG));
            // 授权令牌同样是协议层入参，取走后删除
            String approvalToken = stringArg(arguments.remove(APPROVAL_TOKEN_ARG));
            // 身份来自 McpAuthFilter 校验的结果，经 transportContext 送进来：JWT 是 userId，
            // API Key 是固定的 "api-key" 标识。两者都是「调用方」，配额按它分开算。
            // 注意这与「工具能不能拿到用户上下文」是两件事——API Key 调用在做业务时
            // 仍然没有 userId，需要身份的工具照旧 fail-closed。
            Object fromTransport = exchange.transportContext().get(TRANSPORT_USER_ID);
            String userId = fromTransport == null ? null : String.valueOf(fromTransport);

            // 旁路护栏：超限或原地打转时拒绝执行。
            // 门开在 registry.execute 之前——护栏的意义正是「别让下游真的被打到」，
            // 放到执行之后就成了事后统计。
            McpCallGuard.Verdict verdict = guard.check(userId, definition.getName(), arguments);
            if (verdict != McpCallGuard.Verdict.ALLOWED) {
                String reason = verdict == McpCallGuard.Verdict.QUOTA_EXCEEDED
                        ? "调用过于频繁：同一调用方每分钟最多 " + McpCallGuard.DEFAULT_QUOTA_PER_MINUTE + " 次"
                        : "检测到往复调用：本次与上一步在相同两个调用之间来回，已停止";
                // 这条日志是「外部 Agent 是不是在打转」的唯一证据。谁、哪个工具、
                // 因为哪一类护栏被拒，都留在这里——否则拒绝和正常限流在外部看不出区别。
                log.warn("[MCP] 调用被护栏拒绝 tool={} principal={} reason={}",
                        definition.getName(), userId, verdict);
                return McpSchema.CallToolResult.builder()
                        .addTextContent(reason)
                        .isError(true)
                        .build();
            }

            // 确认授权：高危工具只认服务端签发的令牌，不认调用方自填的 confirmed。
            //
            // 原先 confirmed 是协议里的一个布尔，任何拿到 JWT/API Key 的调用方填 true 就能
            // 放行高危操作——服务端签发令牌这条路被整个绕开（实测：直接 tools/call
            // order_cancel + confirmed=true，护栏只记了一条 WARN，执行照常走到下游）。
            // 裸布尔只能表达「调用方声明自己问过用户」，无法证明它真的问过；
            // 令牌能，因为它是服务端在「已经拦下一次调用、把它做成待确认动作」时签发的，
            // 载荷里带着确切的用户、动作与入参，且签名保证没被改过。
            //
            // 令牌与对话侧共用同一个校验器（同一把 AGENT_APPROVAL_SECRET、同一套 TTL 与
            // 用户绑定），所以「用户点了确认卡片」和「外部 Agent 说用户确认了」走的是
            // 同一条信任链——区别只在令牌从哪来，而不在服务端对它的信任程度。
            // 会话维度传 null：MCP 协议没有平台会话概念，而这枚令牌恰恰要能在 MCP 这条路上用。
            // 绑定维度因此落在「用户 + 动作 + 入参 + 有效期 + 签名」上，与「外部入口」能提供的信息一致。
            if (definition.isRequiresConfirmation() && !approvals.verify(approvalToken, userId, null).isPresent()) {
                guard.recordUnverifiedConfirmation(userId, definition.getName());
                log.warn("[MCP] 高危工具 {} 的调用被拒：缺少服务端签发的确认令牌或令牌无效 principal={}",
                        definition.getName(), userId == null ? "anonymous" : userId);
                return McpSchema.CallToolResult.builder()
                        .addTextContent("该操作需要用户确认：请先经由对话链路取得本次操作的确认，" + APPROVAL_TOKEN_ARG
                                + " 携带服务端随确认卡片下发的令牌；不接受调用方自填的 " + CONFIRMED_ARG)
                        .isError(true)
                        .build();
            }

            ToolResult result = registry.execute(new ToolCall(
                    UUID.randomUUID().toString(), definition.getName(), arguments, confirmed, userId));

            String output = result.isSuccess()
                    ? String.valueOf(result.getOutput())
                    : "工具执行失败: " + result.getErrorMessage();
            if (definition.isRequiresConfirmation()) {
                // 高危动作的审计行——与对话侧「这笔退款是谁批的」同一诉求，
                // 走 MCP 的调用也要能回答同一问题。debug 级不够：默认配置下没人看得见。
                log.info("[MCP] 高危工具 {} confirmed={} userId={} success={}",
                        definition.getName(), confirmed, userId, result.isSuccess());
            }
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
            String type = spec.getType() == null ? "string" : spec.getType();
            Map<String, Object> property = new LinkedHashMap<>();
            property.put("type", type);
            property.put("description", spec.getDescription() == null ? "" : spec.getDescription());
            if ("array".equals(type)) {
                property.put("items", Map.of("type", spec.getItems() == null ? "string" : spec.getItems()));
            }
            properties.put(name, property);
            if (spec.isRequired()) {
                required.add(name);
            }
        });
        if (definition.isRequiresConfirmation()) {
            // 只给高危工具加。非高危工具出现这个参数会诱导调用方以为传 true 有什么效果
            Map<String, Object> confirmed = new LinkedHashMap<>();
            confirmed.put("type", "boolean");
            confirmed.put("description", "标记这是一次高危操作调用（协议要求存在；单凭它不构成授权，"
                    + "授权看 " + APPROVAL_TOKEN_ARG + "）");
            properties.put(CONFIRMED_ARG, confirmed);
            required.add(CONFIRMED_ARG);

            // 真正的授权凭据：服务端签发的确认令牌。校验不通过会被 callHandler 拒绝执行
            Map<String, Object> approvalToken = new LinkedHashMap<>();
            approvalToken.put("type", "string");
            approvalToken.put("description", "服务端在拦下一次高危调用时随确认卡片下发的签名令牌；"
                    + "必须先向用户取得明确确认、再原样回传它。不接受调用方自填的 " + CONFIRMED_ARG);
            properties.put(APPROVAL_TOKEN_ARG, approvalToken);
            required.add(APPROVAL_TOKEN_ARG);
        }

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        return schema;
    }

    /**
     * 宽松解析确认信号：JSON boolean true 或字符串 "true"（部分 MCP 客户端把 boolean 序列化成字符串）。
     * 其余一切取值（含缺失）都是未确认。
     */
    static boolean parseConfirmed(Object raw) {
        return Boolean.TRUE.equals(raw) || "true".equalsIgnoreCase(String.valueOf(raw));
    }

    /** 取字符串参数；非字符串一律当作缺失——令牌是字符串，其它类型没有合法解释 */
    private static String stringArg(Object raw) {
        return raw instanceof String text ? text : null;
    }

}
