package yumefusaka.envoymart.promotionservice.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import tools.jackson.databind.json.JsonMapper;
import yumefusaka.envoymart.common.operation.*;
import yumefusaka.envoymart.promotionservice.mapper.OperationReceiptMapper;
import yumefusaka.envoymart.promotionservice.model.UserCouponResponse;
import java.util.Map;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Callable;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class AgentWriteFacadeTest {
    @Test
    void 成功回执重放必须受Seata全局锁保护() throws Exception {
        var method = OperationReceiptService.class.getMethod("replay", OperationContext.class, Class.class);
        assertThat(java.util.Arrays.stream(method.getAnnotations()).map(annotation -> annotation.annotationType().getName()))
                .contains("io.seata.spring.annotation.GlobalLock");
    }
    @Configuration @EnableTransactionManagement static class Transactions { }
    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private AgentWriteFacade facade;
    private OperationReceiptService receipts;
    private CouponService coupons;
    @BeforeEach
    void 建立真实事务与JSON映射器() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:coupon_receipt_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000", "sa", "");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("create table promotion_operation_receipt(id bigint auto_increment primary key,user_id varchar(32),operation_id varchar(256),tool_name varchar(64),parameters_hash char(64),global_xid varchar(128),decision_mark varchar(24),hash_version smallint,receipt_version smallint,response_json json,created_at timestamp,completed_at timestamp,unique(user_id,operation_id))");
        jdbc.execute("create table issued(id int primary key,quantity int)"); jdbc.update("insert into issued values(1,0)");
        var config = new org.apache.ibatis.session.Configuration(new Environment("test", new SpringManagedTransactionFactory(), source));
        config.setMapUnderscoreToCamelCase(true); config.addMapper(OperationReceiptMapper.class);
        var mapper = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config)).getMapper(OperationReceiptMapper.class);
        context = new AnnotationConfigApplicationContext(); context.register(Transactions.class);
        context.registerBean("transactionManager", DataSourceTransactionManager.class, () -> new DataSourceTransactionManager(source));
        context.registerBean(OperationReceiptService.class, () -> new OperationReceiptService(mapper, JsonMapper.builder().findAndAddModules().build()));
        context.refresh(); receipts = context.getBean(OperationReceiptService.class); coupons = mock(CouponService.class);
        when(coupons.receive("u1",5L)).thenAnswer(invocation -> {
            jdbc.update("update issued set quantity=quantity+1");
            return UserCouponResponse.builder().id(9L).couponId(5L).status("UNUSED").receivedAt(java.time.LocalDateTime.of(2026,10,11,10,0)).build();
        });
        facade = new AgentWriteFacade(receipts,coupons);
    }
    @AfterEach void 关闭上下文() { context.close(); }
    private OperationContext operation() { return OperationContext.of("u1","coupon:step","coupon_receive",Map.of("couponId",5L)); }

    @Test
    void 八并发同操作只发一张券随后返回首次状态与时间() throws Exception {
        try (var pool = Executors.newFixedThreadPool(8)) {
            List<Callable<OperationReceiptReply<UserCouponResponse>>> calls = java.util.stream.IntStream.range(0,8)
                    .mapToObj(i -> (Callable<OperationReceiptReply<UserCouponResponse>>) () -> facade.receive(operation(),5L)).toList();
            for (var future : pool.invokeAll(calls)) assertThat(future.get().body().getData().getId()).isEqualTo(9L);
        }
        assertThat(jdbc.queryForObject("select quantity from issued",Integer.class)).isEqualTo(1);
        verify(coupons,times(1)).receive("u1",5L);
        var data = receipts.replay(operation(),UserCouponResponse.class).orElseThrow().body().getData();
        assertThat(data.getStatus()).isEqualTo("UNUSED");
        assertThat(data.getReceivedAt()).isEqualTo(java.time.LocalDateTime.of(2026,10,11,10,0));
    }

    @Test
    void 发行中失败业务与回执一起回滚不同用户不可见() {
        doAnswer(invocation -> { jdbc.update("update issued set quantity=1"); throw new IllegalStateException("模拟发券失败"); }).when(coupons).receive("u1",5L);
        assertThatThrownBy(() -> facade.receive(operation(),5L)).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("select quantity from issued",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from promotion_operation_receipt",Integer.class)).isZero();
        assertThat(receipts.replay(OperationContext.of("u2","coupon:step","coupon_receive",Map.of("couponId",5L)),UserCouponResponse.class)).isEmpty();
    }
}
