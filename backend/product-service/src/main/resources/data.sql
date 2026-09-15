-- 演示商品种子数据。
--
-- 用「先删后插」而不是 H2 的 `merge into ... key(...)`：后者是 H2 专有语法，MySQL 不认识，
-- 换成本地 MySQL 时会在初始化阶段直接失败（表建好了但数据插不进去）。
-- 先删后插是标准 SQL，H2 与 MySQL 都能跑，且天然幂等——重复启动不会主键冲突。
delete from product where id in (1, 2, 3);
insert into product (id, name, subtitle, category, brand, tags, price, stock, monthly_sales, image, sales_copy, semantic_keywords, description) values
(1, 'Aurora Air Lite 真无线耳机', '学生党入门通勤款，佩戴轻盈', '数码', 'Aurora', '耳机,蓝牙,学生党,百元', 99.00, 120, 864, 'https://images.unsplash.com/photo-1505740420928-5e560c06d30e?w=600', '百元档高性价比，通勤听歌不压耳。', '耳机,蓝牙,百元,学生党,通勤', '13mm 动圈单元，蓝牙 5.3，续航 24 小时，适合通勤与线上会议。'),
(2, 'Nimbus Pro 降噪头戴耳机', '长续航降噪，适合宿舍与图书馆', '数码', 'Nimbus', '耳机,降噪,学习', 299.00, 48, 322, 'https://images.unsplash.com/photo-1518444065439-e933c06ce9cd?w=600', '兼顾沉浸听感和长时佩戴舒适度。', '耳机,头戴,降噪,学习', '支持主动降噪和游戏低延迟模式，续航 48 小时。'),
(3, 'Mori 森系保温杯', '简约轻量，适合办公室和校园', '家居', 'Mori', '杯子,保温,通勤', 79.00, 200, 512, 'https://images.unsplash.com/photo-1544787219-7f47ccb76574?w=600', '手感细腻，日常携带无压力。', '保温杯,通勤,礼物', '316 不锈钢内胆，锁温 12 小时，容量 420ml。');
