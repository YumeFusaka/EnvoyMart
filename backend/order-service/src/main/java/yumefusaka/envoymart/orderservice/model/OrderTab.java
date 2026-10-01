package yumefusaka.envoymart.orderservice.model;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 「我的订单」的状态页签。
 * <p>
 * <b>页签 → 状态集合的映射只此一份。</b>此前它写在前端：列表自己 {@code filter}、
 * 计数又各 {@code filter} 一遍，页签筛选与角标计数是两段代码对同一件事的各一次表述。
 * 一旦列表改成分页（服务端筛），两段就必须一致 —— 与其在两处同步，不如让服务端
 * 一处持有：列表按它筛，计数也按它折算。
 * <p>
 * 页签不是状态本身，是「用户想找什么」：用 6 个页签而不是枚举出全部 9 个状态，
 * 因为用户要找的是「在路上的」而不是 REFUNDING 与 REFUNDED 的区别。
 * 页签的中文写法在这里（与 {@link OrderStatus#text()} 同一个理由）：状态集合会变，
 * 散在客户端的那份迟早与后端对不上。
 */
public enum OrderTab {

    /** 不筛状态。空集合即「全部」，不是「一个都不匹配」 */
    ALL("全部", EnumSet.noneOf(OrderStatus.class)),
    CREATED("待付款", EnumSet.of(OrderStatus.CREATED)),
    PAID("待发货", EnumSet.of(OrderStatus.PAID)),
    SHIPPED("待收货", EnumSet.of(OrderStatus.SHIPPED)),
    /** 已收货与已完成对用户是同一件事：货到手了 */
    RECEIVED("已完成", EnumSet.of(OrderStatus.RECEIVED, OrderStatus.COMPLETED)),
    /** 退款中与已退款都从「取消」这条路进来，用户找的是「我那笔退了的钱」 */
    CANCELLED("退款/取消", EnumSet.of(OrderStatus.CANCELLED, OrderStatus.CLOSED,
            OrderStatus.REFUNDING, OrderStatus.REFUNDED));

    private final String label;
    private final Set<OrderStatus> statuses;

    OrderTab(String label, Set<OrderStatus> statuses) {
        this.label = label;
        this.statuses = statuses;
    }

    public String label() {
        return label;
    }

    public boolean isAll() {
        return statuses.isEmpty();
    }

    /** 传给 SQL 的 {@code in} 条件。名字而非枚举本身：库里存的就是名字 */
    public Set<String> statusNames() {
        return statuses.stream().map(Enum::name).collect(Collectors.toSet());
    }

    /**
     * 按状态计数折算本页签的数量。
     * <p>
     * 全部 = 各行相加，而不是另外查一次 {@code count(*)}：多查一次就多一个时间点，
     * 「全部」与四类之和对不上时，谁也不知道该信哪个。
     * <p>
     * 库里若有本枚举不认识的状态（脏数据），它计入「全部」但不计入任何具体页签 ——
     * 宁可让总和对不上被发现，也不能把它悄悄塞进某个页签。
     */
    public long countIn(Map<String, Long> countByStatus) {
        if (isAll()) {
            return countByStatus.values().stream().mapToLong(Long::longValue).sum();
        }
        return statuses.stream().mapToLong(status -> countByStatus.getOrDefault(status.name(), 0L)).sum();
    }

    /**
     * 解析页签参数。取值为空时回落到全部，<b>但取值非法一律拒绝</b>——
     * 一个拼错的页签若被静默当成「全部」，页面会一本正经地展示全部订单，
     * 而使用的人以为自己是筛过的。
     */
    public static OrderTab parse(String value) {
        if (value == null || value.isBlank()) {
            return ALL;
        }
        try {
            return valueOf(value.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("未知的订单页签：" + value + "，可选值 "
                    + Arrays.toString(values()));
        }
    }
}
