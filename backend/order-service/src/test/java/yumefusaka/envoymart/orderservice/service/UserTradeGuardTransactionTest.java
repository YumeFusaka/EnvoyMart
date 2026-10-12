package yumefusaka.envoymart.orderservice.service;

import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import yumefusaka.envoymart.orderservice.mapper.UserTradeGuardMapper;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserTradeGuardTransactionTest {
    @Configuration
    @EnableTransactionManagement
    static class Transactions { }

    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;
    private UserTradeGuard guard;

    @BeforeEach
    void 使用真实本地事务和映射器() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:trade_" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000", "sa", "");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("create table order_user_trade_guard(user_id varchar(32) primary key,revision bigint not null default 0)");
        jdbc.execute("create table effect(user_id varchar(32) primary key,quantity int)");
        jdbc.update("insert into effect values('u1',0),('u2',0)");
        var configuration = new org.apache.ibatis.session.Configuration(new Environment("test", new SpringManagedTransactionFactory(), source));
        configuration.addMapper(UserTradeGuardMapper.class);
        var mapper = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(configuration)).getMapper(UserTradeGuardMapper.class);
        var manager = new DataSourceTransactionManager(source);
        transactions = new TransactionTemplate(manager);
        context = new AnnotationConfigApplicationContext();
        context.register(Transactions.class);
        context.registerBean("transactionManager", DataSourceTransactionManager.class, () -> manager);
        context.registerBean(UserTradeGuard.class, () -> new UserTradeGuard(mapper, source));
        context.refresh();
        guard = context.getBean(UserTradeGuard.class);
    }

    @AfterEach
    void 释放测试上下文() { context.close(); }

    @Test
    void 八个并发本地写累计准确且共享一条稳定闸行() throws Exception {
        try (var pool = Executors.newFixedThreadPool(8)) {
            var tasks = IntStream.range(0, 8).mapToObj(index -> pool.submit(() -> transactions.executeWithoutResult(status -> {
                guard.acquire("u1");
                jdbc.update("update effect set quantity=quantity+1 where user_id='u1'");
            }))).toList();
            for (var task : tasks) task.get(10, TimeUnit.SECONDS);
        }
        assertThat(jdbc.queryForObject("select quantity from effect where user_id='u1'", Integer.class)).isEqualTo(8);
        assertThat(jdbc.queryForObject("select revision from order_user_trade_guard where user_id='u1'", Long.class)).isEqualTo(8);
        assertThat(jdbc.queryForObject("select count(*) from order_user_trade_guard", Integer.class)).isEqualTo(1);
    }

    @Test
    void 业务回滚保留零版本元数据而不保留业务效果() {
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            guard.acquire("u1");
            jdbc.update("update effect set quantity=7 where user_id='u1'");
            throw new IllegalStateException("业务失败");
        })).hasMessage("业务失败");
        assertThat(jdbc.queryForObject("select count(*) from order_user_trade_guard", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select revision from order_user_trade_guard where user_id='u1'", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select quantity from effect where user_id='u1'", Integer.class)).isZero();
    }

    @Test
    void 同用户等待期间不同用户可提交且回滚后等待方继续() throws Exception {
        transactions.executeWithoutResult(status -> guard.acquire("u1"));
        transactions.executeWithoutResult(status -> guard.acquire("u2"));
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var waitingStarted = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(3)) {
            var holder = pool.submit(() -> transactions.executeWithoutResult(status -> {
                guard.acquire("u1");
                locked.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("测试未释放持锁方"); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
                status.setRollbackOnly();
            }));
            assertThat(locked.await(3, TimeUnit.SECONDS)).isTrue();
            var sameUser = pool.submit(() -> transactions.executeWithoutResult(status -> {
                waitingStarted.countDown();
                guard.acquire("u1");
                jdbc.update("update effect set quantity=quantity+1 where user_id='u1'");
            }));
            assertThat(waitingStarted.await(3, TimeUnit.SECONDS)).isTrue();
            var otherUser = pool.submit(() -> transactions.executeWithoutResult(status -> {
                guard.acquire("u2");
                jdbc.update("update effect set quantity=quantity+1 where user_id='u2'");
            }));
            otherUser.get(3, TimeUnit.SECONDS);
            assertThat(sameUser.isDone()).isFalse();
            release.countDown();
            holder.get(3, TimeUnit.SECONDS);
            sameUser.get(3, TimeUnit.SECONDS);
        } finally { release.countDown(); }
        assertThat(jdbc.queryForObject("select revision from order_user_trade_guard where user_id='u1'", Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select quantity from effect where user_id='u1'", Integer.class)).isEqualTo(1);
    }

    @Test
    void 禁止在业务事务之外单独取闸() {
        assertThatThrownBy(() -> guard.acquire("u1"))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        assertThat(jdbc.queryForObject("select count(*) from order_user_trade_guard", Integer.class)).isZero();
    }

    @Test
    void 同用户池满合并一次元数据任务并有界拒绝且不触碰业务锁() throws Exception {
        var source = org.mockito.Mockito.mock(javax.sql.DataSource.class);
        var mapper = org.mockito.Mockito.mock(UserTradeGuardMapper.class);
        var borrowed = new java.util.concurrent.atomic.AtomicInteger();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        org.mockito.Mockito.when(source.getConnection()).thenAnswer(invocation -> {
            borrowed.incrementAndGet();
            entered.countDown();
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { throw new java.sql.SQLException("模拟池满借连接被取消", interrupted); }
            throw new java.sql.SQLException("模拟连接不可用");
        });
        // 本测试仅测资源失败边界；真实事务行为由其他测试及MySQL隔离实验验证。
        try (var preparing = new UserTradeGuard(mapper, source); var pool = Executors.newFixedThreadPool(8)) {
            var callsStarted = new CountDownLatch(8);
            var calls = IntStream.range(0, 8).mapToObj(index -> pool.submit(() -> {
                callsStarted.countDown();
                assertThatThrownBy(() -> preparing.acquire("pool_full"))
                        .isInstanceOf(IllegalStateException.class).hasMessageContaining("准备超时或不可用");
            })).toList();
            assertThat(callsStarted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            for (var call : calls) call.get(4, TimeUnit.SECONDS);
            assertThat(borrowed.get()).isEqualTo(1);
            org.mockito.Mockito.verifyNoInteractions(mapper);
        } finally { release.countDown(); }
    }
}
