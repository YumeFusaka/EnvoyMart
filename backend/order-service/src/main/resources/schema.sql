-- 订单库建表脚本。
--
-- 索引内联在 CREATE TABLE 里，而不是单独写 `create index if not exists ...`：
-- 后者是 H2/PostgreSQL 的语法，**MySQL 不支持**，换成本地 MySQL 时会在初始化阶段直接失败。
-- 内联声明没有这个问题——表创建本身有 `if not exists` 守卫，索引随之幂等，H2 与 MySQL 都能跑。
create table if not exists cart_item (
    id bigint auto_increment primary key,
    user_id varchar(32) not null,
    product_id bigint not null,
    quantity int not null,
    index idx_cart_item_user (user_id)
);

create table if not exists shop_order (
    id bigint auto_increment primary key,
    order_no varchar(64) not null unique,
    user_id varchar(32) not null,
    recipient_name varchar(64) not null,
    recipient_phone varchar(32) not null,
    address varchar(255) not null,
    total_amount decimal(10,2) not null,
    status varchar(32) not null,
    created_at timestamp,
    -- 高频查询走 user_id + status 维度
    index idx_shop_order_user (user_id, status),
    index idx_shop_order_created (created_at)
);

create table if not exists shop_order_item (
    id bigint auto_increment primary key,
    order_id bigint not null,
    product_id bigint not null,
    product_name varchar(128) not null,
    product_image varchar(255),
    unit_price decimal(10,2) not null,
    quantity int not null,
    subtotal decimal(10,2) not null,
    index idx_shop_order_item_order (order_id)
);
