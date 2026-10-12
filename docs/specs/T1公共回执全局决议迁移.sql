-- 本迁移只新增可空事务证据列，不修改首次响应JSON或既有业务行。
ALTER TABLE envoymart_order.order_operation_receipt
    ADD COLUMN global_xid VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER parameters_hash,
    ADD COLUMN decision_mark VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER global_xid;

ALTER TABLE envoymart_promotion.promotion_operation_receipt
    ADD COLUMN global_xid VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER parameters_hash,
    ADD COLUMN decision_mark VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER global_xid;

-- 回执39的事务来源补充必须在主代理核对用户、操作号及可信undo后另行执行。
