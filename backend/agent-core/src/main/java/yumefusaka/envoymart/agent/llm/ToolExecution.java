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
    private java.util.List<yumefusaka.envoymart.agent.rag.DocumentChunk> evidence;
    /**
     * 这次调用<b>确立的业务事实</b>，形如 {@code 状态 → 已支付}、{@code 应付金额 → ¥128.00}。
     * <p>
     * 由工具自己声明，不由上层从 {@link #output} 反解——输出文案是给人看的，
     * 改一个字就会让解析它的正则悄悄失效，而失效的校验看起来和通过一模一样。
     * 声明出来的事实供 {@code ToolFactVerifier} 拿回答逐条核对。
     */
    private java.util.Map<String, String> facts;

    /**
     * 这次调用<b>返回过哪些业务实体</b>——商品编号与名称这类「回答里提到就应该是真的」的东西。
     * <p>
     * 与 {@link #facts} 是两条不同的线：facts 核对「取值对不对」
     *（订单状态写错成已发货），entities 核对「这个东西存不存在」
     *（回答里推荐了一个从没搜到的商品）。前者是写错，后者是编造。
     * <p>消费者是 {@code ToolFactVerifier} 的实体校验。
     */
    private java.util.List<String> entities;
}
