package yumefusaka.envoymart.orderservice.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.LinkedHashMap;

/** 只读确认稳定PK与事务存储引擎，避免同名旧表绕过建表脚本。 */
@Component
@Slf4j
public class UserTradeGuardSchemaCheck implements ApplicationRunner {
    private final DataSource source;
    public UserTradeGuardSchemaCheck(DataSource source) { this.source = source; }

    @Override
    public void run(ApplicationArguments args) {
        try (var connection = source.getConnection()) {
            if (!"MySQL".equals(connection.getMetaData().getDatabaseProductName())) return;
            var columns = new LinkedHashMap<String, String>();
            try (var statement = connection.prepareStatement("select column_name,column_type,is_nullable,collation_name from information_schema.columns where table_schema=database() and table_name='order_user_trade_guard' order by ordinal_position");
                 var rows = statement.executeQuery()) {
                while (rows.next()) {
                    columns.put(rows.getString(1), rows.getString(2));
                    if (!"NO".equals(rows.getString(3))) throw new IllegalStateException("用户交易闸不得包含可空列");
                    if ("user_id".equals(rows.getString(1)) && !"ascii_bin".equals(rows.getString(4))) {
                        throw new IllegalStateException("用户交易闸主键必须使用ascii_bin排序规则");
                    }
                }
            }
            if (!"varchar(32)".equals(columns.get("user_id")) || !"bigint".equals(columns.get("revision")) || columns.size() != 2) {
                throw new IllegalStateException("用户交易闸列契约不兼容，禁止启动交易写入");
            }
            try (var statement = connection.prepareStatement("select group_concat(column_name order by seq_in_index) from information_schema.statistics where table_schema=database() and table_name='order_user_trade_guard' and index_name='PRIMARY'");
                 var rows = statement.executeQuery()) {
                if (!rows.next() || !"user_id".equals(rows.getString(1))) throw new IllegalStateException("用户交易闸必须以user_id作为唯一主键");
            }
            try (var statement = connection.prepareStatement("select engine from information_schema.tables where table_schema=database() and table_name='order_user_trade_guard'");
                 var rows = statement.executeQuery()) {
                if (!rows.next() || !"InnoDB".equalsIgnoreCase(rows.getString(1))) throw new IllegalStateException("用户交易闸必须使用InnoDB");
            }
            try (var statement = connection.prepareStatement("select column_type,is_nullable from information_schema.columns where table_schema=database() and table_name='shop_order' and column_name='global_xid'");
                 var rows = statement.executeQuery()) {
                if (!rows.next() || !"varchar(128)".equals(rows.getString(1)) || !"YES".equals(rows.getString(2))) {
                    throw new IllegalStateException("订单global_xid列契约不兼容，禁止启动交易写入");
                }
            }
            log.info("[交易结构] 用户稳定主键闸=通过 订单全局XID契约=通过 存储引擎=InnoDB");
        } catch (SQLException failure) {
            throw new IllegalStateException("用户交易闸结构核验失败，禁止启动交易写入", failure);
        }
    }
}
