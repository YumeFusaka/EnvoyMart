package yumefusaka.envoymart.orderservice.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import yumefusaka.envoymart.common.operation.OperationContext;
import yumefusaka.envoymart.common.operation.OperationReceiptRow;
import yumefusaka.envoymart.common.operation.OperationReceiptStore;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OperationReceiptTransactionTest {
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private OperationReceiptStore store;

    @BeforeEach
    void 建立隔离事务数据库() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:receipt_" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(source);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        jdbc.execute("create table effects(id int primary key, amount int)");
        jdbc.execute("create table receipts(user_id varchar(32),operation_id varchar(256),tool_name varchar(64),"
                + "parameters_hash varchar(64),response_json clob,primary key(user_id,operation_id))");
        store = new OperationReceiptStore(new OperationReceiptStore.Repository() {
            public OperationReceiptRow find(String user, String op) {
                return jdbc.query("select * from receipts where user_id=? and operation_id=?", (rs, index) ->
                        new OperationReceiptRow(1L, user, op, rs.getString("tool_name"), rs.getString("parameters_hash"),
                                1, 1, rs.getString("response_json"), null, rs.getString("response_json") == null ? null : java.time.LocalDateTime.now()), user, op)
                        .stream().findFirst().orElse(null);
            }
            public void claim(OperationContext operation) {
                jdbc.update("insert into receipts(user_id,operation_id,tool_name,parameters_hash) values(?,?,?,?)",
                        operation.userId(), operation.operationId(), operation.toolName(), operation.parametersHash());
            }
            public int complete(OperationContext operation, String json) {
                return jdbc.update("update receipts set response_json=? where user_id=? and operation_id=?", json,
                        operation.userId(), operation.operationId());
            }
        }, JsonMapper.builder().findAndAddModules().build());
    }

    @Test
    void 业务失败必须与认领一起回滚同操作随后可以执行() {
        var operation = OperationContext.of("u1", "same", "cart_add", Map.of("skuId", 7, "quantity", 1));
        assertThatThrownBy(() -> tx.execute(status -> store.commit(operation, Map.class, () -> {
            jdbc.update("insert into effects values(1,1)");
            throw new IllegalStateException("模拟业务失败");
        }))).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("select count(*) from effects", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from receipts", Integer.class)).isZero();
        tx.execute(status -> store.commit(operation, Map.class, () -> {
            jdbc.update("insert into effects values(1,1)");
            return Map.of("quantity", 1);
        }));
        assertThat(store.replay(operation, Map.class).orElseThrow().body().getData()).containsEntry("quantity", 1);
    }

    @Test
    void 回执保留首次数据同操作换参数被拒绝() {
        var operation = OperationContext.of("u1", "same", "cart_add", Map.of("quantity", 1));
        tx.execute(status -> store.commit(operation, Map.class, () -> Map.of("quantity", 1)));
        assertThat(store.replay(operation, Map.class).orElseThrow().replayed()).isTrue();
        assertThatThrownBy(() -> store.replay(OperationContext.of("u1", "same", "cart_add", Map.of("quantity", 2)), Map.class))
                .isInstanceOf(yumefusaka.envoymart.common.operation.OperationConflictException.class);
        assertThat(store.replay(OperationContext.of("u2", "same", "cart_add", Map.of("quantity", 1)), Map.class)).isEmpty();
    }
}
