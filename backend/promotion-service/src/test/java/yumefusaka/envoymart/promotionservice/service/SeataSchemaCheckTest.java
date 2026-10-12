package yumefusaka.envoymart.promotionservice.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SeataSchemaCheckTest {
    @Test
    void 启动只读检查必须激活代理上下文并在结束恢复() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:seata_context_local", "sa", "") {
            @Override
            public java.sql.Connection getConnection() throws java.sql.SQLException {
                org.assertj.core.api.Assertions.assertThat(org.apache.seata.core.context.RootContext.requireGlobalLock()).isTrue();
                return super.getConnection();
            }
        };
        new SeataSchemaCheck(source, true).run(null);
        org.assertj.core.api.Assertions.assertThat(org.apache.seata.core.context.RootContext.requireGlobalLock()).isFalse();
        org.apache.seata.core.context.RootContext.bindGlobalLockFlag();
        try {
            new SeataSchemaCheck(source, true).run(null);
            org.assertj.core.api.Assertions.assertThat(org.apache.seata.core.context.RootContext.requireGlobalLock()).isTrue();
        } finally {
            org.apache.seata.core.context.RootContext.unbindGlobalLockFlag();
        }
    }
    private Map<String, String> columns() {
        return new HashMap<>(Map.of("branch_id", "bigint", "xid", "varchar(128)", "context", "varchar(128)",
                "rollback_info", "longblob", "log_status", "int", "log_created", "datetime(6)", "log_modified", "datetime(6)"));
    }

    @Test
    void 完整事务结构允许启动而不执行修表() {
        assertThatCode(() -> SeataSchemaCheck.validate(columns(), Set.of(), Set.of("xid,branch_id"), "InnoDB")).doesNotThrowAnyException();
    }

    @Test
    void 缺列错误类型可空列都阻断() {
        for (String key : columns().keySet()) {
            var missing = columns(); missing.remove(key);
            assertThatThrownBy(() -> SeataSchemaCheck.validate(missing, Set.of(), Set.of("xid,branch_id"), "InnoDB")).isInstanceOf(IllegalStateException.class);
            var wrong = columns(); wrong.put(key, "varchar(8)");
            assertThatThrownBy(() -> SeataSchemaCheck.validate(wrong, Set.of(), Set.of("xid,branch_id"), "InnoDB")).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> SeataSchemaCheck.validate(columns(), Set.of(key), Set.of("xid,branch_id"), "InnoDB")).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void 错误幂等键或非事务引擎阻断() {
        assertThatThrownBy(() -> SeataSchemaCheck.validate(columns(), Set.of(), Set.of("branch_id"), "InnoDB")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> SeataSchemaCheck.validate(columns(), Set.of(), Set.of("xid,branch_id"), "MyISAM")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 明确关闭Seata时不接触数据源而H2仅是本地验证() {
        assertThatCode(() -> new SeataSchemaCheck(null, false).run(null)).doesNotThrowAnyException();
        var source = new DriverManagerDataSource("jdbc:h2:mem:seata_schema_local", "sa", "");
        assertThatCode(() -> new SeataSchemaCheck(source, true).run(null)).doesNotThrowAnyException();
    }
}
