-- ==================== MySQL 幂等迁移 ====================
-- 这个文件会排在 schema.sql **之前**执行（platform=mysql 时 Spring 的默认
-- 脚本列表是 [schema-mysql.sql, schema.sql]），空库上表还不存在，所以每条
-- 迁移都必须先判表存在：不存在就整段降级为 DO 0，建表交给随后的 schema.sql。
--
-- 为什么不用 `ADD COLUMN IF NOT EXISTS`：MySQL 8 不支持，只有 H2/MariaDB 支持。
-- 为什么需要它：`create table if not exists` 不会给**已经存在**的表补列，
-- 开发机上的库数据都还在，后加的列不在这里显式补就永远是「表里没有这一列」。
--
-- ⚠️ 被 PREPARE 的 DDL 是一段**字符串**，里面再出现单引号就必须写两遍。
-- 所以这里一律不写 COMMENT：注释里只要有一个单引号，整条语句就会在外层
-- 字符串处被截断，报出来的却是「1064 语法错误」，看不出真正原因。
--
-- ⚠️ 加列与加约束必须**分开成两段**，各自带前置条件：列可能已存在而约束还没建
-- （先加列、后补约束的库），合成一条会让「列已存在」的库直接报 1060 起不来。
--
-- ⚠️ 迁移的失败方式只能是「跳过 + 留下可读告警」，不能是「把服务拖死」：
-- 加唯一约束撞上历史重复数据时，1062 会让整个应用起不来（日志里连现场都难找）。
-- 所以先数一遍重复行，有重复就写进 migration_warning 表并跳过该约束。

-- 迁移告警表：迁移脚本「跳过但留痕」的出口。
-- 迁移在应用启动时执行，此刻日志框架还没完全就绪，而启动失败又不留现场；
-- 把「本可以拦住启动的问题」写进一张表，让服务照常起来、管理员按表排查。
SET @t_migration_warning := (SELECT COUNT(*) FROM information_schema.TABLES
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'migration_warning');
SET @ddl := IF(@t_migration_warning = 0,
  'CREATE TABLE migration_warning (id bigint auto_increment primary key, script varchar(64) not null, target varchar(96) not null, detail varchar(500) not null, at datetime default current_timestamp)',
  'DO 0');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 商家回复三列：原实现把评价做成只写孤岛，商家侧无从回应。
SET @t_review_reply_content := (SELECT COUNT(*) FROM information_schema.TABLES
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'review');
SET @c_review_reply_content := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'review' AND COLUMN_NAME = 'reply_content');
SET @ddl := IF(@t_review_reply_content = 0, 'DO 0', IF(@c_review_reply_content = 0, 'ALTER TABLE review ADD COLUMN reply_content varchar(500) NULL', 'DO 0'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @t_review_reply_at := (SELECT COUNT(*) FROM information_schema.TABLES
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'review');
SET @c_review_reply_at := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'review' AND COLUMN_NAME = 'reply_at');
SET @ddl := IF(@t_review_reply_at = 0, 'DO 0', IF(@c_review_reply_at = 0, 'ALTER TABLE review ADD COLUMN reply_at datetime NULL', 'DO 0'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @t_review_reply_by := (SELECT COUNT(*) FROM information_schema.TABLES
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'review');
SET @c_review_reply_by := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'review' AND COLUMN_NAME = 'reply_by');
SET @ddl := IF(@t_review_reply_by = 0, 'DO 0', IF(@c_review_reply_by = 0, 'ALTER TABLE review ADD COLUMN reply_by varchar(32) NULL', 'DO 0'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 隐藏留痕三列：隐藏是能被滥用的动作（商家隐藏差评），不能是不留痕的状态位。
SET @t_review_hidden_reason := (SELECT COUNT(*) FROM information_schema.TABLES
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'review');
SET @c_review_hidden_reason := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'review' AND COLUMN_NAME = 'hidden_reason');
SET @ddl := IF(@t_review_hidden_reason = 0, 'DO 0', IF(@c_review_hidden_reason = 0, 'ALTER TABLE review ADD COLUMN hidden_reason varchar(255) NULL', 'DO 0'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @t_review_hidden_by := (SELECT COUNT(*) FROM information_schema.TABLES
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'review');
SET @c_review_hidden_by := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'review' AND COLUMN_NAME = 'hidden_by');
SET @ddl := IF(@t_review_hidden_by = 0, 'DO 0', IF(@c_review_hidden_by = 0, 'ALTER TABLE review ADD COLUMN hidden_by varchar(32) NULL', 'DO 0'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @t_review_hidden_at := (SELECT COUNT(*) FROM information_schema.TABLES
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'review');
SET @c_review_hidden_at := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'review' AND COLUMN_NAME = 'hidden_at');
SET @ddl := IF(@t_review_hidden_at = 0, 'DO 0', IF(@c_review_hidden_at = 0, 'ALTER TABLE review ADD COLUMN hidden_at datetime NULL', 'DO 0'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 一人一票：原实现只对 useful_count 做 +1、userId 收下不用。
-- 先加列、再补约束：列可能已存在而索引还没建
SET @t_uk_review_useful := (SELECT COUNT(*) FROM information_schema.TABLES
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'review_useful');
SET @i_uk_review_useful := (SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'review_useful' AND INDEX_NAME = 'uk_review_useful');
SET @d_uk_review_useful := 0;
SET @dupsql_uk_review_useful := IF(@t_uk_review_useful = 0 OR @i_uk_review_useful > 0, 'DO 0',
  'SELECT COUNT(*) INTO @d_uk_review_useful FROM (SELECT 1 FROM review_useful WHERE review_id IS NOT NULL AND user_id IS NOT NULL GROUP BY review_id, user_id HAVING COUNT(*) > 1) x');
PREPARE dupstmt FROM @dupsql_uk_review_useful; EXECUTE dupstmt; DEALLOCATE PREPARE dupstmt;

-- 有重复值：写进 migration_warning（管理员能查到）并跳过约束，不让启动挂掉
SET @warnsql_uk_review_useful := IF(@d_uk_review_useful > 0, CONCAT(
  'INSERT INTO migration_warning(script,target,detail) VALUES (''schema-mysql.sql'',''uk_review_useful'', CONCAT(''review_useful.review_id, user_id 有 '', ',
  @d_uk_review_useful,
  ', '' 组重复值，唯一约束未创建；请先归并这些行再重启''))'), 'DO 0');
PREPARE warnstmt FROM @warnsql_uk_review_useful; EXECUTE warnstmt; DEALLOCATE PREPARE warnstmt;

SET @ddl := IF(@t_uk_review_useful = 0, 'DO 0', IF(@i_uk_review_useful > 0, 'DO 0',
  IF(@d_uk_review_useful > 0, 'DO 0', 'ALTER TABLE review_useful ADD UNIQUE KEY uk_review_useful (review_id, user_id)')));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
