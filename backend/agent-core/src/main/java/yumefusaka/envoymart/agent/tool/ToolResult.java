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
@Builder(toBuilder = true)
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

    /**
     * 这次调用从进入注册中心到返回的墙钟耗时（毫秒）。
     * <p>
     * 由 {@code ToolRegistry} 在出口统一填，工具自己不测——三个工具各测一遍，
     * 必然有的测有的不测，而且各测各的就把「注册中心的开销」漏在外头。
     * <p>
     * 之所以要它：轨迹上只有工具名和入参，看不出「这个商品检索扫了 3 秒」。
     * 一次问话慢在哪，只能靠这串数字定位。
     */
    private long latencyMs;

    /**
     * 这次调用<b>确立了什么业务事实</b>，形如 {@code 状态 → 已支付}、{@code 应付金额 → ¥128.00}。
     * <p>
     * 由工具自己声明。工具是唯一知道自己返回了什么的地方——让它在返回结果时顺手写清楚，
     * 比让上层的校验器去猜 {@code 应付金额：¥128.00} 这行文本的格式要稳：输出文案改一个字，
     * 解析它的正则就悄悄失效，而失效的校验和通过看起来是一模一样的。
     * <p>
     * 消费者是 {@code ToolFactVerifier}：回答里凡是主动用到这些标签的地方，取值必须与这里一致。
     * 只声明<b>可机器比对</b>的事实（金额、状态、单号），不要塞整段描述——
     * 比对不了的东西进这里，只会让校验器变成摆设。
     */
    private java.util.Map<String, String> facts;
}
