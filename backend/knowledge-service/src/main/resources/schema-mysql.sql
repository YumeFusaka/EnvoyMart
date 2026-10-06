-- MySQL 专用幂等迁移。
--
-- 为什么需要它：`create table if not exists` 只保证「表不存在时建出来」，
-- 对**已经存在**的表不会补新列——开发机上的知识库在主键、语料都还在，
-- 于是新加的 `disabled_by` 永远是「表里没有这一列」，服务起来后第一次查询就 500。
-- H2 单测每次都是空库、走 schema.sql 全新建成，所以只在 MySQL 下跑这一份。
--
-- 语法上不能用 `ADD COLUMN IF NOT EXISTS`：MySQL 8 不支持，只有 H2/MariaDB 支持。
-- 所以用 information_schema 判断 + 动态 SQL，重复执行安全。
SET @has_disabled_by := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'knowledge_document'
    AND COLUMN_NAME = 'disabled_by'
);
SET @ddl := IF(@has_disabled_by = 0,
  'ALTER TABLE knowledge_document ADD COLUMN disabled_by varchar(32) NULL COMMENT ''MANUAL 运营手动停用 / PRODUCT_OFF 商品下架级联停用''',
  'DO 0');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;