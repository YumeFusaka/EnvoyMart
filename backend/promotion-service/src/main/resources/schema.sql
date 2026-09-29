-- 营销库建表脚本：优惠券 / 促销活动。
--
-- 营销独立成服务而不是并进订单服务，理由是**生命周期不同源**：
-- 券在订单之前就存在（用户先领券，可能几天后才用），订单结束之后券的统计还在继续
-- （核销率、过期率）。把它绑进订单的事务边界，两边都别扭。

create table if not exists coupon (
    id bigint auto_increment primary key,
    name varchar(128) not null,
    -- FIXED 满减 / DISCOUNT 折扣
    type varchar(16) not null,
    -- 满减金额（分）；折扣券为空
    amount bigint,
    -- 折扣率，如 0.85 表示八五折；满减券为空
    discount decimal(3,2),
    -- 使用门槛（分），0 表示无门槛
    threshold bigint not null default 0,
    -- ALL 全场 / CATEGORY 限类目 / SPU 限商品
    scope_type varchar(16) not null default 'ALL',
    scope_ids varchar(1024),
    -- 发行量与已领取量。领取时用条件更新
    -- （update ... set received_count = received_count + 1 where received_count < total_count），
    -- 与库存扣减同一套模式，不引入新的并发原语
    total_count int not null,
    received_count int not null default 0,
    -- 领取后有效天数；为空则用下面两个绝对时间
    valid_days int,
    start_time datetime,
    end_time datetime,
    status tinyint not null default 1,
    created_at datetime not null
);

create table if not exists user_coupon (
    id bigint auto_increment primary key,
    user_id varchar(32) not null,
    coupon_id bigint not null,
    -- UNUSED / USED / EXPIRED
    status varchar(16) not null,
    -- 核销在哪张订单上。券一旦用过就要能追溯到订单，否则对账时说不清优惠去了哪
    order_no varchar(32),
    received_at datetime not null,
    used_at datetime,
    expire_at datetime not null,
    index idx_user_coupon_user (user_id, status),
    index idx_user_coupon_coupon (coupon_id),
    -- 过期扫描走这个索引
    index idx_user_coupon_expire (status, expire_at)
);

create table if not exists promotion_activity (
    id bigint auto_increment primary key,
    name varchar(128) not null,
    -- FULL_REDUCTION 满减 / DISCOUNT 折扣 / FREE_SHIPPING 包邮
    type varchar(24) not null,
    description varchar(500),
    start_time datetime not null,
    end_time datetime not null,
    status tinyint not null default 1,
    created_at datetime not null,
    index idx_activity_time (status, start_time, end_time)
);

-- 活动的阶梯规则：「满 300 减 30」和「满 500 减 60」是同一活动下的两条规则。
-- 拆成独立表而不是塞进 JSON：阶梯需要被查询——给定订单金额要能直接找出适用档位
create table if not exists promotion_rule (
    id bigint auto_increment primary key,
    activity_id bigint not null,
    -- 门槛（分）
    threshold bigint not null,
    -- REDUCE 减固定金额 / RATE 打折 / FREE_SHIPPING 包邮
    benefit_type varchar(24) not null,
    benefit_value bigint,
    sort int not null default 0,
    index idx_promotion_rule_activity (activity_id, threshold)
);
