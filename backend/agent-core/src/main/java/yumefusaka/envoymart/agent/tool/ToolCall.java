package yumefusaka.envoymart.agent.tool;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * 工具调用请求 —— LLM 决定调用工具时的入参包装。
 */
@Data
@Builder
@AllArgsConstructor
public class ToolCall {
    private String id;
    private String toolName;
    private Map<String, Object> arguments;

    /** 高危工具是否已获得用户确认 */
    @Builder.Default
    private boolean confirmed = false;

    /**
     * 经过认证的用户身份。
     * <p>
     * <b>刻意不放进 {@link #arguments}</b>：arguments 是模型生成的，把身份放进去等于让模型决定
     * "我是谁"——它无从知道真实身份，只能编，而下游会照着这个值做归属校验。
     * 身份由执行上下文注入（网关校验 JWT 后透传，或 MCP 鉴权结果），模型既看不到也改不了。
     * <p>
     * 需要用户身份的工具从 {@code call.getUserId()} 取，而不是从 arguments 里取。
     */
    private String userId;

    public ToolCall(String id, String toolName, Map<String, Object> arguments) {
        this(id, toolName, arguments, false, null);
    }

    public ToolCall(String id, String toolName, Map<String, Object> arguments, boolean confirmed) {
        this(id, toolName, arguments, confirmed, null);
    }

    /**
     * 取经过认证的用户身份；缺失即失败。
     * <p>
     * <b>fail-closed</b>：身份缺失时抛错，绝不回退到 arguments 里的同名参数。
     * 回退就等于把"我是谁"的决定权交回给模型，那正是这个字段要消除的问题。
     */
    public String requireUserId() {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("缺少经过认证的用户身份，无法执行需要用户上下文的操作");
        }
        return userId;
    }
}
