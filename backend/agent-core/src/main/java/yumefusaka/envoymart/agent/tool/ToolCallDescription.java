package yumefusaka.envoymart.agent.tool;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * 工具调用的可读描述 —— <b>确认卡片、日志、测试断言共用的稳定契约</b>。
 * <p>
 * 形如 {@code order_cancel(orderId=12)}：<b>工具名加上真实入参</b>。
 * 这是用户在确认前唯一能看到的东西，所以它必须是<b>待执行操作本身</b>，
 * 而不是模型对它的转述——模型完全可以把自己要做的危险操作描述得很温和，
 * 用户按转述点了确认，等于让模型给自己批了这次授权。带参数而不是只给工具名：
 * 「取消订单」与「取消订单 12」在授权上的区别，正是高危确认存在的理由。
 * <p>
 * <b>为什么单独提出来而不是各拼各的</b>：计划路径（执行前拦整批计划）与
 * ReAct 路径（工具循环内拦截）都要拼这串文本，而前端
 * {@code PendingApprovalCard} 与 {@code actionLabel()} 按固定格式解析它。
 * 两处各拼一份，格式迟早分叉——分叉的症状是确认卡显示不出参数，
 * 而那时用户已经点下了「确认执行」。
 * <p>
 * 参数按 key 排序，保证同一份调用在任何一次运行里拼出同一串文本——否则
 * 日志对比与测试断言都得先做一次集合比较。
 */
public final class ToolCallDescription {

    public static String of(String tool, Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return tool;
        }
        String args = arguments.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(", "));
        return tool + "(" + args + ")";
    }

    private ToolCallDescription() {
    }
}
