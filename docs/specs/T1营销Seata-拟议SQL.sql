-- 已获用户授权并在2026-10-11执行；只新增营销undo_log，不修改旧表或旧业务行。
-- 结构对齐当前商品库已运行的 Seata 2.5.0 AT undo_log。
-- 创建前只读核对同名表是否存在及结构；禁止用IF NOT EXISTS隐藏不兼容结构。
CREATE TABLE undo_log (
    branch_id BIGINT NOT NULL COMMENT 'branch transaction id',
    xid VARCHAR(128) NOT NULL COMMENT 'global transaction id',
    context VARCHAR(128) NOT NULL COMMENT 'undo_log context',
    rollback_info LONGBLOB NOT NULL COMMENT 'rollback info',
    log_status INT NOT NULL COMMENT '0:normal 1:defense',
    log_created DATETIME(6) NOT NULL,
    log_modified DATETIME(6) NOT NULL,
    UNIQUE KEY ux_undo_log (xid, branch_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AT transaction mode undo table';
-- 回滚保留表和事务记录，无DROP/DELETE/旧业务数据UPDATE。
