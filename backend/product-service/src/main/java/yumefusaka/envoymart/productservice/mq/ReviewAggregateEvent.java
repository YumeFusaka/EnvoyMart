package yumefusaka.envoymart.productservice.mq;

import java.math.BigDecimal;

/**
 * 商品的评分聚合快照，由 review-service 在提交评价、隐藏、恢复之后发布。
 * <p>
 * <b>是全量值而不是增量。</b>增量在消息重投时会把同一条评价数两次，而消费者分辨不出
 * 第二次是「真的又来了一条」还是「同一条又来了」。全量值没有这个问题：重投一次
 * 写进去的还是同一个数，第二次甚至能直接短路掉。
 * <p>
 * <b>这也意味着发送方的顺序会影响最终值</b>：同一商品的两条聚合消息如果乱序到达，
 * 后到的旧快照会覆盖新值。发生条件是「同一商品的两条评价几乎同时提交」，
 * 而下一次该商品的任何评价都会把它纠正回来——代价与彻底解决它（在消息里带版本号、
 * 并在商品表上留一列存版本）不成比例，如实记在待改进清单里。
 */
public record ReviewAggregateEvent(Long spuId, BigDecimal ratingAvg, long reviewCount) {
}
