-- 支付表。
--
-- order_id 上有唯一约束：一个订单只应有一张支付单。
-- 服务层已经做了「存在即复用」的幂等判断，但那只挡得住顺序调用——
-- 并发下两个请求会同时查不到、同时插入。**资金路径上的重复不能只靠应用层拦**。
-- 重复记录本身不报错，却会让读取侧的 selectOne 抛 TooManyResultsException：
-- 写入时无人察觉，读取时整个接口挂掉。
CREATE TABLE IF NOT EXISTS payment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    order_no VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    amount DECIMAL(10, 2) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    transaction_no VARCHAR(128),
    paid_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_payment_order UNIQUE (order_id)
);
