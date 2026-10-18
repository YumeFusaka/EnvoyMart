package yumefusaka.envoymart.aiservice.tool;

import yumefusaka.envoymart.aiservice.client.OrderClient;
import yumefusaka.envoymart.contract.OrderResponse;

/**
 * 订单编号的两种形态 —— <b>用户说的</b>与<b>系统认的</b>。
 * <p>
 * <b>为什么需要这一层。</b>用户看见的是 {@code YS2026...}，而详情/物流/售后接口认的是内部数字 id。
 * 原先只有取消工具兼容订单号，其余工具一律 {@code Long.valueOf(...)}，于是
 * 「查一下 YS2026...」直接抛 {@code For input string: "YS..."}——
 * 而系统里又没有「按订单号查」的工具，模型没有任何可执行的出路。
 * <p>
 * <b>解析只写一次。</b>三处工具各写一遍 {@code matches("\\d+")} 迟早会分叉：
 * 一处认带空格的、一处不认；一处把异常吞成 null、一处抛出去。
 */
final class OrderRefs {

    private OrderRefs() {
    }

    /** 数字形态（内部 id）原样解析；其余形态一律按订单号处理。 */
    static boolean isInternalId(Object raw) {
        return raw != null && raw.toString().trim().matches("\\d+");
    }

    /**
     * 把用户给的编号解析成内部 id。
     * <p>
     * 数字直接返回；订单号走一次 {@code by-no} 查询换 id。查不到时抛
     * {@link IllegalArgumentException}——由调用方经 {@link Downstream} 翻成用户能懂的话，
     * 而不是让 {@code For input string} 这种 JVM 异常原文漏到界面上。
     */
    static Long resolveOrderId(OrderClient client, String userId, Object raw) {
        if (raw == null || raw.toString().isBlank()) {
            throw new IllegalArgumentException("缺少订单编号，请提供订单号或订单 ID");
        }
        String text = raw.toString().trim();
        if (text.matches("\\d+")) {
            return Long.valueOf(text);
        }
        OrderResponse order = Downstream.read("订单服务", () -> client.getOrderByNo(userId, text));
        if (order == null || order.getId() == null) {
            throw new IllegalArgumentException("没有找到订单 " + text + "，请确认订单号是否正确");
        }
        return order.getId();
    }
}
