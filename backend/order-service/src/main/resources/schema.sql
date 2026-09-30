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

    -- 买家留言
    remark varchar(255),
    -- 商家备注（管理端写的内部说明）。与 remark 分开存：覆盖买家留言会让那句
    -- 「请放门口」永久消失，而它往往是售后争议里唯一能证明买家说过什么的东西。
    --
    -- 注意：本文件全部是 create table if not exists，对**已经存在的库**不会加列。
    -- 新库自动带上这一列；老库需要手工执行一次
    --   alter table shop_order add column admin_remark varchar(255) null;
    admin_remark varchar(255),
    cancel_reason varchar(255),

    -- 本单核销的用户券 id。取消/超时关单时据此把券退还 —— 不记下来的话，
    -- 「券被核销了但订单没成交」在数据上无处可查，用户只能找客服对质。
    -- 老库需要手工执行一次（新库自动带上）
    --   alter table shop_order add column user_coupon_id bigint null;
    user_coupon_id bigint,

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
    -- 退货物流（仅退货退款/换货填写）：审核通过后由用户寄回时录入。
    -- 没有这几列时，「用户说寄了」在系统里没有任何凭证，而商家收货那一端
    -- 也就无从对照 —— 退货退款的链路会停在「待寄回」永远向前走不动。
    -- 老库需要手工执行一次（新库自动带上）
    --   alter table after_sale
    --     add column return_carrier varchar(32) null,
    --     add column return_tracking_no varchar(64) null,
    --     add column returned_at datetime null;
    return_carrier varchar(32),
    return_tracking_no varchar(64),
    returned_at datetime,
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

-- 客服工单。
-- 状态机是单向往前的：OPEN → PROCESSING → RESOLVED → CLOSED，
-- 唯一一条回边是 RESOLVED → PROCESSING（用户不满意，重开）。
-- CLOSED 是终态、**不可重开**：超时自动关闭会制造一批 CLOSED，若还能重开，
-- 用户半年后回来重开一条上下文早已散尽的工单，客服看到的只有一句"还是不行"。
-- 新问题走新工单，两条工单之间的联系是后续的事，状态机先保持单向。
create table if not exists support_ticket (
    id bigint auto_increment primary key,
    ticket_no varchar(32) not null,
    user_id varchar(32) not null,
    -- 可空：不是所有工单都关于某张订单。非空时必须是**该用户自己的**订单，
    -- 否则用户 A 的工单里挂着用户 B 的订单号，客服按着它去查，线索整个串掉
    order_id bigint,
    order_no varchar(32),
    -- ORDER / REFUND / PRODUCT / OTHER
    category varchar(16) not null,
    title varchar(128) not null,
    -- OPEN / PROCESSING / RESOLVED / CLOSED
    status varchar(16) not null,
    -- USER / ADMIN：最后一条消息是谁发的。列表页要回答"这条现在等谁"，
    -- 从消息表推是每行一次子查询；它只是在消息插入时同步的一列冗余
    last_reply_by varchar(16),
    created_at datetime not null,
    updated_at datetime not null,
    resolved_at datetime,
    closed_at datetime,
    close_reason varchar(255),
    unique key uk_support_ticket_no (ticket_no),
    index idx_support_ticket_user (user_id, updated_at),
    index idx_support_ticket_status (status, updated_at)
);

-- 工单消息。工单的上下文全在这张表里，工单本身只存"当前状态"。
create table if not exists support_ticket_message (
    id bigint auto_increment primary key,
    ticket_id bigint not null,
    -- USER / ADMIN / SYSTEM。SYSTEM 用于"超时自动关闭"这类没有人工的流转：
    -- 没有它，用户看到状态自己变了，而消息流里没有任何解释
    sender_type varchar(16) not null,
    sender_id varchar(32),
    content varchar(2000) not null,
    created_at datetime not null,
    index idx_support_ticket_msg (ticket_id, id)
);
