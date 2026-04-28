merge into sys_user key(id) values
('u1001', 'alice', '123456', 'Alice', 'customer', 'https://images.unsplash.com/photo-1494790108377-be9c29b29330?w=200', current_timestamp),
('u1002', 'bob', '123456', 'Bob', 'customer', 'https://images.unsplash.com/photo-1500648767791-00dcc994a43e?w=200', current_timestamp);

merge into product key(id) values
(1, 'Aurora Air Lite 真无线耳机', '学生党入门通勤款，佩戴轻盈', '数码', 'Aurora', '耳机,蓝牙,学生党,百元', 99.00, 120, 864, 'https://images.unsplash.com/photo-1505740420928-5e560c06d30e?w=600', '百元档高性价比，通勤听歌不压耳。', '耳机,蓝牙,百元,学生党,通勤', '13mm 动圈单元，蓝牙 5.3，续航 24 小时，适合通勤与线上会议。', current_timestamp),
(2, 'Nimbus Pro 降噪头戴耳机', '长续航降噪，适合宿舍与图书馆', '数码', 'Nimbus', '耳机,降噪,学习', 299.00, 48, 322, 'https://images.unsplash.com/photo-1518444065439-e933c06ce9cd?w=600', '兼顾沉浸听感和长时佩戴舒适度。', '耳机,头戴,降噪,学习', '支持主动降噪和游戏低延迟模式，续航 48 小时。', current_timestamp),
(3, 'Mori 森系保温杯', '简约轻量，适合办公室和校园', '家居', 'Mori', '杯子,保温,通勤', 79.00, 200, 512, 'https://images.unsplash.com/photo-1514228742587-6b1558fcf93a?w=600', '手感细腻，日常携带无压力。', '保温杯,通勤,礼物', '316 不锈钢内胆，锁温 12 小时，容量 420ml。', current_timestamp),
(4, 'Pixel Glow 便携补光灯', '直播自拍双用，小巧易收纳', '数码', 'PixelGlow', '补光灯,直播,拍照', 159.00, 76, 271, 'https://images.unsplash.com/photo-1516035069371-29a1b244cc32?w=600', '三挡色温可调，适合短视频拍摄。', '补光灯,直播,拍照,便携', '磁吸夹持设计，支持 Type-C 充电，显色指数高。', current_timestamp),
(5, 'Cloud Knit 记忆棉枕', '柔软回弹，改善午休与夜间支撑', '家居', 'CloudKnit', '枕头,睡眠,舒适', 129.00, 66, 403, 'https://images.unsplash.com/photo-1505693416388-ac5ce068fe85?w=600', '久坐和颈肩紧张人群的舒缓选择。', '枕头,记忆棉,睡眠', '慢回弹记忆棉内芯，透气枕套，可拆洗。', current_timestamp);
