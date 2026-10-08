package yumefusaka.envoymart.aiservice.model;

import yumefusaka.envoymart.contract.OrderItemResponse;

import java.util.List;

/**
 * 待支付订单与下单时的商品快照。
 * <p>
 * 卡片同时支持核对订单与商品明细：
 * <ul>
 *   <li>{@code orderId} —— <b>跳转参数</b>。收银台（{@code /payment?orderId=…}）
 *       认的是订单主键，不是订单号；两者都下发是因为它们服务不同的人：</li>
 *   <li>{@code orderNo} —— <b>给人核对的凭据</b>。用户在订单列表、在客服对话里看到的
 *       都是这个单号，卡片上必须显示它，否则用户没法确认「付的是不是我刚下的那一单」；</li>
 *   <li>{@code payAmount} —— 卡片上最大的那个数字；</li>
 *   <li>{@code expireAt} —— 回答「我还有多久」。它来自订单服务而不是前端算的：
 *       前端算会和服务端实际关闭订单的时刻对不上，而那个不一致的后果是
 *       用户以为还能付、点了却失败。</li>
 * </ul>
 *
 * @param orderId   订单主键，跳转 {@code /payment?orderId=…} 的唯一参数
 * @param orderNo   订单编号，展示用（用户核对的依据）
 * @param payAmount 应付金额（分）。用分而不是元：中间任何一步换成浮点都可能丢掉一分钱
 * @param expireAt  支付截止时间（ISO-8601 字符串）。订单服务没给时为 null ——
 *                  卡片就不显示倒计时，不编一个
 * @param items 下单时的商品名称、规格、数量与价格快照，不使用当前目录覆盖
 */
public record PendingPayment(Long orderId, String orderNo, Long payAmount, String expireAt,
                             List<OrderItemResponse> items) {
    public PendingPayment {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
