-- 演示商品种子数据。
--
-- 写成「不存在才插入」而不是「先删后插」。
--
-- 先删后插确实是幂等的（重复启动不冲突），但它把<b>每次启动都当成初始化</b>：
-- 只要 id 在名单里，就无条件覆盖。用 MySQL 当持久库时，这条语句会在每次重启时
-- 把库存打回种子值——订单明明已经扣到 114，重启一次回到 120，和订单记录直接矛盾，
-- 继续卖就是超卖。演示数据只需要在<b>表为空</b>时出现一次，之后库存归订单和运营管。
--
-- 语法要在 H2(MODE=MySQL) 与 MySQL 上都能跑：
-- `insert ... select ... where not exists (...)` 两边都认；
-- H2 的 `merge into ... key()` 是专有语法，`insert ignore` 在 H2 上不保证，
-- `on duplicate key update` 同理。这条最保守。

insert into product (id, name, subtitle, category, brand, tags, price, stock, monthly_sales, image, sales_copy, semantic_keywords, description)
select 1, 'Aurora Air Lite 真无线耳机', '学生党入门通勤款，佩戴轻盈', '数码', 'Aurora', '耳机,蓝牙,学生党,百元', 99.00, 120, 864, 'https://images.unsplash.com/photo-1505740420928-5e560c06d30e?w=600', '百元档高性价比，通勤听歌不压耳。', '耳机,蓝牙,百元,学生党,通勤', '13mm 动圈单元，蓝牙 5.3，续航 24 小时，适合通勤与线上会议。'
where not exists (select 1 from product where id = 1);

insert into product (id, name, subtitle, category, brand, tags, price, stock, monthly_sales, image, sales_copy, semantic_keywords, description)
select 2, 'Nimbus Pro 降噪头戴耳机', '长续航降噪，适合宿舍与图书馆', '数码', 'Nimbus', '耳机,降噪,学习', 299.00, 48, 322, 'https://images.unsplash.com/photo-1518444065439-e933c06ce9cd?w=600', '兼顾沉浸听感和长时佩戴舒适度。', '耳机,头戴,降噪,学习', '支持主动降噪和游戏低延迟模式，续航 48 小时。'
where not exists (select 1 from product where id = 2);

insert into product (id, name, subtitle, category, brand, tags, price, stock, monthly_sales, image, sales_copy, semantic_keywords, description)
select 3, 'Mori 森系保温杯', '简约轻量，适合办公室和校园', '家居', 'Mori', '杯子,保温,通勤', 79.00, 200, 512, 'https://images.unsplash.com/photo-1544787219-7f47ccb76574?w=600', '手感细腻，日常携带无压力。', '保温杯,通勤,礼物', '316 不锈钢内胆，锁温 12 小时，容量 420ml。'
where not exists (select 1 from product where id = 3);
