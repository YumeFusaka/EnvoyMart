# 数据库设计

## 概述

各微服务独立数据库，通过 schema.sql 文件在启动时自动初始化。默认使用 H2 内存数据库，仅需切换配置即可切换为 MySQL。

## 服务数据模型

### auth-service

```sql
-- 用户表
create table if not exists user (
    id       bigint auto_increment primary key,
    username varchar(32)  not null unique,
    password varchar(128) not null,
    nickname varchar(64),
    avatar   varchar(255),
    role_name varchar(32) default 'user'
);
```

### product-service

```sql
-- 商品表
create table if not exists product (
    id                bigint auto_increment primary key,
    name              varchar(128)   not null,
    subtitle          varchar(255),
    category          varchar(32),
    brand             varchar(32),
    tags              varchar(255),
    price             decimal(10,2)  not null,
    stock             int            not null default 0,
    monthly_sales     int            not null default 0,
    image             varchar(255),
    sales_copy        varchar(512),
    semantic_keywords varchar(255),
    description       text
);
```

### order-service

```sql
-- 购物车条目
create table if not exists cart_item (
    id         bigint auto_increment primary key,
    user_id    varchar(32) not null,
    product_id bigint      not null,
    quantity   int         not null,
    index idx_cart_item_user (user_id)
);

-- 订单主表
create table if not exists shop_order (
    id              bigint auto_increment primary key,
    order_no        varchar(64)  not null unique,
    user_id         varchar(32)  not null,
    recipient_name  varchar(64)  not null,
    recipient_phone varchar(32)  not null,
    address         varchar(255) not null,
    total_amount    decimal(10,2) not null,
    status          varchar(32)  not null default 'DELIVERING',
    created_at      timestamp,
    index idx_shop_order_user (user_id, status),
    index idx_shop_order_created (created_at desc)
);

-- 订单明细
create table if not exists shop_order_item (
    id            bigint auto_increment primary key,
    order_id      bigint       not null,
    product_id    bigint       not null,
    product_name  varchar(128) not null,
    product_image varchar(255),
    unit_price    decimal(10,2) not null,
    quantity      int          not null,
    subtotal      decimal(10,2) not null,
    index idx_shop_order_item_order (order_id)
);
```

### payment-service

```sql
create table if not exists payment (
    id             bigint auto_increment primary key,
    order_id       bigint       not null,
    order_no       varchar(64)  not null,
    user_id        varchar(64)  not null,
    amount         decimal(10,2) not null,
    status         varchar(32)  not null default 'PENDING',
    transaction_no varchar(128),
    paid_at        timestamp,
    created_at     timestamp    not null default current_timestamp
);
```

### review-service

```sql
create table if not exists review (
    id         bigint auto_increment primary key,
    product_id bigint       not null,
    order_id   bigint       not null,
    user_id    varchar(64)  not null,
    rating     int          not null default 5,
    content    text,
    images     varchar(1024),
    created_at timestamp    not null default current_timestamp
);
```

## 索引说明

| 表 | 索引 | 作用 |
|----|------|------|
| shop_order | (user_id, status) | 用户订单列表按状态筛选 |
| shop_order | (created_at desc) | 近期订单排序 |
| shop_order_item | (order_id) | 订单明细查询 |
| cart_item | (user_id) | 用户购物车查询 |

## 隔离策略

每个微服务独立数据源，通过 REST API 跨服务查询。避免分布式事务，通过 RabbitMQ 事件驱动最终一致性。订单创建→库存扣减→支付回调的流程通过异步事件衔接。
