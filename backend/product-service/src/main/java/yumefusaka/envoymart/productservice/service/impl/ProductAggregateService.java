package yumefusaka.envoymart.productservice.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import yumefusaka.envoymart.common.util.Times;
import yumefusaka.envoymart.productservice.entity.ProductSalesLedgerEntity;
import yumefusaka.envoymart.productservice.mapper.ProductSalesLedgerMapper;
import yumefusaka.envoymart.productservice.mapper.ProductSpuMapper;
import yumefusaka.envoymart.productservice.mq.OrderPaidEvent;
import yumefusaka.envoymart.productservice.mq.ReviewAggregateEvent;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 把别人的事实落成商品表上的冗余列：评分均值、评论数、销量。
 * <p>
 * <b>为什么这些值要冗余在 {@code product_spu} 上</b>：它们是商品卡与列表页的一等信息，
 * 而真实来源分散在另外两个库（评价数、订单数）。列表页按评分排序、按销量排序时，
 * 现算意味着每次翻页都要跨库聚合，且那个聚合落不到索引上。冗余是这里唯一可行的做法，
 * 代价是必须有人负责让它跟上——就是这个类。
 * <p>
 * <b>为什么入口是消息而不是 Feign 调用</b>：评价与支付都发生在"用户已经做完了一件事"之后。
 * 同步调用会把商品服务的可用性绑到这两条链路上——它抖一下，用户要么白写一条评价、
 * 要么这笔支付的成功回调被重试。消息让发送方只管把事实说出去，这边什么时候跟上都行。
 * <p>
 * 三个写入方法都跑在事务里，且把「刷新缓存与索引」注册到提交之后
 * （{@link ProductDerivedRefresh#afterCommit}）。在事务里刷新的话，一旦回滚，
 * 搜索页上会留下一个库里并不存在的商品状态。
 */
@Slf4j
@Service
public class ProductAggregateService {

    private final ProductSpuMapper spuMapper;
    private final ProductSalesLedgerMapper ledgerMapper;
    private final ProductDerivedRefresh derivedRefresh;

    public ProductAggregateService(ProductSpuMapper spuMapper,
                                   ProductSalesLedgerMapper ledgerMapper,
                                   ProductDerivedRefresh derivedRefresh) {
        this.spuMapper = spuMapper;
        this.ledgerMapper = ledgerMapper;
        this.derivedRefresh = derivedRefresh;
    }

    /**
     * 应用评价聚合快照。
     * <p>
     * <b>商品不存在时只记日志、不抛异常。</b>抛出去会让消息进死信队列，而这类消息
     * 永远处理不了——商品删了就是删了。死信队列该装的是"本该能处理但失败了"的消息，
     * 混进一批注定失败的消息，真正需要人看的那些会被淹掉。
     * <p>
     * <b>写入带版本判据，而不是先查再改。</b>全量快照消除了「重投算两次」，但消除不了
     * 「乱序」：同一商品的两条消息几乎同时发出时，后到的可能是旧值。这里把
     * {@code aggregate_version < incoming} 写进 UPDATE 的 where，由数据库在一次原子操作里
     * 裁决，旧快照自然被丢弃。写成「先 select 比一下版本、再 update」在并发下两个线程都会
     * 读到同一个旧版本、都判定自己更新，等于没有判据。
     */
    @Transactional
    public void applyReviewAggregate(ReviewAggregateEvent event) {
        if (event == null || event.spuId() == null) {
            log.warn("[MQ] 评价聚合消息缺少 spuId，跳过: {}", event);
            return;
        }
        Long spuId = event.spuId();
        BigDecimal incomingAvg = event.ratingAvg() == null ? BigDecimal.ZERO : event.ratingAvg();
        int incomingCount = (int) Math.min(Integer.MAX_VALUE, Math.max(0, event.reviewCount()));
        long incomingVersion = Math.max(0L, event.version());

        int updated = spuMapper.applyReviewAggregate(spuId, incomingAvg, incomingCount, incomingVersion);
        if (updated == 0) {
            // 两种可能，都不该报错也不该刷缓存：
            // ① 商品不存在（删了就是删了，重试不会变好）
            // ② 版本没更新 —— 乱序到达的旧快照，或同一条消息重投（版本相等，不满足 < ）
            log.debug("[MQ] 评价聚合未写入，可能商品不存在或快照版本未变新: spuId={} avg={} count={} version={}",
                    spuId, incomingAvg, incomingCount, incomingVersion);
            return;
        }
        log.info("[MQ] 商品评分聚合已更新: spuId={} avg={} count={} version={}",
                spuId, incomingAvg, incomingCount, incomingVersion);
        derivedRefresh.afterCommit(spuId);
    }

    /**
     * 应用一笔已支付订单：给订单里每个商品累加销量。
     * <p>
     * 幂等靠 {@code product_sales_ledger} 的 {@code order_item_id} 唯一约束，
     * 而不是"先查有没有再插"：后者在消息并发重投时两个消费者会同时查到"没有"，
     * 于是各加一次。让数据库来判这件事，唯一约束是原子的。
     * <p>
     * <b>去重键必须是订单行而不是 (订单, 商品)</b>：一笔订单里同一个商品买两个规格是
     * 再普通不过的事，按 (订单, 商品) 去重会把第 2、3 行判成重投丢掉——销量少算且毫无动静。
     */
    @Transactional
    public void applyPaidOrder(OrderPaidEvent event) {
        if (event == null || event.orderId() == null || event.items() == null || event.items().isEmpty()) {
            log.warn("[MQ] 支付完成消息没有可计入的订单行，跳过: {}", event);
            return;
        }
        Set<Long> changed = new LinkedHashSet<>();
        for (OrderPaidEvent.Item item : event.items()) {
            if (item == null || item.orderItemId() == null || item.spuId() == null
                    || item.quantity() == null || item.quantity() <= 0) {
                // 坏消息记录并跳过，而不是让整条消息失败：同一笔订单里其余的行是好的
                log.warn("[MQ] 订单行数据不完整，跳过: orderNo={} item={}", event.orderNo(), item);
                continue;
            }
            if (!recordLedger(event.orderId(), item.orderItemId(), item.spuId(), item.quantity())) {
                // 这一行之前已经计过（消息重投）。销量不能加，但其余行仍要继续处理
                continue;
            }
            int updated = spuMapper.increaseSales(item.spuId(), item.quantity());
            if (updated == 0) {
                log.warn("[MQ] 订单行指向的商品不存在，销量未累加: orderNo={} spuId={}",
                        event.orderNo(), item.spuId());
                continue;
            }
            changed.add(item.spuId());
        }
        if (changed.isEmpty()) {
            return;
        }
        log.info("[MQ] 订单 {} 已计入销量: {} 个商品", event.orderNo(), changed.size());
        derivedRefresh.afterCommit(changed);
    }

    /**
     * 台账登记。
     * <p>
     * 撞唯一约束 = 这一行已经计过，返回 {@code false}，由调用方跳过销量累加。
     * 这是"用数据库约束做幂等"的唯一判据，不额外查一次库——查了也不是原子的。
     */
    private boolean recordLedger(Long orderId, Long orderItemId, Long spuId, int quantity) {
        ProductSalesLedgerEntity ledger = new ProductSalesLedgerEntity();
        ledger.setOrderItemId(orderItemId);
        ledger.setOrderId(orderId);
        ledger.setSpuId(spuId);
        ledger.setQuantity(quantity);
        ledger.setCreatedAt(Times.now());
        try {
            ledgerMapper.insert(ledger);
            return true;
        } catch (DuplicateKeyException e) {
            log.debug("[MQ] 订单行已计入过，跳过: orderItemId={} spuId={}", orderItemId, spuId);
            return false;
        }
    }

}
