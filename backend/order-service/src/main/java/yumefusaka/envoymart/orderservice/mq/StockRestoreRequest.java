package yumefusaka.envoymart.orderservice.mq;

/**
 * 库存回补的补偿请求 —— <b>一次没做成的回补，登记成待重试的事实</b>。
 * <p>
 * 它不叫「事件」而叫「请求」：与本服务发出去的其它消息不同，这条的语义是
 * 「我必须做成这件事」，不是「通知下游发生了什么」。消费方（本服务自己）
 * 重试的是同一个动作，而不是把消息转给第三方。
 * <p>
 * 字段是回补动作的完整输入，不是一个 id：消费时不该再去查订单行 ——
 * 订单行可能已经被改过（或者订单和商品的关系已经变了），
 * 而重试要还原的是「当时要补多少」这个事实。
 */
public record StockRestoreRequest(Long orderId, String orderNo, Long skuId, Integer quantity, String remark) {
}