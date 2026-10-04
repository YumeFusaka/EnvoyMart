package yumefusaka.envoymart.productservice.mq;

import java.math.BigDecimal;

/**
 * 商品的评分聚合快照，由 review-service 在提交评价、隐藏、恢复之后发布。
 * <p>
 * <b>是全量值而不是增量。</b>增量在消息重投时会把同一条评价数两次，而消费者分辨不出
 * 第二次是「真的又来了一条」还是「同一条又来了」。全量值没有这个问题：重投一次
 * 写进去的还是同一个数，第二次甚至能直接短路掉。
 * <p>
 * <b>顺序问题由 {@code version} 兜住</b>：同一商品的两条聚合消息可能乱序到达，
 * 后到的旧快照会覆盖新值。发送方带上「这份快照对应的最大评价 id」，消费者只在
 * {@code version} 比库里那份更新时才写——乱序到达的旧快照自然被丢弃。
 * <p>
 * <b>这个字段必须在生产者与消费者两侧同时加。</b>两个服务各有一份本 record 的副本，
 * 只改一侧时：生产侧多一个字段、消费侧没有，Jackson 会静默忽略它（不报错）；
 * 反向则编译不过。字段加了而没生效，症状是「乱序照旧覆盖」，没有任何日志。
 *
 * @param version 这份快照对应的最大评价 id；老版本生产者发的消息里缺这个字段时为 0
 */
public record ReviewAggregateEvent(Long spuId, BigDecimal ratingAvg, long reviewCount, long version) {
}
