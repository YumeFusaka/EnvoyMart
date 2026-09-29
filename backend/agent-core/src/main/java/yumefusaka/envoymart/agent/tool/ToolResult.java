package yumefusaka.envoymart.agent.tool;

import lombok.Builder;
import lombok.Data;

/**
 * 工具执行结果。
 * <p>
 * <b>「跑通了但没查到」是第三种结局，必须能被说出来。</b>只有成功与失败两态时，
 * 查询条件没匹配上任何东西会被归进成功——而它和「查到了」对下游完全一样。
 * 执行图因此永远感知不到「这次没做好」，{@code replan → act} 那个环只能在工具
 * 真的报错时才转，自我纠偏等于残废一半。见 {@link #noData}。
 */
@Data
@Builder
public class ToolResult {
    private boolean success;
    private String output;          // 文本结果
    private Object rawData;         // 结构化数据（可选）
    private String errorMessage;
    /** 因缺少用户确认而未执行（区别于执行失败） */
    @Builder.Default
    private boolean pendingApproval = false;
    /**
     * 跑通了，但什么都没查到：查询条件没匹配上，<b>换个条件可能就有</b>。
     * <p>
     * 与「失败」不是一回事——失败是这次没做成，该重试或换手段；
     * 无结果是这件事问到了、但答案是「没有」。与「查到」也不是一回事——
     * 那是<b>事实</b>，可以直接作答。所以它既不该被报成失败（会把「确实没有这个商品」
     * 说成系统出问题），也不该被悄悄当成成功（那是这一层想解决的病）。
     * <p>
     * <b>只标真正「换条件可能就有」的那些分支。</b>按精确编号查不到、订单号不存在、
     * 订单尚未发货——这些是事实，不是没查到，标它只会让执行图白白重规划一轮。
     */
    @Builder.Default
    private boolean noData = false;
}
