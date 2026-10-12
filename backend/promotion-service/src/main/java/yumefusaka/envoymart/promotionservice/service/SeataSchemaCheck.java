package yumefusaka.envoymart.promotionservice.service;

import org.apache.seata.rm.datasource.ConnectionProxy;
import org.apache.seata.core.context.RootContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 只读核验真实AT数据源及undo契约，不用IF NOT EXISTS掩盖旧表不兼容。 */
@Component
public class SeataSchemaCheck implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(SeataSchemaCheck.class);
    private static final Map<String, String> EXPECTED = Map.of(
            "branch_id", "bigint", "xid", "varchar(128)", "context", "varchar(128)",
            "rollback_info", "longblob", "log_status", "int", "log_created", "datetime(6)", "log_modified", "datetime(6)");
    private final DataSource source;
    private final boolean enabled;

    public SeataSchemaCheck(DataSource source, @Value("${seata.enabled:true}") boolean enabled) {
        this.source = source;
        this.enabled = enabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            log.warn("[Seata] 模式=本地事务 原因=公开高级SEATA_ENABLED关闭 跨服务原子保证=false");
            return;
        }
        boolean previousGlobalLock = RootContext.requireGlobalLock();
        // 自动数据源代理仅在XID或GlobalLock上下文拦截getConnection；启动线程没有业务XID。
        // 本次只读检查激活自身GlobalLock上下文，结束恢复原标志，不创建/冒充全局事务。
        if (!previousGlobalLock) RootContext.bindGlobalLockFlag();
        try (var connection = source.getConnection()) {
            if (!"MySQL".equals(connection.getMetaData().getDatabaseProductName())) {
                log.warn("[Seata] 模式=本地验证 原因=非MySQL 跨服务验收=false");
                return;
            }
            if (!(connection instanceof ConnectionProxy)) throw new IllegalStateException("营销实际连接未受Seata AT代理，禁止启用跨服务核销");
            Map<String, String> columns = new HashMap<>();
            Set<String> nullable = new HashSet<>();
            try (var query = connection.prepareStatement("select column_name,column_type,is_nullable from information_schema.columns where table_schema=database() and table_name='undo_log'")) {
                try (var rows = query.executeQuery()) {
                    while (rows.next()) {
                        columns.put(rows.getString(1), rows.getString(2).toLowerCase(Locale.ROOT));
                        if (!"NO".equals(rows.getString(3))) nullable.add(rows.getString(1));
                    }
                }
            }
            Set<String> unique = new HashSet<>();
            try (var query = connection.prepareStatement("select group_concat(column_name order by seq_in_index separator ',') from information_schema.statistics where table_schema=database() and table_name='undo_log' and non_unique=0 group by index_name")) {
                try (var rows = query.executeQuery()) { while (rows.next()) unique.add(rows.getString(1)); }
            }
            String engine = null;
            try (var query = connection.prepareStatement("select engine from information_schema.tables where table_schema=database() and table_name='undo_log'")) {
                try (var rows = query.executeQuery()) { if (rows.next()) engine = rows.getString(1); }
            }
            validate(columns, nullable, unique, engine);
            log.info("[Seata] 模式=AT 数据源代理=true undo结构=通过 事务组=envoymart_tx_group TC连接及分支结果见RM注册日志");
        } catch (SQLException e) {
            throw new IllegalStateException("营销Seata undo结构只读核验失败，禁止启动不完整AT", e);
        } finally {
            if (!previousGlobalLock) RootContext.unbindGlobalLockFlag();
        }
    }

    static void validate(Map<String, String> columns, Set<String> nullable, Set<String> unique, String engine) {
        if (!columns.entrySet().containsAll(EXPECTED.entrySet()) || EXPECTED.keySet().stream().anyMatch(nullable::contains)) {
            throw new IllegalStateException("营销undo_log列结构与批准Seata 2.5 AT迁移不一致");
        }
        if (!unique.contains("xid,branch_id")) throw new IllegalStateException("营销undo_log缺少(xid,branch_id)联合唯一键");
        if (!"InnoDB".equalsIgnoreCase(engine)) throw new IllegalStateException("营销undo_log必须使用InnoDB事务引擎");
    }
}
