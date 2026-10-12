-- T1 已批准结构，2026-10-11：用户授权后按各业务库分别执行，唯一索引已核对。
-- 不是启动脚本，禁止将本文件直接整份执行到一个数据库。
-- 第一段仅面向 order-service 当前配置实际连接的 MySQL 库。
-- 第二段仅面向 promotion-service 当前配置实际连接的 MySQL 库。
-- 执行前核对实际库、同名表元数据与现有迁移约定；不改任何旧表/历史行。

CREATE TABLE order_operation_receipt (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    operation_id VARCHAR(256) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tool_name VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    parameters_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    hash_version SMALLINT NOT NULL,
    receipt_version SMALLINT NOT NULL,
    response_json JSON NULL,
    created_at DATETIME(3) NOT NULL,
    completed_at DATETIME(3) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_operation_user (user_id, operation_id),
    KEY idx_order_operation_completed (completed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 以下段落改用 promotion-service 的实际业务库连接，不使用 order-service 连接。
CREATE TABLE promotion_operation_receipt (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    operation_id VARCHAR(256) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tool_name VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    parameters_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    hash_version SMALLINT NOT NULL,
    receipt_version SMALLINT NOT NULL,
    response_json JSON NULL,
    created_at DATETIME(3) NOT NULL,
    completed_at DATETIME(3) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_promotion_operation_user (user_id, operation_id),
    KEY idx_promotion_operation_completed (completed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- response_json/completed_at 仅在未提交认领事务内为空；不得提交空回执行。
-- 同事务必须完成业务写入与回执 UPDATE；失败整事务回滚，不写失败回执。
-- checkout 受 Seata AT 保护，回执读取还必须检查全局锁/全局提交，普通 SELECT 不足。
-- 不添加自动清理/TTL：删除已成功操作记录会允许旧 operationId 再次产生副作用。
-- 回滚保留两张表及数据，只撤回代码并暂停旧Agent写；本文不提供 DROP/DELETE。
