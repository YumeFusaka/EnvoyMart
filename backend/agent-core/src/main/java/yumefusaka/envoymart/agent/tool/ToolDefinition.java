package yumefusaka.envoymart.agent.tool;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * 工具元数据 —— 描述工具的签名、参数、用途，供 LLM 识别调用。
 */
@Data
@Builder
public class ToolDefinition {
    private String name;
    private String description;
    private Map<String, ParameterSpec> parameters;

    /**
     * 是否为高危操作（退款、取消订单、扣款等）。
     * 高危工具必须由用户显式确认后才执行，避免模型自主触发不可逆操作。
     */
    @Builder.Default
    private boolean requiresConfirmation = false;

    /** 写操作的副作用策略。默认幂等，兼容既有高危工具并要求新工具显式选择。 */
    @Builder.Default
    private IdempotencyPolicy idempotencyPolicy = IdempotencyPolicy.IDEMPOTENT;

    /** 工具是否接受执行器注入的 operationId；仅用于 schema/契约说明。 */
    @Builder.Default
    private boolean acceptsOperationId = false;

    public enum IdempotencyPolicy {
        IDEMPOTENT,
        NON_IDEMPOTENT,
        NONE
    }

    @Data
    @Builder
    public static class ParameterSpec {
        private String type;         // string / integer / number / boolean / array
        private String description;
        private boolean required;

        /**
         * 数组元素类型，仅 {@code type="array"} 时有意义；缺省 string。
         * <p>
         * <b>不含它生成的 schema 是「array 但没有 items」</b>——有的模型直接拒收这个 schema，
         * 有的会自己猜元素类型。猜错的代价不在调用那一刻暴露：参数校验只看必填项在不在，
         * 形状不对的参数会一路走到工具内部才炸，而那时的报错已经离原因很远了。
         * <p>
         * 元素目前只用到 string（一串「属性名:属性值」），所以不做成嵌套的 Schema。
         */
        private String items;
    }
}
