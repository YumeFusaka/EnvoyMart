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
    reply_by varchar(32),
    -- 隐藏原因与操作人。隐藏是能被滥用的动作（商家隐藏差评），不能是不留痕的状态位。
    -- 恢复时清空，让「HIDDEN」与「有隐藏原因」始终是同一件事的两个说法
    hidden_reason varchar(255),
    hidden_by varchar(32),
    hidden_at datetime,
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

-- 「有用」一票一人。原先只对 review.useful_count 做 +1，userId 收下了却没用，
-- 于是同一个账号多调几次就能把那个数字顶上去 —— 而它是所有用户都看得见的。
-- 计数仍是 review.useful_count（列表页不该为了一个数字多查一张表），
-- 这张表只负责回答「这个人投过没有」。
create table if not exists review_useful (
    id bigint auto_increment primary key,
    review_id bigint not null,
    user_id varchar(32) not null,
    created_at datetime not null,
    constraint uk_review_useful unique (review_id, user_id),
    index idx_review_useful_user (user_id)
);
