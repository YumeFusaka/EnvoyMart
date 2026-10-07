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

SET @t_shop_order_admin_remark := (SELECT COUNT(*) FROM information_schema.TABLES
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'shop_order');
SET @c_shop_order_admin_remark := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'shop_order' AND COLUMN_NAME = 'admin_remark');
SET @ddl := IF(@t_shop_order_admin_remark = 0, 'DO 0', IF(@c_shop_order_admin_remark = 0, 'ALTER TABLE shop_order ADD COLUMN admin_remark varchar(255) NULL', 'DO 0'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @t_shop_order_user_coupon_id := (SELECT COUNT(*) FROM information_schema.TABLES
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'shop_order');
SET @c_shop_order_user_coupon_id := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'shop_order' AND COLUMN_NAME = 'user_coupon_id');
SET @ddl := IF(@t_shop_order_user_coupon_id = 0, 'DO 0', IF(@c_shop_order_user_coupon_id = 0, 'ALTER TABLE shop_order ADD COLUMN user_coupon_id bigint NULL', 'DO 0'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 下单幂等键：允许 NULL 是刻意的（没带幂等键的调用方不参与去重），MySQL 唯一约束允许多个 NULL 正好表达这件事。
SET @t_shop_order_request_id := (SELECT COUNT(*) FROM information_schema.TABLES
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'shop_order');
SET @c_shop_order_request_id := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'shop_order' AND COLUMN_NAME = 'request_id');
SET @ddl := IF(@t_shop_order_request_id = 0, 'DO 0', IF(@c_shop_order_request_id = 0, 'ALTER TABLE shop_order ADD COLUMN request_id varchar(64) NULL', 'DO 0'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 先加列、再补约束：列可能已存在而索引还没建
SET @t_uk_order_request := (SELECT COUNT(*) FROM information_schema.TABLES
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'shop_order');
SET @i_uk_order_request := (SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'shop_order' AND INDEX_NAME = 'uk_order_request');
SET @d_uk_order_request := 0;
SET @dupsql_uk_order_request := IF(@t_uk_order_request = 0 OR @i_uk_order_request > 0, 'DO 0',
  'SELECT COUNT(*) INTO @d_uk_order_request FROM (SELECT 1 FROM shop_order WHERE request_id IS NOT NULL GROUP BY request_id HAVING COUNT(*) > 1) x');
PREPARE dupstmt FROM @dupsql_uk_order_request; EXECUTE dupstmt; DEALLOCATE PREPARE dupstmt;

-- 有重复值：写进 migration_warning（管理员能查到）并跳过约束，不让启动挂掉
SET @warnsql_uk_order_request := IF(@d_uk_order_request > 0, CONCAT(
  'INSERT INTO migration_warning(script,target,detail) VALUES (''schema-mysql.sql'',''uk_order_request'', CONCAT(''shop_order.request_id 有 '', ',
  @d_uk_order_request,
  ', '' 组重复值，唯一约束未创建；请先归并这些行再重启''))'), 'DO 0');
PREPARE warnstmt FROM @warnsql_uk_order_request; EXECUTE warnstmt; DEALLOCATE PREPARE warnstmt;

SET @ddl := IF(@t_uk_order_request = 0, 'DO 0', IF(@i_uk_order_request > 0, 'DO 0',
  IF(@d_uk_order_request > 0, 'DO 0', 'ALTER TABLE shop_order ADD UNIQUE KEY uk_order_request (request_id)')));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @t_after_sale_return_carrier := (SELECT COUNT(*) FROM information_schema.TABLES
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'after_sale');
SET @c_after_sale_return_carrier := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'after_sale' AND COLUMN_NAME = 'return_carrier');
SET @ddl := IF(@t_after_sale_return_carrier = 0, 'DO 0', IF(@c_after_sale_return_carrier = 0, 'ALTER TABLE after_sale ADD COLUMN return_carrier varchar(32) NULL', 'DO 0'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @t_after_sale_return_tracking_no := (SELECT COUNT(*) FROM information_schema.TABLES
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'after_sale');
SET @c_after_sale_return_tracking_no := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'after_sale' AND COLUMN_NAME = 'return_tracking_no');
SET @ddl := IF(@t_after_sale_return_tracking_no = 0, 'DO 0', IF(@c_after_sale_return_tracking_no = 0, 'ALTER TABLE after_sale ADD COLUMN return_tracking_no varchar(64) NULL', 'DO 0'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @t_after_sale_returned_at := (SELECT COUNT(*) FROM information_schema.TABLES
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'after_sale');
SET @c_after_sale_returned_at := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'after_sale' AND COLUMN_NAME = 'returned_at');
SET @ddl := IF(@t_after_sale_returned_at = 0, 'DO 0', IF(@c_after_sale_returned_at = 0, 'ALTER TABLE after_sale ADD COLUMN returned_at datetime NULL', 'DO 0'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
