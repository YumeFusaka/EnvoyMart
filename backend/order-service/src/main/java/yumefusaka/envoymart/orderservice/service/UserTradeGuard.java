package yumefusaka.envoymart.orderservice.service;

import io.seata.spring.annotation.GlobalLock;
import lombok.extern.slf4j.Slf4j;
import org.apache.seata.core.context.RootContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import yumefusaka.envoymart.orderservice.mapper.UserTradeGuardMapper;

/** 稳定用户主键协调购物车与结算；购物车删除重建不能绕过该交易边界。 */
@Service
@Slf4j
public class UserTradeGuard implements AutoCloseable {
    private static final long PREPARE_TIMEOUT_MILLIS = 2500;
    private final UserTradeGuardMapper mapper;
    private final javax.sql.DataSource source;
    private final java.util.concurrent.ExecutorService preparation = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
    private final java.util.concurrent.ConcurrentMap<String, Provision> inFlight = new java.util.concurrent.ConcurrentHashMap<>();

    public UserTradeGuard(UserTradeGuardMapper mapper, javax.sql.DataSource source) {
        this.mapper = mapper;
        this.source = source;
    }

    @GlobalLock
    @Transactional(propagation = Propagation.MANDATORY)
    public void acquire(String userId) {
        if (userId == null || userId.isBlank() || userId.length() > 32) {
            throw new IllegalArgumentException("用户交易标识无效");
        }
        long started = System.nanoTime();
        String userHash = Integer.toHexString(userId.hashCode());
        try {
            ensureMetadata(userId);
            var row = mapper.lock(userId);
            if (row == null || mapper.advance(userId) != 1) {
                throw new IllegalStateException("用户交易闸未能建立，请稍后重试");
            }
            log.info("[用户交易闸] 阶段=已锁定 模式={} globalLock={} userHash={} xid={} 耗时ms={}",
                    RootContext.inGlobalTransaction() ? "AT" : RootContext.requireGlobalLock() ? "本地全局锁校验" : "本地事务未进入Seata", RootContext.requireGlobalLock(), userHash,
                    RootContext.getXID(), (System.nanoTime() - started) / 1_000_000);
        } catch (RuntimeException failure) {
            log.warn("[用户交易闸] 阶段=拒绝 userHash={} errorType={} 耗时ms={}", userHash,
                    failure.getClass().getSimpleName(), (System.nanoTime() - started) / 1_000_000);
            throw failure;
        }
    }

    private void ensureMetadata(String userId) {
        var candidate = new Provision();
        var existing = inFlight.putIfAbsent(userId, candidate);
        var provision = existing == null ? candidate : existing;
        if (existing == null) {
            try {
                preparation.execute(() -> prepareMetadata(userId, provision));
            } catch (RuntimeException rejected) {
                inFlight.remove(userId, provision);
                provision.result.completeExceptionally(rejected);
            }
        }
        try {
            provision.result.get(PREPARE_TIMEOUT_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Exception unavailable) {
            provision.result.completeExceptionally(unavailable);
            var runner = provision.runner;
            if (runner != null) runner.interrupt();
            if (unavailable instanceof InterruptedException) Thread.currentThread().interrupt();
            Throwable reason = unavailable instanceof java.util.concurrent.ExecutionException && unavailable.getCause() != null
                    ? unavailable.getCause() : unavailable;
            log.warn("[用户交易闸] 阶段=元数据准备拒绝 userHash={} errorType={} 等待上限ms={}",
                    Integer.toHexString(userId.hashCode()), reason.getClass().getSimpleName(), PREPARE_TIMEOUT_MILLIS);
            throw new IllegalStateException("用户交易闸准备超时或不可用，请稍后重试", unavailable);
        }
    }

    private void prepareMetadata(String userId, Provision provision) {
        provision.runner = Thread.currentThread();
        try {
            if (provision.result.isDone()) return;
            // 独立线程不继承业务XID、GlobalLock或Spring事务快照，认领不得生成AT INSERT undo。
            if (RootContext.inGlobalTransaction() || RootContext.requireGlobalLock()) {
                throw new IllegalStateException("用户交易闸元数据线程存在业务事务上下文");
            }
            try (var connection = source.getConnection()) {
                if (!connection.getAutoCommit()) throw new IllegalStateException("用户交易闸元数据连接必须自动提交");
                try (var query = connection.prepareStatement("select user_id from order_user_trade_guard where user_id=?")) {
                    query.setQueryTimeout(2);
                    query.setString(1, userId);
                    try (var rows = query.executeQuery()) {
                        if (rows.next()) { provision.result.complete(null); return; }
                    }
                }
                // 既有行只作恒等赋值，绝不覆盖revision；冷行认领由原子SQL裁决，无前置gap锁。
                try (var claim = connection.prepareStatement("insert into order_user_trade_guard(user_id,revision) values(?,0) on duplicate key update user_id=user_id")) {
                    claim.setQueryTimeout(2);
                    claim.setString(1, userId);
                    claim.executeUpdate();
                }
            }
            provision.result.complete(null);
        } catch (Exception failure) {
            provision.result.completeExceptionally(failure);
        } finally {
            provision.runner = null;
            // 调用方超时不能提前移除仍执行的任务，避免同用户继续堆积借连接请求。
            inFlight.remove(userId, provision);
        }
    }

    @jakarta.annotation.PreDestroy
    @Override
    public void close() {
        inFlight.values().forEach(provision -> provision.result.completeExceptionally(new IllegalStateException("用户交易闸服务正在关闭")));
        preparation.shutdownNow();
    }

    private static final class Provision {
        private final java.util.concurrent.CompletableFuture<Void> result = new java.util.concurrent.CompletableFuture<>();
        private volatile Thread runner;
    }
}
