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

    /** 用户是否已确认高危操作（取消订单等），默认未确认 */
    private boolean approved = false;
}
