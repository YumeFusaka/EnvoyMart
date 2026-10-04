package yumefusaka.envoymart.productservice.service.impl;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import yumefusaka.envoymart.contract.StockChangeRequest;
import yumefusaka.envoymart.productservice.entity.ProductSkuEntity;
import yumefusaka.envoymart.productservice.entity.StockLogEntity;
import yumefusaka.envoymart.productservice.mapper.ProductSkuMapper;
import yumefusaka.envoymart.productservice.mapper.StockLogMapper;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 库存回补的幂等性 —— 锁住一条实测踩出来的 P0。
 *
 * <p><b>缺陷原貌</b>：订单关闭回补走 MQ，消费抛异常会自动重试；
 * 而 product-service 对同一笔回补没有任何去重，重试几次就把库存补几遍。
 * 实测 {@code stock_log} 里同一 {@code (orderNo, skuId)} 躺着 <b>4 条</b>
 * RESTORE 流水，库存只该加 1 件却加了 4 件 —— 而且全程不报错，
 * 只在某天有人对账时才发现「系统里凭空多出三件货」。
 *
 * <p><b>为什么断言的是「第二次不再加库存」而不是「第二次抛异常」。</b>
 * 重复投递在 MQ 语义下是正常的，不是错误；把它抛成异常会让消息反复重投、
 * 最后进死信，反倒把一条正常路径变成待办。正确行为是<b>静默跳过</b>，
 * 并且留下一条能证明「我知道这是重复」的日志。
 */
class StockRestoreIdempotencyTest {

    private StockChangeRequest request() {
        return StockChangeRequest.builder()
                .skuId(1L).quantity(1).bizType("ORDER").bizId("YS20261004082742012AF29DB")
                .remark("订单关闭回补").build();
    }

    private ProductSkuEntity sku() {
        ProductSkuEntity sku = new ProductSkuEntity();
        sku.setId(1L);
        sku.setSpuId(1L);
        sku.setStock(299);
        return sku;
    }

    @Test
    void 同一条回补消息重投第二次时不再加库存() {
        ProductSkuMapper skuMapper = mock(ProductSkuMapper.class);
        StockLogMapper logMapper = mock(StockLogMapper.class);
        when(skuMapper.restoreStock(anyLong(), anyInt())).thenReturn(1);
        when(skuMapper.selectStock(anyLong())).thenReturn(300);

        // 第一次：写流水成功，认领到手
        when(logMapper.insert(any(StockLogEntity.class))).thenReturn(1);
        StockServiceImpl first = new StockServiceImpl(skuMapper, logMapper, new org.springframework.beans.factory.support.DefaultListableBeanFactory().getBeanProvider(yumefusaka.envoymart.productservice.search.ProductSyncService.class));
        first.restore(request());

        // 第二次：唯一键冲突 —— 这正是数据库在说「这件事已经做过了」
        when(logMapper.insert(any(StockLogEntity.class)))
                .thenThrow(new DuplicateKeyException("uk_stock_log_biz_action"));
        StockServiceImpl second = new StockServiceImpl(skuMapper, logMapper, new org.springframework.beans.factory.support.DefaultListableBeanFactory().getBeanProvider(yumefusaka.envoymart.productservice.search.ProductSyncService.class));
        second.restore(request());

        // 库存只被加过一次 —— 这是本测试唯一真正关心的事
        verify(skuMapper, times(1)).restoreStock(eq(1L), eq(1));
    }

    @Test
    void 带上认领失败时不回填流水也不动库存() {
        ProductSkuMapper skuMapper = mock(ProductSkuMapper.class);
        StockLogMapper logMapper = mock(StockLogMapper.class);
        when(logMapper.insert(any(StockLogEntity.class)))
                .thenThrow(new DuplicateKeyException("dup"));

        new StockServiceImpl(skuMapper, logMapper,
                new org.springframework.beans.factory.support.DefaultListableBeanFactory()
                        .getBeanProvider(yumefusaka.envoymart.productservice.search.ProductSyncService.class))
                .restore(request());

        verify(skuMapper, never()).restoreStock(anyLong(), anyInt());
        verify(logMapper, never()).updateAfterStock(anyString(), anyString(), anyLong(), anyString(), anyInt(), anyInt());
    }

    @Test
    void 没有业务标识的回补不去重_两次都真的执行() {
        ProductSkuMapper skuMapper = mock(ProductSkuMapper.class);
        StockLogMapper logMapper = mock(StockLogMapper.class);
        when(skuMapper.restoreStock(anyLong(), anyInt())).thenReturn(1);
        when(skuMapper.selectStock(anyLong())).thenReturn(300);
        when(logMapper.insert(any(StockLogEntity.class))).thenReturn(1);

        StockChangeRequest manual = StockChangeRequest.builder()
                .skuId(1L).quantity(5).bizType("MANUAL").build();

        StockServiceImpl service = new StockServiceImpl(skuMapper, logMapper,
                new org.springframework.beans.factory.support.DefaultListableBeanFactory()
                        .getBeanProvider(yumefusaka.envoymart.productservice.search.ProductSyncService.class));
        service.restore(manual);
        service.restore(manual);

        // 人工调库存没有可去重的业务键，两次都该生效 ——
        // 硬凑一个 key 会把运营的两次独立调整判成一次
        verify(skuMapper, times(2)).restoreStock(eq(1L), eq(5));
    }
}
