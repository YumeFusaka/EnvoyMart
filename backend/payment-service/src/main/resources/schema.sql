-- 支付库建表脚本：支付单 / 退款单 / 回调流水。

-- order_id 上有唯一约束：一个订单只应有一张支付单。
-- 服务层已经做了「存在即复用」的幂等判断，但那只挡得住顺序调用——
-- 并发下两个请求会同时查不到、同时插入。**资金路径上的重复不能只靠应用层拦**。
-- 重复记录本身不报错，却会让读取侧的 selectOne 抛 TooManyResultsException：
-- 写入时无人察觉，读取时整个接口挂掉。
create table if not exists payment (
    id bigint auto_increment primary key,
    payment_no varchar(32) not null unique,
    order_id bigint not null,
    order_no varchar(32) not null,
    user_id varchar(32) not null,
    amount bigint not null,
    -- ALIPAY / WECHAT / MOCK
    channel varchar(24) not null,
    -- APP / WEB / QR
    pay_type varchar(24),
    -- PENDING / SUCCESS / FAILED / CLOSED
    status varchar(16) not null,
    -- 渠道流水号。与支付单号分开：前者由渠道生成，对账时以它为准
    transaction_no varchar(64),
    paid_at datetime,
    created_at datetime not null,
    updated_at datetime not null,
    constraint uk_payment_order unique (order_id),
    constraint uk_payment_transaction unique (transaction_no)
);

-- 退款单与支付单分离，因为一次支付可以有多次部分退款。
-- 原实现完全没有退款能力，订单取消流程里对已支付订单只能回一句「请走退款流程」，
-- 而那条流程并不存在。
create table if not exists refund (
    id bigint auto_increment primary key,
    refund_no varchar(32) not null unique,
    payment_id bigint not null,
    order_id bigint not null,
    -- 售后退款时关联售后单；超时未发货等场景的主动退款为空
    after_sale_id bigint,
    user_id varchar(32) not null,
    amount bigint not null,
    -- PENDING / SUCCESS / FAILED
    status varchar(16) not null,
    reason varchar(255),
    channel_refund_no varchar(64),
    created_at datetime not null,
    refunded_at datetime,
    index idx_refund_order (order_id),
    -- 一个售后单只对应一笔退款：重试要重发同一笔，不能变成第二笔。
    -- 应用层已经在支付单行锁内查重，这里是数据库那一层的最终防线
    -- （MySQL 的唯一索引允许多个 null，所以超时未发货的主动退款不受影响）
    unique key uk_refund_after_sale (after_sale_id)
);

-- 回调流水。**验签失败的记录也要落库**——被伪造的回调是有价值的排查线索，
-- 只记录成功日志等于把线索丢掉。
-- payload 原样保留：对账、纠纷、以及「渠道说回调了但订单没变」这类问题时，
-- 唯一能自证的就是原始报文。
create table if not exists payment_callback_log (
    id bigint auto_increment primary key,
    order_no varchar(32) not null,
    transaction_no varchar(64),
    status varchar(16),
    payload text,
    signature varchar(128),
    verified tinyint not null,
    created_at datetime not null,
    index idx_callback_log_order (order_no),
    index idx_callback_log_transaction (transaction_no)
);
