package yumefusaka.envoymart.productservice.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.dao.DuplicateKeyException;
import yumefusaka.envoymart.productservice.mq.ReviewAggregateEvent;
import yumefusaka.envoymart.productservice.entity.ProductSalesLedgerEntity;
import yumefusaka.envoymart.productservice.mapper.ProductSalesLedgerMapper;
import yumefusaka.envoymart.productservice.mapper.ProductSpuMapper;
import yumefusaka.envoymart.productservice.mq.OrderPaidEvent;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 销量台账的粒度与幂等。
 * <p>
 * 这两件事错了都不报错，只是数字不动：
 * <ul>
 *   <li>粒度用 {@code (order_id, spu_id)} 去重时，一笔订单里同一个商品买两个规格，
 *       第 2、3 行会被当成「消息重投」丢掉——实测下单三件销量只 +1</li>
 *   <li>幂等失效时重投一条消息销量就多算一次，而且只会在对账时才发现</li>
 * </ul>
 * 真实环境里判这两件事的是 {@code product_sales_ledger} 的主键约束，
 * 所以这里的假台账必须<b>也按主键判重</b>，否则测的是一个不存在的宽松环境。
 */
class ProductAggregateServiceTest {

    private ProductSpuMapper spuMapper;
    private ProductSalesLedgerMapper ledgerMapper;
    private ProductDerivedRefresh derivedRefresh;
    private ProductAggregateService service;
    /** 假商品表上那一个 aggregate_version，模拟「库里的当前版本」 */
    private long storedVersion;

    /** 假台账：用「已插过的 order_item_id」模拟主键唯一约束，重复插入就抛 DuplicateKeyException */
    private final Set<Long> ledgerKeys = new HashSet<>();

    @BeforeEach
    void setUp() {
        spuMapper = mock(ProductSpuMapper.class);
        ledgerMapper = mock(ProductSalesLedgerMapper.class);
        derivedRefresh = mock(ProductDerivedRefresh.class);
        ledgerKeys.clear();

        when(ledgerMapper.insert(any(ProductSalesLedgerEntity.class))).thenAnswer(invocation -> {
            ProductSalesLedgerEntity row = invocation.getArgument(0);
            if (!ledgerKeys.add(row.getOrderItemId())) {
                throw new DuplicateKeyException("Duplicate entry '" + row.getOrderItemId() + "' for key 'PRIMARY'");
            }
            return 1;
        });
        when(spuMapper.increaseSales(anyLong(), anyInt())).thenReturn(1);

        // 条件更新的假实现：只有 incoming 比库里新才写、才返回 1。
        // 真实环境里这个判据在 UPDATE 的 where 子句里由数据库原子执行，
        // 假实现必须复刻「原子 + 按版本裁决」这两点，否则测的是一个不存在的宽松环境
        storedVersion = 0L;
        when(spuMapper.applyReviewAggregate(anyLong(), any(), anyInt(), anyLong()))
                .thenAnswer(invocation -> {
                    long incoming = invocation.getArgument(3);
                    if (incoming <= storedVersion) {
                        return 0;
                    }
                    storedVersion = incoming;
                    return 1;
                });

        service = new ProductAggregateService(spuMapper, ledgerMapper, derivedRefresh);
    }

    @Test
    void 同一订单里同一商品的两行都要计入销量() {
        OrderPaidEvent event = new OrderPaidEvent(150L, "YS150", List.of(
                new OrderPaidEvent.Item(168L, 1L, 1),
                new OrderPaidEvent.Item(169L, 1L, 1),
                new OrderPaidEvent.Item(170L, 1L, 1)));

        service.applyPaidOrder(event);

        // 三行三件：曾经只加了 1，因为后两行撞了 (order_id, spu_id) 被判成重复投递
        verify(spuMapper, times(3)).increaseSales(1L, 1);
        assertThat(ledgerKeys).containsExactlyInAnyOrder(168L, 169L, 170L);
        // 派生刷新按商品去重：同一个 SPU 的三行只刷一次缓存与索引
        verify(derivedRefresh, times(1)).afterCommit(ArgumentMatchers.<Collection<Long>>any());
    }

