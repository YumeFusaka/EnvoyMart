package yumefusaka.envoymart.agent.llm;

import lombok.Builder;
import lombok.Data;

/**
 * 一次工具调用的执行记录 —— 供前端展示调用轨迹与排障。
 */
@Data
@Builder
public class ToolExecution implements java.io.Serializable {

    /** 工具名 */
    private String tool;
    /** 调用入参（JSON 字符串） */
    private String input;
    /** 工具返回的文本结果 */
    private String output;
    private boolean success;
    /**
     * 跑通了但什么都没查到——见 {@code ToolResult#noData}。
     * <p>
     * <b>轨迹上的成/败二态会把这一种压成「成功」</b>：查一个不存在的订单，工具确实跑通了、
     * 也返回了「没有找到」，只是什么都没查到。界面上亮一个绿标「成功」，
     * 而同一张卡片上的回答写着「没查到物流信息」——用户看到的是自相矛盾。
     * 它也不该被压成「失败」：失败是这次没做成，该重试；无结果是这件事问到了、
     * 答案是「没有」。所以第三态必须一路带到界面。
     */
    private boolean noData;
    /** 耗时（毫秒），由 {@code ToolRegistry} 在出口填 */
    private long latencyMs;
    /** 结构化结果，便于上层做二次加工（如抽取推荐商品） */
    private Object rawData;
}
