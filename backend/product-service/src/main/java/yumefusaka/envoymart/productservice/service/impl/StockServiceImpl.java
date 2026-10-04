package yumefusaka.envoymart.productservice.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
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

        // 回补必须先过幂等闸，再去加库存。
        //
        // 顺序不能反。反过来的话，「消息重试」与「并发重投」这两个场景在
        // 扣减已经发生、日志还没写下的窗口里都读不到记录，于是各自补一遍——
        // 实测就是这么把同一笔订单补了 4 次。
        // 先写流水则相反：谁先插进唯一键谁就是唯一的执行者，
        // 后到的插入会撞 key 抛异常，整个事务回滚，库存一行都没动。
        if (!claimRestore(skuId, request)) {
            log.info("[Stock] 回补已执行过，本次跳过（幂等）skuId={} bizType={} bizId={}",
                    skuId, request.getBizType(), request.getBizId());
            return;
        }

        int updated = skuMapper.restoreStock(skuId, quantity);
        if (updated == 0) {
            // 回补丢目标比扣减更危险：货已经退回来了，库存却没加回去，
            // 系统此后会一直少卖。所以这里必须让调用方看见，不能只打日志。
            // 抛异常会连带回滚上面那条流水占位，下一次重投能重新认领
            throw new IllegalArgumentException("商品规格不存在，库存回补失败：skuId=" + skuId);
        }
        updateLogAfterStock(skuId, RESTORE, quantity, request);
        syncIndexAfterCommit(skuId);
    }

    /**
     * 认领这次回补 —— 用唯一键裁决「谁是第一个」，而不是先查一次再说。
     *
     * <p><b>为什么不能写「select 一下有没有，没有就补」。</b>那是典型的
     * check-then-act：两个并发消费者都在对方写入前查到「没有」，于是都去补，
     * 唯一键在这里不是优化而是正确性的前提。DuplicateKeyException 不是错误，
     * 它正是「这件事已经有人做过了」的答案。
     *
     * <p><b>没有 bizType/bizId 的回补直接放行。</b>手工调库存这类请求不带业务标识，
     * 去重没有依据；硬凑一个 key 会把两次独立的人工调整判成一次。
     */
    private boolean claimRestore(Long skuId, StockChangeRequest request) {
        if (request.getBizType() == null || request.getBizId() == null) {
            return true;
        }
        try {
            writeLog(skuId, RESTORE, request.getQuantity(), request);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /**
     * 认领成功后，把这条流水的变动前后值补成真实数字。
     *
     * <p>占位必须先于库存更新写入（见 {@link #claimRestore}），而那时
     * {@code afterStock} 还没发生，只能先写一个占位；这里再按事务内回读到的
     * 真实值更新它。两步都在同一个事务里，中途失败会一起回滚，
     * 不会留下一条数字不对的流水。
     */
    private void updateLogAfterStock(Long skuId, String changeType, int quantity, StockChangeRequest request) {
        Integer after = skuMapper.selectStock(skuId);
        int afterStock = after == null ? 0 : after;
        int beforeStock = DEDUCT.equals(changeType) ? afterStock + quantity : afterStock - quantity;
        stockLogMapper.updateAfterStock(request.getBizType(), request.getBizId(), skuId, changeType,
                beforeStock, afterStock);
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
