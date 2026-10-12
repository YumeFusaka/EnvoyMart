package yumefusaka.envoymart.productservice.projection;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/** 业务事务追加任务；消费者元数据不与业务行共同更新。 */
@Repository
public class ProductProjectionStore {
    public record Task(long id, long spuId, String globalXid) { }
    public record Guard(long spuId, Long versionBase, long lastAppliedTaskId) { }
    private final JdbcTemplate jdbc;

    public ProductProjectionStore(javax.sql.DataSource source) {
        jdbc = new JdbcTemplate(source);
        jdbc.setQueryTimeout(2);
    }

    public long append(long spuId, String globalXid) {
        var key = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("insert into product_projection_task(spu_id,global_xid,created_at,attempts) values(?,?,current_timestamp,0)", java.sql.Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, spuId); statement.setString(2, globalXid);
            return statement;
        }, key);
        return java.util.Objects.requireNonNull(key.getKey(), "任务主键未返回").longValue();
    }

    public List<Task> candidates(long afterId, int limit) {
        return jdbc.query("select id,spu_id,global_xid from product_projection_task where completed_at is null "
                        + "and (next_attempt_at is null or next_attempt_at<=current_timestamp) and id>? order by id limit ?",
                (rs, row) -> new Task(rs.getLong(1), rs.getLong(2), rs.getString(3)), afterId, limit);
    }

    public Task lockTask(long id) {
        var rows = jdbc.query("select id,spu_id,global_xid from product_projection_task where id=? and completed_at is null for update",
                (rs, row) -> new Task(rs.getLong(1), rs.getLong(2), rs.getString(3)), id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void ensureGuard(long spuId) {
        jdbc.update("insert ignore into product_projection_spu_guard(spu_id,last_applied_task_id) values(?,0)", spuId);
    }

    public Guard lockGuard(long spuId) {
        return jdbc.queryForObject("select spu_id,es_version_base,last_applied_task_id from product_projection_spu_guard where spu_id=? for update",
                (rs, row) -> new Guard(rs.getLong(1), rs.getObject(2, Long.class), rs.getLong(3)), spuId);
    }

    public void initializeVersionBase(long spuId, long versionBase) {
        if (jdbc.update("update product_projection_spu_guard set es_version_base=? where spu_id=? and es_version_base is null", versionBase, spuId) != 1)
            throw new IllegalStateException("投影版本基数初始化发生并发冲突");
    }

    public boolean knownTarget(long spuId) {
        Long count = jdbc.queryForObject("select case when exists(select 1 from product_spu where id=?) or exists(select 1 from product_projection_spu_guard where spu_id=?)"
                + " or exists(select 1 from product_projection_task where spu_id=?) then 1 else 0 end", Long.class, spuId, spuId, spuId);
        return count != null && count > 0;
    }

    public List<String> pendingSources(long spuId) {
        return jdbc.query("select distinct global_xid from product_projection_task where spu_id=? and completed_at is null and global_xid is not null",
                (rs, row) -> rs.getString(1), spuId);
    }

    public long maximumTaskId(long spuId) {
        Long maximum = jdbc.queryForObject("select coalesce(max(id),0) from product_projection_task where spu_id=?", Long.class, spuId);
        return maximum == null ? 0 : maximum;
    }

    public List<Long> startupTargets() {
        return jdbc.queryForList("select id from product_spu union select spu_id from product_projection_spu_guard order by 1", Long.class);
    }

    public void complete(long id, long spuId, long appliedTaskId, String mode) {
        if (jdbc.update("update product_projection_task set completed_at=current_timestamp,attempts=attempts+1,last_error_type=null,"
                + "next_attempt_at=null,applied_task_id=?,publish_mode=? where id=? and completed_at is null", appliedTaskId, mode, id) != 1)
            throw new IllegalStateException("投影任务完成状态冲突");
        jdbc.update("update product_projection_spu_guard set last_applied_task_id=greatest(last_applied_task_id,?) where spu_id=?", appliedTaskId, spuId);
    }

    public void failed(long id, String errorType) {
        jdbc.update("update product_projection_task set attempts=attempts+1,last_error_type=?,next_attempt_at=? where id=? and completed_at is null",
                errorType, Timestamp.from(Instant.now().plusSeconds(2)), id);
    }

}
