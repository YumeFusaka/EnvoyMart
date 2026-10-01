package yumefusaka.envoymart.orderservice.mq;

import java.util.List;

/**
 * 订单已支付，在状态真正从「待支付」推进到「已支付」之后发布。
 * <p>
 * <b>它卖的是销量。</b>商品服务收到后按订单行累加 {@code product_spu.sales}，
 * 用于商品卡展示与「按销量排序」。销量不能现算（那是跨库的 COUNT），
 * 所以必须在产生事实的那一刻把它播出去。
 * <p>
 * <b>与 {@link PaymentCompletedEvent} 的区别是刻意的</b>：那个是支付渠道回调成功，
 * 由 payment-service 发、order-service 收；这个是订单状态真的变成已支付。
 * 两者之间隔着一次状态机判断——已关闭的订单收到支付会走自动退款、根本不会变成已支付，
 * 那笔钱不该计入销量。从那边直接发事件就会把退款订单也算进去。
 * <p>
 * 粒度是 {@code spuId} 而不是 {@code skuId}：销量挂在商品（SPU）上——
 * 用户看到的是「维生素 D3 卖了多少」，不是「90 粒装卖了多少」。
 */
public record OrderPaidEvent(Long orderId, String orderNo, List<Item> items) {

    /**
     * 一条订单行。
     * <p>
     * 这里用 record 而不是复用 {@link OrderItemEvent}：那个事件带的是名称、规格、价格快照，
     * 是给「订单详情」这类要还原当时样子的消费者看的；这个只关心「哪一行、哪个商品、几件」。
     * 把两者合成一个，等于让销量消费者去依赖一堆它根本不会读的字段。
     *
     * @param orderItemId 订单行 id。<b>它才是销量的去重键</b>——
     *                    拿 (orderId, spuId) 去重时，一笔订单里买两个规格会被当成重复投递丢掉
     */
    public record Item(Long orderItemId, Long spuId, Integer quantity) {
    }
}
