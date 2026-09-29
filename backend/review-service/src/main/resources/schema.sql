-- 评价库建表脚本。

create table if not exists review (
    id bigint auto_increment primary key,
    spu_id bigint not null,
    sku_id bigint not null,
    order_id bigint not null,
    order_item_id bigint not null,
    user_id varchar(32) not null,
    rating tinyint not null,
    content varchar(1000),
    is_anonymous tinyint not null default 0,
    -- PENDING 待审核 / PUBLISHED 已发布 / HIDDEN 已隐藏
    status varchar(16) not null default 'PUBLISHED',
    -- 商家回复。原实现把评价做成只写孤岛，商家侧无从回应
    reply_content varchar(500),
    reply_at datetime,
    useful_count int not null default 0,
    created_at datetime not null,
    -- 「同一订单行只能评价一次」由数据库唯一约束保证。
    -- 原实现用 selectCount 在应用层判断，并发下两次提交都能通过检查
    constraint uk_review_order_item_user unique (order_item_id, user_id),
    index idx_review_spu_status (spu_id, status),
    index idx_review_user (user_id)
);

-- 评价图片独立成表。原实现把图片塞进 review 表的一个逗号分隔 varchar，
-- 既无法限制数量，也无法单独删除某张图（比如审核不通过的）
create table if not exists review_image (
    id bigint auto_increment primary key,
    review_id bigint not null,
    url varchar(512) not null,
    sort int not null default 0,
    index idx_review_image_review (review_id)
);
