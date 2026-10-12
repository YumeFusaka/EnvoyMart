package yumefusaka.envoymart.productservice.service.impl;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import yumefusaka.envoymart.contract.StockChangeRequest;
import yumefusaka.envoymart.productservice.entity.ProductSkuEntity;
import yumefusaka.envoymart.productservice.mapper.ProductSkuMapper;
import yumefusaka.envoymart.productservice.mapper.StockLogMapper;
import yumefusaka.envoymart.productservice.search.ProductSyncService;
import yumefusaka.envoymart.productservice.projection.ProductProjectionRequests;

import static org.mockito.Mockito.*;

class StockProjectionRegistrationTest {
    @Test
    void localCommitNeverPublishesProjectionDirectly() {
        var skus = mock(ProductSkuMapper.class);
        var logs = mock(StockLogMapper.class);
        var sync = mock(ProductSyncService.class);
        var requests = mock(ProductProjectionRequests.class);
        when(skus.deductStock(29L, 1)).thenReturn(1);
        when(skus.selectStock(29L)).thenReturn(494);
        var sku = new ProductSkuEntity();
        sku.setId(29L); sku.setSpuId(20L); sku.setStock(494);
        when(skus.selectById(29L)).thenReturn(sku);
        TransactionSynchronizationManager.initSynchronization();
        try {
            new StockServiceImpl(skus, logs, requests).deduct(StockChangeRequest.builder()
                    .skuId(29L).quantity(1).bizType("ORDER").bizId("projection-regression").build());
            // 本地提交仅代表AT阶段一，不能作为外部派生发布的唯一凭据。
            TransactionSynchronizationManager.getSynchronizations().forEach(item -> item.afterCommit());
            verifyNoInteractions(sync);
            verify(requests).request(java.util.List.of(20L));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
