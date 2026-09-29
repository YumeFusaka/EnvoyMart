-- 用户库建表脚本。
--
-- 索引一律内联在 CREATE TABLE 里，不写独立的 `create index if not exists`：
-- 后者是 H2/PostgreSQL 语法，**MySQL 不支持**，会在初始化阶段直接失败。
--
-- 字段说明用 `--` 行注释而非内联 COMMENT 子句：后者在 H2 的 MySQL 兼容模式下
-- 支持情况不稳定，而行注释两种库都能解析。
create table if not exists sys_user (
    id varchar(32) primary key,
    username varchar(64) not null unique,
    password varchar(128) not null,
    nickname varchar(64) not null,
    avatar varchar(255),
    phone varchar(20),
    email varchar(128),
    -- 0 禁用 / 1 正常。禁用后拒绝登录，但历史订单仍可查询
    status tinyint not null default 1,
    role_name varchar(32) not null,
    created_at datetime not null,
    updated_at datetime not null
);

-- 收货地址簿。原实现把地址当作订单上的三个字符串，导致「选已有地址下单」无法实现。
create table if not exists user_address (
    id bigint auto_increment primary key,
    user_id varchar(32) not null,
    receiver_name varchar(64) not null,
    receiver_phone varchar(20) not null,
    -- 省市区拆成三列而非一个字符串：按区域统计订单量、匹配偏远地区运费规则都要用到
    province varchar(32) not null,
    city varchar(32) not null,
    district varchar(32) not null,
    detail varchar(255) not null,
    -- 默认地址：同一用户至多一条为 1。
    -- 唯一性由应用层在同一事务内「先清后置」保证，不加数据库唯一索引——
    -- 唯一索引会让清除与设置之间的中间态直接冲突。
    is_default tinyint not null default 0,
    tag varchar(16),
    created_at datetime not null,
    updated_at datetime not null,
    index idx_user_address_user (user_id, is_default)
);
