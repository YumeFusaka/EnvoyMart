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

-- 商品库的老库补列。
SET @t_product_spu_aggregate_version := (SELECT COUNT(*) FROM information_schema.TABLES
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'product_spu');
SET @c_product_spu_aggregate_version := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'product_spu' AND COLUMN_NAME = 'aggregate_version');
SET @ddl := IF(@t_product_spu_aggregate_version = 0, 'DO 0', IF(@c_product_spu_aggregate_version = 0, 'ALTER TABLE product_spu ADD COLUMN aggregate_version bigint NOT NULL DEFAULT 0', 'DO 0'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 销量台账的唯一键必须是**订单行**，不是「一笔订单里的一个商品」。
-- 用 (order_id, spu_id) 做主键会把同一商品买两个规格的第 2、3 行当成
-- 「消息重投」丢掉 —— 销量少算，而且少算得没有任何动静（实测下单三件只加 1）。
SET @t_product_sales_ledger_order_item_id := (SELECT COUNT(*) FROM information_schema.TABLES
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'product_sales_ledger');
SET @c_product_sales_ledger_order_item_id := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'product_sales_ledger' AND COLUMN_NAME = 'order_item_id');
SET @ddl := IF(@t_product_sales_ledger_order_item_id = 0, 'DO 0', IF(@c_product_sales_ledger_order_item_id = 0, 'ALTER TABLE product_sales_ledger ADD COLUMN order_item_id bigint NULL', 'DO 0'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 老库主键还是 (order_id, spu_id)：结构不跟着改，同一份代码在新老库上行为不同
-- （老库照样丢销量）。换主键要求 order_item_id 全部有值且不重复，而历史行没有
-- 订单行号可回填，所以这里只做**检测 + 告警**：这张表每次启动都由 data.sql 从
-- 种子重建，drop 掉重启即可，不必在迁移里冒险改主键。
SET @t_ledger := (SELECT COUNT(*) FROM information_schema.TABLES
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'product_sales_ledger');
SET @i_ledger_old_pk := (SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'product_sales_ledger'
    AND INDEX_NAME = 'PRIMARY' AND COLUMN_NAME = 'spu_id');
SET @ledger_bad := 0;
SET @chk := IF(@t_ledger = 0 OR @i_ledger_old_pk = 0, 'DO 0',
  'SELECT COUNT(*) INTO @ledger_bad FROM product_sales_ledger WHERE order_item_id IS NULL');
PREPARE chkstmt FROM @chk; EXECUTE chkstmt; DEALLOCATE PREPARE chkstmt;

SET @warnsql := IF(@ledger_bad > 0, CONCAT(
  'INSERT INTO migration_warning(script,target,detail) VALUES (''schema-mysql.sql'',''product_sales_ledger.PRIMARY'', CONCAT(''主键仍是 (order_id, spu_id)，'', ',
  @ledger_bad,
  ', '' 行 order_item_id 为空；这张表每次启动由 data.sql 重建，drop 后重启即可''))'), 'DO 0');
PREPARE warnstmt FROM @warnsql; EXECUTE warnstmt; DEALLOCATE PREPARE warnstmt;

SET @t_ledger_idx := (SELECT COUNT(*) FROM information_schema.TABLES
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'product_sales_ledger');
SET @i_ledger_order := (SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'product_sales_ledger' AND INDEX_NAME = 'idx_sales_ledger_order');
SET @ddl := IF(@t_ledger_idx = 0, 'DO 0', IF(@i_ledger_order = 0, 'ALTER TABLE product_sales_ledger ADD INDEX idx_sales_ledger_order (order_id)', 'DO 0'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
