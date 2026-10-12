package yumefusaka.envoymart.promotionservice.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import yumefusaka.envoymart.promotionservice.mapper.UserCouponMapper;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class CouponUnredeemContractTest {
    @Test
    void 真实条件更新隔离核销订单且重复退还不会破坏券() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:coupon_release_" + UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(source);
        jdbc.execute("create table user_coupon(id bigint primary key,user_id varchar(32),status varchar(16),order_no varchar(32),used_at timestamp)");
        jdbc.update("insert into user_coupon values(1,'u1','USED','YS-FIRST',current_timestamp)");
        var config = new org.apache.ibatis.session.Configuration(new Environment("test",new JdbcTransactionFactory(),source));
        config.addMapper(UserCouponMapper.class);
        try(var session=new SqlSessionFactoryBuilder().build(config).openSession(true)) {
            var mapper=session.getMapper(UserCouponMapper.class);
            assertThat(mapper.unredeem(1L,"u1","YS-OTHER")).isZero();
            assertThat(jdbc.queryForObject("select order_no from user_coupon where id=1",String.class)).isEqualTo("YS-FIRST");
            assertThat(mapper.unredeem(1L,"u2","YS-FIRST")).isZero();
            assertThat(mapper.unredeem(1L,"u1","YS-FIRST")).isEqualTo(1);
            assertThat(jdbc.queryForObject("select status from user_coupon where id=1",String.class)).isEqualTo("UNUSED");
            assertThat(jdbc.queryForObject("select order_no from user_coupon where id=1",String.class)).isNull();
            assertThat(jdbc.queryForObject("select used_at from user_coupon where id=1",java.sql.Timestamp.class)).isNull();
            assertThat(mapper.unredeem(1L,"u1","YS-FIRST")).isZero();
        }
    }
}
