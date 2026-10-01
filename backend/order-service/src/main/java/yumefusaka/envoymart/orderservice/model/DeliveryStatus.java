package yumefusaka.envoymart.orderservice.model;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * 物流节点状态。
 * <p>
 * 在引入这个枚举之前，这几个状态只活在一句 Javadoc 注释里
 * （{@code OrderDeliveryEntity} 的 "CREATED / PICKED_UP / IN_TRANSIT / DELIVERING / SIGNED"），
 * 由发货与签收两处各写一个字符串字面量。后果是<b>「没写过的状态」与「写错了的状态」无法区分</b>——
 * 前者可以静默存在半年没人发现，后者会以一条谁也看不懂的轨迹出现在用户面前。
 * <p>
 * <b>这里刻意不定义流转规则。</b>轨迹是<b>事实记录</b>，不是状态机：客服补录的是
 * "发生了什么"，不是"把它推进到哪一步"。派送失败后再次派送、中途退回、甚至先斩后奏的
 * 补录都会让状态回退或重复出现——用状态机的思路去管事实记录，会把真实发生过的事挡在门外。
 * 订单状态（{@link OrderStatus}）是状态机，物流节点不是，两者的约束强度本就该不同。
 */
public enum DeliveryStatus {

    /** 电子面单已生成，等承运商上门揽收 */
    CREATED("电子面单已生成，等待揽收"),
    /** 承运商已揽收 */
    PICKED_UP("包裹已由承运商揽收"),
    /** 运输途中 */
    IN_TRANSIT("包裹已发往下一站"),
    /** 派送中 */
    DELIVERING("包裹正在派送中"),
    /** 已签收 */
    SIGNED("包裹已签收");

    private final String defaultDescription;

    DeliveryStatus(String defaultDescription) {
        this.defaultDescription = defaultDescription;
    }

    /**
     * 不填说明时用的那句话。
     * <p>
     * 让客服补录只需选一个状态：一句话都编不出来的节点，多半也不该录进去。
     * 想写得更具体（"已到达杭州转运中心"）再自己填。
     */
    public String defaultDescription() {
        return defaultDescription;
    }

    /**
     * 解析状态码，非法取值一律拒绝。
     * <p>
     * 报错信息里带上全部合法取值：这个接口只有客服在用，看到 400 时他手上应该
     * 直接有正确答案，而不是去翻代码。取值区分大小写——放宽容错（忽略大小写、
     * 去空格）会让前端字典与后端契约的分叉一直藏着，直到某天多出一个谁也没写过的节点。
     */
    public static DeliveryStatus parse(String value) {
        try {
            return valueOf(value);
        } catch (IllegalArgumentException | NullPointerException e) {
            // null 单独接住：Enum.valueOf(null) 抛的是 NPE，会一路落进兜底变成 500。
            // 缺参数是客户端的错，应当是 400
            throw new IllegalArgumentException("未知的物流状态：" + value
                    + "（可选：" + Arrays.stream(values()).map(Enum::name)
                    .collect(Collectors.joining(" / ")) + "）");
        }
    }
}
