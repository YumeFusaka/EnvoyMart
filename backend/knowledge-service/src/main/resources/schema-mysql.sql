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

-- T1 图谱诊断证据字段：旧库逐列补齐，避免重建 graph_build_failure 丢失历史记录。
SET @t_gbf := (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'graph_build_failure');
SET @c_gbf_raw := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'graph_build_failure' AND COLUMN_NAME = 'raw_candidate');
SET @ddl := IF(@t_gbf = 0 OR @c_gbf_raw > 0, 'DO 0', 'ALTER TABLE graph_build_failure ADD COLUMN raw_candidate text'); PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @c_gbf_nhk := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'graph_build_failure' AND COLUMN_NAME = 'normalized_head_kind');
SET @ddl := IF(@t_gbf = 0 OR @c_gbf_nhk > 0, 'DO 0', 'ALTER TABLE graph_build_failure ADD COLUMN normalized_head_kind varchar(32)'); PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @c_gbf_nh := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'graph_build_failure' AND COLUMN_NAME = 'normalized_head');
SET @ddl := IF(@t_gbf = 0 OR @c_gbf_nh > 0, 'DO 0', 'ALTER TABLE graph_build_failure ADD COLUMN normalized_head varchar(255)'); PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @c_gbf_ntk := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'graph_build_failure' AND COLUMN_NAME = 'normalized_tail_kind');
SET @ddl := IF(@t_gbf = 0 OR @c_gbf_ntk > 0, 'DO 0', 'ALTER TABLE graph_build_failure ADD COLUMN normalized_tail_kind varchar(32)'); PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @c_gbf_nt := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'graph_build_failure' AND COLUMN_NAME = 'normalized_tail');
SET @ddl := IF(@t_gbf = 0 OR @c_gbf_nt > 0, 'DO 0', 'ALTER TABLE graph_build_failure ADD COLUMN normalized_tail varchar(255)'); PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @c_gbf_rel := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'graph_build_failure' AND COLUMN_NAME = 'relation');
SET @ddl := IF(@t_gbf = 0 OR @c_gbf_rel > 0, 'DO 0', 'ALTER TABLE graph_build_failure ADD COLUMN relation varchar(64)'); PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @c_gbf_quote := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'graph_build_failure' AND COLUMN_NAME = 'quote');
SET @ddl := IF(@t_gbf = 0 OR @c_gbf_quote > 0, 'DO 0', 'ALTER TABLE graph_build_failure ADD COLUMN quote text'); PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @c_gbf_qs := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'graph_build_failure' AND COLUMN_NAME = 'quote_offset_start');
SET @ddl := IF(@t_gbf = 0 OR @c_gbf_qs > 0, 'DO 0', 'ALTER TABLE graph_build_failure ADD COLUMN quote_offset_start int'); PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @c_gbf_qe := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'graph_build_failure' AND COLUMN_NAME = 'quote_offset_end');
SET @ddl := IF(@t_gbf = 0 OR @c_gbf_qe > 0, 'DO 0', 'ALTER TABLE graph_build_failure ADD COLUMN quote_offset_end int'); PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @c_gbf_alias := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'graph_build_failure' AND COLUMN_NAME = 'alias_hit');
SET @ddl := IF(@t_gbf = 0 OR @c_gbf_alias > 0, 'DO 0', 'ALTER TABLE graph_build_failure ADD COLUMN alias_hit tinyint'); PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @c_gbf_chunk := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'graph_build_failure' AND COLUMN_NAME = 'chunk_id');
SET @ddl := IF(@t_gbf = 0 OR @c_gbf_chunk > 0, 'DO 0', 'ALTER TABLE graph_build_failure ADD COLUMN chunk_id varchar(64)'); PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @t_graph_build_failure := (SELECT COUNT(*) FROM information_schema.TABLES
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'graph_build_failure');
SET @ddl := IF(@t_graph_build_failure = 0,
  'CREATE TABLE graph_build_failure (id bigint auto_increment primary key, batch_id varchar(64) not null, doc_no varchar(32), entity_key varchar(255), stage varchar(32) not null, reason_code varchar(64) not null, detail varchar(500), retryable tinyint not null default 0, occurred_at datetime not null, index idx_graph_failure_batch (batch_id), index idx_graph_failure_doc (doc_no), index idx_graph_failure_reason (stage, reason_code))',
  'DO 0');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- disabled_by：文档停用原因（MANUAL 运营手动 / PRODUCT_OFF 商品下架级联）。
SET @t_knowledge_document_disabled_by := (SELECT COUNT(*) FROM information_schema.TABLES
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'knowledge_document');
SET @c_knowledge_document_disabled_by := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'knowledge_document' AND COLUMN_NAME = 'disabled_by');
SET @ddl := IF(@t_knowledge_document_disabled_by = 0, 'DO 0', IF(@c_knowledge_document_disabled_by = 0, 'ALTER TABLE knowledge_document ADD COLUMN disabled_by varchar(32) NULL', 'DO 0'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
