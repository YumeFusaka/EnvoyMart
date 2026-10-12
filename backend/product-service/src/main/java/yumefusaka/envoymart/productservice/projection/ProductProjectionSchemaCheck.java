package yumefusaka.envoymart.productservice.projection;

import lombok.extern.slf4j.Slf4j;
import org.apache.seata.core.context.RootContext;
import org.apache.seata.rm.datasource.ConnectionProxy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;

/** 只读检查既有同名表，不能用IF NOT EXISTS掩盖非事务引擎或缺列。 */
@Slf4j
@Component
public class ProductProjectionSchemaCheck implements ApplicationRunner {
    private final DataSource source;
    private final boolean seataEnabled;
    public ProductProjectionSchemaCheck(DataSource source, @Value("${seata.enabled:true}") boolean seataEnabled) {
        this.source = source; this.seataEnabled = seataEnabled;
    }
    @Override public void run(ApplicationArguments arguments) {
        boolean previous = RootContext.requireGlobalLock();
        if (!previous) RootContext.bindGlobalLockFlag();
        try (var connection = source.getConnection()) {
            if (!"MySQL".equals(connection.getMetaData().getDatabaseProductName())) {
                log.info("[商品投影] 模式=本地数据库验证 MySQL和AT门禁=false");
                return;
            }
            if (seataEnabled && !(connection instanceof ConnectionProxy)) throw new IllegalStateException("商品实际连接缺少Seata AT代理");
            verify(connection, "product_projection_task", Map.ofEntries(
                    Map.entry("id", "bigint"), Map.entry("spu_id", "bigint"), Map.entry("global_xid", "varchar(128)"),
                    Map.entry("created_at", "datetime"), Map.entry("completed_at", "datetime"), Map.entry("attempts", "int"),
                    Map.entry("next_attempt_at", "datetime"), Map.entry("last_error_type", "varchar(128)"),
                    Map.entry("applied_task_id", "bigint"), Map.entry("publish_mode", "varchar(32)")), "id", Set.of("id", "spu_id", "created_at", "attempts"), true);
            verify(connection, "product_projection_spu_guard", Map.of("spu_id", "bigint", "es_version_base", "bigint", "last_applied_task_id", "bigint"),
                    "spu_id", Set.of("spu_id", "last_applied_task_id"), false);
            log.info("[商品投影] 持久任务结构=通过 永久版本闸结构=通过 InnoDB=true AT代理={} 模式={}",
                    connection instanceof ConnectionProxy, seataEnabled ? "AT" : "公开高级开关关闭Seata的本地事务");
        } catch (SQLException failure) { throw new IllegalStateException("商品持久投影结构只读核验失败", failure); }
        finally { if (!previous) RootContext.unbindGlobalLockFlag(); }
    }

    private static void verify(Connection connection, String table, Map<String,String> expected, String primary, Set<String> required, boolean autoId) throws SQLException {
        var actual = new HashMap<String,String>();
        var nonnull = new java.util.HashSet<String>();
        boolean increment = false;
        try (var query = connection.prepareStatement("select column_name,column_type,is_nullable,extra from information_schema.columns where table_schema=database() and table_name=?")) {
            query.setString(1, table); query.setQueryTimeout(2);
            try (var rows = query.executeQuery()) { while (rows.next()) {
                actual.put(rows.getString(1), rows.getString(2));
                if ("NO".equals(rows.getString(3))) nonnull.add(rows.getString(1));
                if (primary.equals(rows.getString(1)) && rows.getString(4).contains("auto_increment")) increment = true;
            } }
        }
        if (!actual.entrySet().containsAll(expected.entrySet()) || !nonnull.containsAll(required) || autoId && !increment)
            throw new IllegalStateException("商品投影列结构不符合批准契约：" + table);
        try (var query = connection.prepareStatement("select group_concat(column_name order by seq_in_index separator ',') from information_schema.statistics where table_schema=database() and table_name=? and index_name='PRIMARY'")) {
            query.setString(1, table); query.setQueryTimeout(2);
            try (var rows = query.executeQuery()) { if (!rows.next() || !primary.equals(rows.getString(1))) throw new IllegalStateException("商品投影缺少稳定主键：" + table); }
        }
        try (var query = connection.prepareStatement("select engine from information_schema.tables where table_schema=database() and table_name=?")) {
            query.setString(1, table); query.setQueryTimeout(2);
            try (var rows = query.executeQuery()) { if (!rows.next() || !"InnoDB".equalsIgnoreCase(rows.getString(1))) throw new IllegalStateException("商品投影必须使用InnoDB事务引擎：" + table); }
        }
    }
}
