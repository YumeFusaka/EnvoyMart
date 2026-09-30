package yumefusaka.envoymart.agent.tool;

import java.io.Serial;
import java.io.Serializable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 待用户确认的高危操作 —— <b>结构化的工具名 + 入参</b>，而不是一句可读描述。
 * <p>
 * <b>为什么不能只有那句描述。</b>确认链路原先走的是「服务端拦下 → 前端渲染
 * {@code order_cancel(orderId=12)} → 用户点确认 → 前端带一个请求级布尔 {@code approved=true}
 * 重入」。那个布尔一旦置位，本轮计划里<b>所有</b>高危步骤都放行，而「用户究竟确认了哪一单」
 * 只活在前端的一句话里，服务端不复核——重入轮跑的是模型重新规划出来的计划，
 * 它是否还记得要取消 12 号单，取决于短期记忆里那段对话还在不在。换设备、隔久了点确认、
 * 会话窗口滑过去，卡片还在，点下去却什么也不会发生。
 * <p>
 * 把操作本身变成数据之后，两件事同时成立：确认可以被签名（见 {@link ApprovalTokens}），
 * 执行可以对着一份<b>服务端自己写下的载荷</b>跑，与对话历史彻底解耦。
 * <p>
 * 可读描述仍然要有，但它退成渲染的产物（{@link #describe()}）——展示归展示，执行归执行。
 */
public record PendingAction(String tool, Map<String, Object> arguments) implements Serializable {

    /** LangGraph4j 的检查点会把图状态整个序列化一遍；不可序列化的值会在运行期炸在图里 */
    @Serial
    private static final long serialVersionUID = 1L;

    public PendingAction {
        // 模型生成的参数是任意 JSON，值可能是 null；Map.copyOf 遇到 null 值会直接抛。
        // 防御放在这里而不是调用方：这份数据要进签名载荷，构造失败应当当场发生
        arguments = arguments == null || arguments.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
    }

    public static PendingAction of(String tool, Map<String, Object> arguments) {
        return new PendingAction(tool, arguments);
    }

    /** 展示用描述，形如 {@code order_cancel(orderId=12)}。格式契约见 {@link ToolCallDescription} */
    public String describe() {
        return ToolCallDescription.of(tool, arguments);
    }
}
