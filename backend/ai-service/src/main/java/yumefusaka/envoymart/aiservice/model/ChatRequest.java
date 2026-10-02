package yumefusaka.envoymart.aiservice.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class ChatRequest {

    /**
     * 会话号由客户端生成。格式校验必须在**写入路径**上，与查询/删除接口同一把尺子：
     * 只护读不护写的话，一个含 {@code |} 或冒号的 sessionId 会写出侧栏点不开（400）、
     * 也删不掉（400）的死会话，还会与短期记忆窗口的 `userId|sessionId` 拼键法互相污染。
     */
    @NotBlank
    @Pattern(regexp = "^[A-Za-z0-9_-]{1,100}$", message = "会话标识不合法")
    private String sessionId;
    @NotBlank
    private String message;
    private Long contextOrderId;

    /**
     * 用户确认高危操作时，由上一轮响应下发、本轮原样带回的签名令牌。
     * <p>
     * 它<b>不是</b>一个「已确认」的开关：令牌里签着服务端自己写下的那批调用
     * （工具名 + 入参），执行的就是那一批，客户端改不了也换不掉。空值即普通一轮。
     */
    @Pattern(regexp = "^[A-Za-z0-9_.=-]{1,8192}$", message = "确认令牌不合法")
    private String approvalToken;

    /**
     * 本轮是「重新生成」：同一个问题再答一次。
     * <p>
     * 置位时服务端<b>不再新增用户轮次</b>，而是把会话里最后一条助手答复就地改写——
     * 界面上重新生成是一个原地替换的动作，历史也必须同构：没有这个标志，
     * 每点一次重新生成，侧栏里就多出一对完全重复的问答。
     * <p>
     * 用户消息原样重发（{@code message} 照常带上），回答走的是与首次提问完全相同的链路——
     * 服务端不缓存也不重放旧答案，「重新生成」的字面意思就是重新跑一遍。
     */
    private boolean regenerate;
}
