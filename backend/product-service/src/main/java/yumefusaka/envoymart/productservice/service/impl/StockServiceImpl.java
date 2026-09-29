package yumefusaka.envoymart.productservice.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import yumefusaka.envoymart.common.util.Times;
import yumefusaka.envoymart.productservice.entity.ProductSkuEntity;
import yumefusaka.envoymart.productservice.entity.StockLogEntity;
import yumefusaka.envoymart.productservice.mapper.ProductSkuMapper;
import yumefusaka.envoymart.productservice.mapper.StockLogMapper;
import yumefusaka.envoymart.contract.StockChangeRequest;
import yumefusaka.envoymart.productservice.search.ProductSyncService;
import yumefusaka.envoymart.productservice.service.StockService;

@Slf4j
@Service
public class StockServiceImpl implements StockService {

    private static final String DEDUCT = "DEDUCT";
    private static final String RESTORE = "RESTORE";

    private final ProductSkuMapper skuMapper;
    private final StockLogMapper stockLogMapper;
    /** 索引用 {@link ObjectProvider} 拿：同步开关可以关掉，那时这个 bean 不存在，注入会直接失败 */
    private final ObjectProvider<ProductSyncService> syncServiceProvider;

    public StockServiceImpl(ProductSkuMapper skuMapper,
                            StockLogMapper stockLogMapper,
                            ObjectProvider<ProductSyncService> syncServiceProvider) {
        this.skuMapper = skuMapper;
        this.stockLogMapper = stockLogMapper;
        this.syncServiceProvider = syncServiceProvider;
    }

    @Override
    @Transactional
    public void deduct(StockChangeRequest request) {
        Long skuId = request.getSkuId();
        int quantity = request.getQuantity();

        int updated = skuMapper.deductStock(skuId, quantity);
        if (updated == 0) {
            // 条件更新没命中只有两种可能。分开查一次是为了给出**能指导下一步**的提示：
            // 「规格不存在」该去改收货信息，「库存不足」该去改数量 —— 混成一句话，用户只能猜
            ProductSkuEntity sku = skuMapper.selectById(skuId);
            if (sku == null) {
                throw new IllegalArgumentException("商品规格不存在");
            }
            throw new IllegalStateException("库存不足，当前仅剩 " + sku.getStock() + " 件");
        }
        writeLog(skuId, DEDUCT, quantity, request);
        syncIndexAfterCommit(skuId);
    }

    @Override
    @Transactional
    public void restore(StockChangeRequest request) {
        Long skuId = request.getSkuId();
        int quantity = request.getQuantity();

        int updated = skuMapper.restoreStock(skuId, quantity);
        if (updated == 0) {
            // 回补丢目标比扣减更危险：货已经退回来了，库存却没加回去，
            // 系统此后会一直少卖。所以这里必须让调用方看见，不能只打日志
            throw new IllegalArgumentException("商品规格不存在，库存回补失败：skuId=" + skuId);
        }
        writeLog(skuId, RESTORE, quantity, request);
        syncIndexAfterCommit(skuId);
    }

    /**
     * 记一条库存流水。
     * <p>
     * <b>必须在扣减/回补的同一个事务内调用。</b>刚才那条 UPDATE 在 InnoDB 里会锁住该行
     * 直到事务结束，所以这里回读到的是自己刚写的值，并发事务插不进来；一旦离开事务边界
     * 再读，锁已释放，读到的可能是别人的结果，流水的前后值就不准了 —— 而对账全靠它。
     */
    private void writeLog(Long skuId, String changeType, int quantity, StockChangeRequest request) {
        Integer after = skuMapper.selectStock(skuId);
        int afterStock = after == null ? 0 : after;
        int beforeStock = DEDUCT.equals(changeType) ? afterStock + quantity : afterStock - quantity;

        StockLogEntity log = new StockLogEntity();
        log.setSkuId(skuId);
        log.setChangeType(changeType);
        log.setQuantity(quantity);
        log.setBeforeStock(beforeStock);
        log.setAfterStock(afterStock);
        log.setBizType(request.getBizType());
        log.setBizId(request.getBizId());
        log.setRemark(request.getRemark());
        log.setCreatedAt(Times.now());
        stockLogMapper.insert(log);
    }

    /**
     * 库存变了，ES 索引里的汇总库存与「有货/缺货」标签也要跟着变。
     * <p>
     * <b>注册在事务提交之后执行。</b>直接在方法里同步的话，事务一旦回滚，
     * 索引已经被改成了「库存减过」的样子，而库里没有 —— 这种不一致不会报错，
     * 只会在某天有人对着搜索结果和实际库存发懵时暴露。
     */
    private void syncIndexAfterCommit(Long skuId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    syncIndexQuietly(skuId);
                }
            });
        } else {
            syncIndexQuietly(skuId);
        }
    }

    /**
     * ES 是派生数据：同步失败不该让库存操作跟着失败 —— 那一笔库存已经扣了，
     * 因为索引没更新就回滚，代价与「索引里的库存旧一会儿」完全不成比例。
     * 下一次该商品的任何变更都会把索引覆盖回正确值。
     */
    private void syncIndexQuietly(Long skuId) {
        try {
            ProductSyncService syncService = syncServiceProvider.getIfAvailable();
            if (syncService == null) {
                return;
            }
            ProductSkuEntity sku = skuMapper.selectById(skuId);
            if (sku != null) {
                syncService.syncOne(sku.getSpuId());
            }
        } catch (Exception e) {
            log.warn("[Stock] ES 索引同步失败，不影响库存本身: skuId={}", skuId, e);
        }
    }
}