    @Test
    void 同一条订单行重投不再计销量() {
        OrderPaidEvent event = new OrderPaidEvent(150L, "YS150", List.of(
                new OrderPaidEvent.Item(168L, 1L, 2)));

        service.applyPaidOrder(event);
        service.applyPaidOrder(event);

        verify(spuMapper, times(1)).increaseSales(1L, 2);
    }

    @Test
    void 订单行缺少id时跳过该行而不是整条消息失败() {
        // 老版本生产者发的消息里没有 orderItemId。毒消息进死信队列就再也补不回来了，
        // 而同一批里其余的行是好的——记一条日志跳过，别让整批陪葬
        OrderPaidEvent event = new OrderPaidEvent(150L, "YS150", List.of(
                new OrderPaidEvent.Item(null, 1L, 1),
                new OrderPaidEvent.Item(169L, 1L, 1)));

        service.applyPaidOrder(event);

        verify(spuMapper, times(1)).increaseSales(1L, 1);
        verify(ledgerMapper, times(1)).insert(any(ProductSalesLedgerEntity.class));
    }

    @Test
    void 商品不存在时不影响同一批里其它行() {
        when(spuMapper.increaseSales(eq(404L), anyInt())).thenReturn(0);
        OrderPaidEvent event = new OrderPaidEvent(150L, "YS150", List.of(
                new OrderPaidEvent.Item(168L, 404L, 1),
                new OrderPaidEvent.Item(169L, 1L, 1)));

        service.applyPaidOrder(event);

        verify(spuMapper).increaseSales(404L, 1);
        verify(spuMapper).increaseSales(1L, 1);
    }

    @Test
    void 空消息不产生任何写入() {
        service.applyPaidOrder(new OrderPaidEvent(150L, "YS150", List.of()));

        verify(ledgerMapper, never()).insert(any(ProductSalesLedgerEntity.class));
        verify(spuMapper, never()).increaseSales(anyLong(), anyInt());
        verifyNoInteractions(derivedRefresh);
    }

    /**
     * U58：乱序到达的旧快照不能覆盖新值。
     * <p>
     * RabbitMQ 不保证同一队列内两个消费者的处理顺序。此前写入不带版本，后到的旧快照会直接
     * 盖掉新值，而且<b>覆盖得毫无痕迹</b>——商品页从此停在错值上，直到下一次有人评价。
     * 这里构造「新快照先到、旧快照后到」，断言旧的那条写不进去。
     */
    @Test
    void 旧版本快照后到时不覆盖新值() {
        service.applyReviewAggregate(new ReviewAggregateEvent(1L, new BigDecimal("4.80"), 10, 100L));
        // 后到的这条版本更低：它是「评价少两条」时的旧快照
        service.applyReviewAggregate(new ReviewAggregateEvent(1L, new BigDecimal("5.00"), 8, 80L));

        // 两条都发起了条件更新（判据在 SQL 里，不在应用层的 if 里），但只有第一条真正写进去：
        // 第二条命中 where 不成立、影响行数 0，被丢弃
        assertThat(storedVersion).isEqualTo(100L);
        // 缓存/索引只刷新一次 —— 被丢弃的旧快照不该触发下游重算
        verify(derivedRefresh, times(1)).afterCommit(1L);
    }

    /** 同一条消息重投：版本相等也不该再写一次，更不该刷新缓存 */
    @Test
    void 同版本重投不重复写入() {
        service.applyReviewAggregate(new ReviewAggregateEvent(1L, new BigDecimal("4.80"), 10, 100L));
        service.applyReviewAggregate(new ReviewAggregateEvent(1L, new BigDecimal("4.80"), 10, 100L));

        verify(derivedRefresh, times(1)).afterCommit(1L);
    }

    /** 版本更高的新快照正常写入 */
    @Test
    void 新版本快照正常覆盖旧值() {
        service.applyReviewAggregate(new ReviewAggregateEvent(1L, new BigDecimal("4.80"), 10, 100L));
        service.applyReviewAggregate(new ReviewAggregateEvent(1L, new BigDecimal("4.50"), 12, 120L));

        verify(spuMapper, times(2)).applyReviewAggregate(eq(1L), any(), anyInt(), anyLong());
        assertThat(storedVersion).isEqualTo(120L);
    }
}
