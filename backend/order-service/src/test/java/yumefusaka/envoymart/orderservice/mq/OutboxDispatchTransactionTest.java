package yumefusaka.envoymart.orderservice.mq;

import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import yumefusaka.envoymart.orderservice.mapper.EventOutboxMapper;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** H2只验证本地事务、行锁、重读与失败恢复；Seata全局锁由真实MySQL/TC场景验收。 */
class OutboxDispatchTransactionTest {
    @Configuration
    @EnableTransactionManagement
    static class Transactions { }

    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private RabbitTemplate rabbit;
    private OutboxDispatchTransaction dispatch;

    @BeforeEach
    void setup() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:outbox_" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000", "sa", "");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("create table event_outbox(id bigint primary key,event_type varchar(64),aggregate_id varchar(64),exchange_name varchar(128),routing_key varchar(128),payload text,created_at timestamp,sent_at timestamp,attempts int,last_error varchar(512))");
        jdbc.update("insert into event_outbox values(1,'order.created','独立订单','envoymart.order','order.created','{}',current_timestamp,null,0,null)");
        var config = new org.apache.ibatis.session.Configuration(new Environment("test", new SpringManagedTransactionFactory(), source));
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(EventOutboxMapper.class);
        var mapper = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config)).getMapper(EventOutboxMapper.class);
        rabbit = mock(RabbitTemplate.class);
        doAnswer(call -> {
            CorrelationData correlation = call.getArgument(3);
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbit).send(anyString(), anyString(), any(), any(CorrelationData.class));
        context = new AnnotationConfigApplicationContext();
        context.register(Transactions.class);
        context.registerBean("transactionManager", DataSourceTransactionManager.class, () -> new DataSourceTransactionManager(source));
        context.registerBean(OutboxDispatchTransaction.class, () -> new OutboxDispatchTransaction(mapper, rabbit));
        context.refresh();
        dispatch = context.getBean(OutboxDispatchTransaction.class);
        assertThat(org.springframework.aop.support.AopUtils.isAopProxy(dispatch)).isTrue();
    }

    @AfterEach
    void cleanup() { if (context != null) context.close(); }

    @Test
    void 两投递节点相同行并发认领在事务内重读只发布一次() throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            List<Callable<OutboxDispatchTransaction.Outcome>> calls = List.of(() -> dispatch.dispatch(1L), () -> dispatch.dispatch(1L));
            var results = pool.invokeAll(calls);
            assertThat(List.of(results.getFirst().get(), results.getLast().get()))
                    .containsExactlyInAnyOrder(OutboxDispatchTransaction.Outcome.SENT, OutboxDispatchTransaction.Outcome.SKIPPED);
        }
        verify(rabbit, times(1)).send(anyString(), anyString(), any(), any(CorrelationData.class));
        assertThat(jdbc.queryForObject("select attempts from event_outbox where id=1", Integer.class)).isEqualTo(1);
    }

    @Test
    void 回滚导致候选原行不存在时不得发布事件() {
        jdbc.update("delete from event_outbox where id=1");
        assertThat(dispatch.dispatch(1L)).isEqualTo(OutboxDispatchTransaction.Outcome.SKIPPED);
        verifyNoInteractions(rabbit);
    }

    @Test
    void 发布失败保留待投递且重试成功会清除错误() {
        doThrow(new IllegalStateException("模拟发布失败")).doAnswer(call -> {
            ((CorrelationData) call.getArgument(3)).getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbit).send(anyString(), anyString(), any(), any(CorrelationData.class));
        assertThat(dispatch.dispatch(1L)).isEqualTo(OutboxDispatchTransaction.Outcome.RETRY);
        assertThat(jdbc.queryForObject("select sent_at is null from event_outbox where id=1", Boolean.class)).isTrue();
        assertThat(dispatch.dispatch(1L)).isEqualTo(OutboxDispatchTransaction.Outcome.SENT);
        assertThat(jdbc.queryForObject("select attempts from event_outbox where id=1", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select last_error is null from event_outbox where id=1", Boolean.class)).isTrue();
    }

    @Test
    void broker确认但mandatory退回仍保留待投递() {
        doAnswer(call -> {
            CorrelationData correlation = call.getArgument(3);
            correlation.setReturned(new ReturnedMessage(call.getArgument(2), 312, "模拟无路由", "envoymart.order", "order.created"));
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbit).send(anyString(), anyString(), any(), any(CorrelationData.class));
        assertThat(dispatch.dispatch(1L)).isEqualTo(OutboxDispatchTransaction.Outcome.RETRY);
        assertThat(jdbc.queryForObject("select attempts from event_outbox where id=1", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select sent_at is null from event_outbox where id=1", Boolean.class)).isTrue();
    }
}
