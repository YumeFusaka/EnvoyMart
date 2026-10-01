package yumefusaka.envoymart.productservice.mq;

import java.util.List;

/**
 * 订单已支付，由 order-service 在状态真正从「待支付」推进到「已支付」之后发布，
 * 带上订单行 —— 商品的销量就是从这里累加的。
 * <p>
 * <b>与 {@code PaymentCompletedEvent} 不是一回事</b>，虽然名字听起来很像：
 * 那个说的是「支付渠道回调成功了」，由 payment-service 发、order-service 收；
 * 这个说的是「订单状态真的变成已支付了」。两者之间隔着 order-service 的一次状态机判断——
 * 已关闭的订单收到支付会走自动退款、根本不会变成已支付，那笔钱不该计入销量。
 * <p>
 * 事件里带的是<b>订单行</b>而不是「哪个商品加几件」：消费方自己去累加，
 * 发送方不需要知道商品服务内部是怎么记这个数的。
 */
public record OrderPaidEvent(Long orderId, String orderNo, List<Item> items) {

    /**
     * 一条订单行。
     * <p>
     * 一行为空或数量非正时消费方会跳过，这里不加 {@code @NotNull} —— 坏消息应该被记录并跳过，
     * 不是让反序列化失败。
     *
     * @param orderItemId 订单行 id，销量的去重键。拿 (orderId, spuId) 去重时，
     *                    一笔订单里同一个商品买两个规格会被当成重复投递丢掉——销量少算且毫无动静
     */
    public record Item(Long orderItemId, Long spuId, Integer quantity) {
    }
}
