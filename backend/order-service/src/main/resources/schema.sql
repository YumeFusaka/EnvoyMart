-- 交易库建表脚本：购物车 / 订单 / 履约 / 售后。
--
-- 这四块合在一个库而不是拆成独立服务，是因为它们共享订单这个聚合根——
-- 同一个状态机、同一个事务边界、同一套幂等键。拆出去只会把一次本地事务
-- 摊成分布式事务，换不来任何内聚性。
--
-- 索引一律内联在 CREATE TABLE 里：独立的 `create index if not exists` 是
-- H2/PostgreSQL 语法，**MySQL 不支持**，会在初始化阶段直接失败。

-- ==================== 购物车 ====================

create table if not exists cart_item (
    id bigint auto_increment primary key,
    user_id varchar(32) not null,
    spu_id bigint not null,
    -- 引用 SKU 而非 SPU：加购时就必须已经选定规格，否则下单时无法确定价格与库存
    sku_id bigint not null,
    quantity int not null,
    -- 是否勾选参与结算。购物车里未勾选的条目下单时忽略
    selected tinyint not null default 1,
    created_at datetime not null,
    updated_at datetime not null,
    -- 「同商品累加」由唯一键保证幂等，而不是靠「先查后写」的应用逻辑——
    -- 后者在并发加购时会插入两条
    constraint uk_cart_user_sku unique (user_id, sku_id)
);

-- ==================== 订单 ====================

create table if not exists shop_order (
    id bigint auto_increment primary key,
    order_no varchar(32) not null unique,
    user_id varchar(32) not null,
    -- CREATED / PAID / SHIPPED / RECEIVED / COMPLETED / CANCELLED / CLOSED / REFUNDING / REFUNDED
    status varchar(24) not null,

    -- 金额一律以「分」为单位。四个字段分开存而不是只存应付金额：
    -- 售后按比例退款要按商品总额算，运费是否退还要单独判断，优惠需要能追溯
    total_amount bigint not null,
    freight_amount bigint not null default 0,
    discount_amount bigint not null default 0,
    pay_amount bigint not null,

    -- 收货信息快照。地址簿后续被修改或删除，都不影响历史订单的收货信息
    receiver_name varchar(64) not null,
    receiver_phone varchar(20) not null,
    receiver_province varchar(32) not null,
    receiver_city varchar(32) not null,
    receiver_district varchar(32) not null,
    receiver_detail varchar(255) not null,

    -- 时间轴。支付截止时间用于超时关单——原实现没有这个字段，
    -- 未支付订单会永久停在「配送中」并且永久占用库存
    expire_at datetime not null,
    created_at datetime not null,
    paid_at datetime,
    shipped_at datetime,
    received_at datetime,
    closed_at datetime,
    finished_at datetime,

    remark varchar(255),
    cancel_reason varchar(255),

    index idx_order_user_status (user_id, status),
    -- 超时关单的扫描任务走这个索引：where status = 'CREATED' and expire_at < now()
    index idx_order_status_expire (status, expire_at)
);

create table if not exists shop_order_item (
    id bigint auto_increment primary key,
    order_id bigint not null,
    order_no varchar(32) not null,
    spu_id bigint not null,
    sku_id bigint not null,
    -- 类目也进快照：售后政策按类目判定（食品拆封不退、特殊商品不支持无理由），
    -- 而订单行不引用商品表，不存下来就无从判断
    category_id bigint,
    -- 商品快照：商品改名、改价、下架之后，历史订单必须还原下单当时的样子。
    -- 这是订单行不直接引用商品表的唯一理由
    spu_name varchar(255) not null,
    sku_spec_text varchar(255),
    sku_image varchar(512),
    unit_price bigint not null,
    quantity int not null,
    subtotal bigint not null,
    index idx_order_item_order (order_id),
    index idx_order_item_sku (sku_id)
);

-- 订单状态流水。用户问「我的订单为什么是这个状态」，答案在这里，不在客服记忆里
create table if not exists order_status_log (
    id bigint auto_increment primary key,
    order_id bigint not null,
    from_status varchar(24),
    to_status varchar(24) not null,
    -- USER / SYSTEM / ADMIN
    operator_type varchar(16) not null,
    operator_id varchar(32),
    remark varchar(255),
    created_at datetime not null,
    index idx_order_status_log_order (order_id, created_at)
);

-- ==================== 履约 ====================

-- 物流是独立实体而非订单上的几个字段：承运商与运单号有独立生命周期，
-- 轨迹是随时间增长的时间序列
create table if not exists order_delivery (
    id bigint auto_increment primary key,
    order_id bigint not null,
    order_no varchar(32) not null,
    carrier_code varchar(32) not null,
    carrier_name varchar(64) not null,
    tracking_no varchar(64) not null unique,
    -- CREATED / PICKED_UP / IN_TRANSIT / DELIVERING / SIGNED
    status varchar(24) not null,
    shipped_at datetime not null,
    signed_at datetime,
    constraint uk_delivery_order unique (order_id)
);

create table if not exists order_delivery_trace (
    id bigint auto_increment primary key,
    delivery_id bigint not null,
    happen_at datetime not null,
    status varchar(24) not null,
    description varchar(255) not null,
    location varchar(128),
    index idx_delivery_trace_delivery (delivery_id, happen_at)
);

-- ==================== 售后 ====================

create table if not exists after_sale (
    id bigint auto_increment primary key,
    after_sale_no varchar(32) not null unique,
    order_id bigint not null,
    order_no varchar(32) not null,
    order_item_id bigint not null,
    user_id varchar(32) not null,
    -- REFUND_ONLY 仅退款 / RETURN_REFUND 退货退款 / EXCHANGE 换货
    type varchar(24) not null,
    -- APPLIED / APPROVED / RETURNING / RECEIVED / REFUNDING / FINISHED / REJECTED / CANCELLED
    status varchar(24) not null,
    reason varchar(64) not null,
    description varchar(500),
    images varchar(1024),
    refund_amount bigint not null,
    applied_at datetime not null,
    audited_at datetime,
    finished_at datetime,
    audit_remark varchar(255),
    index idx_after_sale_order (order_id),
    index idx_after_sale_user_status (user_id, status)
);

create table if not exists after_sale_log (
    id bigint auto_increment primary key,
    after_sale_id bigint not null,
    from_status varchar(24),
    to_status varchar(24) not null,
    operator_type varchar(16) not null,
    operator_id varchar(32),
    remark varchar(255),
    created_at datetime not null,
    index idx_after_sale_log_sale (after_sale_id, created_at)
);

-- 售后政策。这条是「规则引擎」与「知识库」的交汇点：
--   - 规则引擎读这张表得出「能不能退」的确定性结论（涉及金额与时间窗，模型算错就是资损）
--   - doc_ref 指向知识库文档编号，「为什么」由检索给出并附带原文引用
-- 两条腿各司其职：规则给结论，检索给依据
create table if not exists after_sale_policy (
    id bigint auto_increment primary key,
    -- 为 NULL 表示全类目默认政策；否则只覆盖该一级类目
    category_id bigint,
    type varchar(24) not null,
    returnable tinyint not null default 1,
    -- 无理由退货天数
    return_days int not null default 7,
    -- 质量问题可退天数
    quality_days int not null default 15,
    -- 最高可退比例。拆封的食品类商品可能只能退部分
    max_refund_ratio decimal(3,2) not null default 1.00,
    requirements varchar(500),
    doc_ref varchar(32),
    index idx_after_sale_policy_category (category_id, type)
);
