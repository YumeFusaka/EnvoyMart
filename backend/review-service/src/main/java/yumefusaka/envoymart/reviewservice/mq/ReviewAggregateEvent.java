package yumefusaka.envoymart.reviewservice.mq;

import java.math.BigDecimal;

/**
 * 商品评分聚合变更事件。
 * <p>
 * <b>携带的是全量聚合值，不是增量。</b>增量（{@code review_count += 1}）在消息重复投递时会
 * 把同一条评价数两次，而消费者无法分辨第二次是"真的又有一条"还是"同一条又来了"。
 * 全量值没有这个问题：重投一次写进去的还是同一个数。
 * <p>
 * 代价是发送方要先把聚合算出来（因为它得看到自己刚插入的那一行）。这是划算的——
 * 评价提交本来就是低频操作，多一次按 spu_id 的索引聚合查询。
 *
 * <b>版本号与全量值是配套的</b>：全量快照消除了「重投算两次」，但消除不了「乱序」——
 * 两条几乎同时发出的快照，后到的可能是旧值。带上「这份快照对应的最大评价 id」之后，
 * 消费者只接受更新的那一份，乱序到达的旧快照被丢弃。
 *
 * @param spuId       商品 id
 * @param ratingAvg   已发布评价的平均分，保留一位小数；没有评价时为 0
 * @param reviewCount 已发布评价条数
 * @param version     这份快照对应的最大评价 id；没有任何已发布评价时为 0
 */
public record ReviewAggregateEvent(Long spuId, BigDecimal ratingAvg, long reviewCount, long version) {
}
