package yumefusaka.envoymart.common.operation;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;

/** 仅校验，不自动修表。启动失败比同名旧表静默失去唯一认领更容易恢复。 */
public final class OperationReceiptSchema {
    private OperationReceiptSchema() { }
    public static void verify(DataSource source, String table) {
        if (!Set.of("order_operation_receipt", "promotion_operation_receipt").contains(table)) throw new IllegalArgumentException("不支持的回执表");
        try (var connection = source.getConnection()) {
            if (!"MySQL".equals(connection.getMetaData().getDatabaseProductName())) return;
            Map<String,String> columns = new HashMap<>();
            try (var query = connection.prepareStatement("select column_name,concat(data_type,':',coalesce(collation_name,'')) from information_schema.columns where table_schema=database() and table_name=?")) {
                query.setString(1, table);
                try (var rows = query.executeQuery()) { while (rows.next()) columns.put(rows.getString(1), rows.getString(2)); }
            }
            if (!columns.keySet().containsAll(Set.of("id","user_id","operation_id","tool_name","parameters_hash","global_xid","decision_mark","hash_version","receipt_version","response_json","created_at","completed_at"))
                    || !"json:".equals(columns.get("response_json"))
                    || !"varchar:ascii_bin".equals(columns.get("global_xid")) || !"varchar:ascii_bin".equals(columns.get("decision_mark"))
                    || !columns.get("user_id").endsWith("_bin") || !columns.get("operation_id").endsWith("_bin")) {
                throw new IllegalStateException("回执表结构与已批准SQL不一致，请按迁移契约核对：" + table);
            }
            boolean unique = false;
            try (var query = connection.prepareStatement("select group_concat(column_name order by seq_in_index separator ',') from information_schema.statistics where table_schema=database() and table_name=? and non_unique=0 group by index_name")) {
                query.setString(1, table);
                try (var rows = query.executeQuery()) { while (rows.next()) unique |= "user_id,operation_id".equals(rows.getString(1)); }
            }
            if (!unique) throw new IllegalStateException("回执表缺少用户与操作联合唯一键：" + table);
            try (var query = connection.prepareStatement("select column_name,character_maximum_length,is_nullable from information_schema.columns where table_schema=database() and table_name=? and column_name in ('global_xid','decision_mark')")) {
                query.setString(1, table);
                try (var rows = query.executeQuery()) {
                    int checked = 0;
                    while (rows.next()) {
                        int expected = "global_xid".equals(rows.getString(1)) ? 128 : 24;
                        if (rows.getLong(2) != expected || !"YES".equals(rows.getString(3))) throw new IllegalStateException("回执事务证据列长度或可空约束不符合契约：" + table);
                        checked++;
                    }
                    if (checked != 2) throw new IllegalStateException("回执事务证据列缺失：" + table);
                }
            }
            try (var query = connection.prepareStatement("select character_maximum_length from information_schema.columns where table_schema=database() and table_name=? and column_name='operation_id'")) {
                query.setString(1, table);
                try (var rows = query.executeQuery()) {
                    if (!rows.next() || rows.getLong(1) != 256) throw new IllegalStateException("回执操作编号列长度不符合契约：" + table);
                }
            }
            try (var query = connection.prepareStatement("select engine from information_schema.tables where table_schema=database() and table_name=?")) {
                query.setString(1, table);
                try (var rows = query.executeQuery()) {
                    if (!rows.next() || !"InnoDB".equalsIgnoreCase(rows.getString(1))) throw new IllegalStateException("回执表必须使用事务引擎：" + table);
                }
            }
        } catch (SQLException e) { throw new IllegalStateException("回执表结构核对失败，不能启用写恢复", e); }
    }
}
