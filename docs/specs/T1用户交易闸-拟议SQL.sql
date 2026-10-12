-- 新增独立用户交易闸；不修改或删除存量购物车、订单与 undo。
CREATE TABLE IF NOT EXISTS order_user_trade_guard (
    user_id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (user_id)
) ENGINE=InnoDB;

-- 迁移前先只读核对列不存在；不回填任何历史订单。
ALTER TABLE shop_order ADD COLUMN global_xid VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL;
