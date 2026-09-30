-- 商品库种子数据：营养健康领域的类目、品牌、商品、规格与参数。
--
-- 写法说明：`insert ... select ... from (派生表) where not exists (...)` ——
-- 一次插入多行且整体幂等。H2(MODE=MySQL) 与 MySQL 都认这个写法；
-- H2 的 `merge into ... key()` 是专有语法，换成本地 MySQL 时会在初始化阶段直接失败。
--
-- <b>守卫要对着表上真正管唯一性的那一列写，不能一律写 `id`。</b>
-- `id` 只是代理键；下面有五张表的唯一性由自然键承担：
-- brand.name、product_spu.spu_code、product_sku.sku_code、
-- product_sku_spec(sku_id, spec_id)、spu_attribute_value(spu_id, attribute_id)。
-- 只守 id 的后果不是「多插一行」而是**服务起不来**：某行被删掉又建回来时会拿到新的自增 id，
-- id 守卫于是放行，插进去撞上自然键的唯一索引，报出来的是
-- `Duplicate entry '9-5' for key 'uk_sku_spec'` —— 出现在应用启动阶段，
-- 错误信息里没有一个字提到种子脚本，只能顺着调用栈一路挖到这里。
--
-- 幂等是必须的：`spring.sql.init.mode=always` 让这个脚本**每次启动都跑一遍**，
-- 不幂等的写法会让商品每重启一次就翻一倍。

-- ==================== 类目 ====================
-- path 存祖级路径，查子树走前缀匹配。一级类目的 path 就是自己的 id
insert into category (id, parent_id, name, level, path, sort, status)
select * from (
    select 1 as id, 0 as parent_id, '营养保健' as name, 1 as level, '1' as path, 1 as sort, 1 as status
    union all select 2, 1, '维生素矿物质', 2, '1/2', 1, 1
    union all select 3, 1, '蛋白质氨基酸', 2, '1/3', 2, 1
    union all select 4, 1, '益生菌与肠道', 2, '1/4', 3, 1
    union all select 5, 1, '特殊医学用途', 2, '1/5', 4, 1
    union all select 6, 1, '功能性食品', 2, '1/6', 5, 1
    union all select 7, 0, '运动营养', 1, '7', 2, 1
    union all select 8, 7, '蛋白粉', 2, '7/8', 1, 1
    union all select 9, 7, '能量补充', 2, '7/9', 2, 1
    union all select 10, 0, '母婴营养', 1, '10', 3, 1
    union all select 11, 10, '孕期营养', 2, '10/11', 1, 1
    union all select 12, 10, '儿童营养', 2, '10/12', 2, 1
    union all select 13, 0, '中老年健康', 1, '13', 4, 1
    union all select 14, 13, '骨骼关节', 2, '13/14', 1, 1
    union all select 15, 13, '心脑血管', 2, '13/15', 2, 1
) as seed
where not exists (select 1 from category where id = seed.id);

-- ==================== 品牌 ====================
insert into brand (id, name, logo, description, status)
select * from (
    select 1 as id, '益生源' as name, 'https://picsum.photos/seed/brand1/120' as logo, '专注益生菌与肠道健康' as description, 1 as status
    union all select 2, '元气元素', 'https://picsum.photos/seed/brand2/120', '维生素与矿物质补充', 1
    union all select 3, '肌力方', 'https://picsum.photos/seed/brand3/120', '运动营养与蛋白质', 1
    union all select 4, '康倍适', 'https://picsum.photos/seed/brand4/120', '特殊医学用途配方食品', 1
    union all select 5, '深海鲜', 'https://picsum.photos/seed/brand5/120', '海洋来源营养', 1
    union all select 6, '本草纪', 'https://picsum.photos/seed/brand6/120', '中式草本与现代营养结合', 1
) as seed
-- brand 的唯一键是 name，不是 id
where not exists (select 1 from brand where name = seed.name);

-- ==================== 商品（SPU） ====================
-- 价格与库存在 SKU 上，这里只有商品概念与展示信息
insert into product_spu (id, spu_code, name, subtitle, category_id, brand_id, main_image, images, tags, detail_html, status, sales, rating_avg, review_count, created_at, updated_at)
select * from (
    select 1 as id, 'SPU001' as spu_code, '维生素 D3 软胶囊' as name, '每粒 400IU，助力钙吸收' as subtitle,
           2 as category_id, 2 as brand_id,
           'https://picsum.photos/seed/spu1/600' as main_image,
           'https://picsum.photos/seed/spu1a/800,https://picsum.photos/seed/spu1b/800,https://picsum.photos/seed/spu1c/800' as images,
           '热销,骨骼健康' as tags,
           '<h2>产品说明</h2><p>维生素 D3 有助于促进钙的吸收，维持骨骼健康。适用于日常日照不足、久坐办公的人群。</p><h2>服用建议</h2><p>随餐服用吸收更佳。</p>' as detail_html,
           1 as status, 1280 as sales, 4.80 as rating_avg, 216 as review_count, now() as created_at, now() as updated_at
    union all select 2, 'SPU002', '复合维生素矿物质片', '每日一片，覆盖 12 种维生素与 8 种矿物质', 2, 2,
           'https://picsum.photos/seed/spu2/600', 'https://picsum.photos/seed/spu2a/800', '综合补充',
           '<h2>产品说明</h2><p>针对成人日常营养缺口设计的复合配方。</p>', 1, 960, 4.60, 154, now(), now()
    union all select 3, 'SPU003', '乳清蛋白粉', '每份 24g 蛋白质，低脂低糖', 3, 3,
           'https://picsum.photos/seed/spu3/600', 'https://picsum.photos/seed/spu3a/800', '运动,增肌',
           '<h2>产品说明</h2><p>分离乳清蛋白，乳糖含量低。</p>', 1, 2100, 4.70, 388, now(), now()
    union all select 4, 'SPU004', '植物蛋白粉', '豌豆与糙米双蛋白，适合素食人群', 3, 3,
           'https://picsum.photos/seed/spu4/600', 'https://picsum.photos/seed/spu4a/800', '素食',
           '<h2>产品说明</h2><p>植物来源，不含乳制品。</p>', 1, 430, 4.40, 76, now(), now()
    union all select 5, 'SPU005', '益生菌粉', '每袋 100 亿活菌，独立包装', 4, 1,
           'https://picsum.photos/seed/spu5/600', 'https://picsum.photos/seed/spu5a/800', '肠道健康',
           '<h2>产品说明</h2><p>含多种乳杆菌与双歧杆菌。</p>', 1, 1560, 4.90, 302, now(), now()
    union all select 6, 'SPU006', '膳食纤维粉', '水溶性膳食纤维，无味易冲调', 4, 1,
           'https://picsum.photos/seed/spu6/600', 'https://picsum.photos/seed/spu6a/800', null,
           '<h2>产品说明</h2><p>可加入水、牛奶或饮品中。</p>', 1, 320, 4.30, 48, now(), now()
    union all select 7, 'SPU007', '鱼油软胶囊', '深海鱼油，富含 EPA 与 DHA', 6, 5,
           'https://picsum.photos/seed/spu7/600', 'https://picsum.photos/seed/spu7a/800', '心脑血管',
           '<h2>产品说明</h2><p>深海小型鱼提取，经分子蒸馏纯化。</p>', 1, 870, 4.50, 165, now(), now()
    union all select 8, 'SPU008', '辅酶 Q10 软胶囊', '每粒 100mg', 6, 5,
           'https://picsum.photos/seed/spu8/600', 'https://picsum.photos/seed/spu8a/800', null,
           '<h2>产品说明</h2><p>辅酶 Q10 是人体自身合成的物质。</p>', 1, 540, 4.60, 92, now(), now()
    union all select 9, 'SPU009', '维生素 K2 软胶囊', '每粒 90μg，与钙同补', 2, 6,
           'https://picsum.photos/seed/spu9/600', 'https://picsum.photos/seed/spu9a/800', '骨骼健康',
           '<h2>产品说明</h2><p>维生素 K2 参与骨钙素的活化。</p><h2>注意事项</h2><p>正在服用抗凝药物者，服用前请咨询医师或药师。</p>', 1, 260, 4.50, 41, now(), now()
    union all select 10, 'SPU010', '碳酸钙 D3 咀嚼片', '含钙 600mg，添加维生素 D3', 14, 2,
           'https://picsum.photos/seed/spu10/600', 'https://picsum.photos/seed/spu10a/800', null,
           '<h2>产品说明</h2><p>咀嚼片剂型，无需吞服。辅料中含乳糖。</p>', 1, 720, 4.40, 128, now(), now()
    union all select 11, 'SPU011', '柠檬酸钙胶囊', '不含乳糖，随餐与否均可', 14, 2,
           'https://picsum.photos/seed/spu11/600', 'https://picsum.photos/seed/spu11a/800', null,
           '<h2>产品说明</h2><p>柠檬酸钙对胃酸依赖小，空腹也可服用。</p>', 1, 390, 4.70, 63, now(), now()
    union all select 12, 'SPU012', '孕期复合营养包', '含叶酸、铁、钙、DHA', 11, 4,
           'https://picsum.photos/seed/spu12/600', 'https://picsum.photos/seed/spu12a/800', '孕期',
           '<h2>产品说明</h2><p>针对孕期营养需求设计。</p>', 1, 640, 4.80, 117, now(), now()
    union all select 13, 'SPU013', '全营养配方粉', '特殊医学用途，整蛋白型', 5, 4,
           'https://picsum.photos/seed/spu13/600', 'https://picsum.photos/seed/spu13a/800', null,
           '<h2>产品说明</h2><p>适用于进食受限人群的营养补充，使用前请咨询医师或临床营养师。</p>', 1, 180, 4.60, 29, now(), now()
    union all select 14, 'SPU014', '胶原蛋白肽粉', '小分子肽，易吸收', 3, 6,
           'https://picsum.photos/seed/spu14/600', 'https://picsum.photos/seed/spu14a/800', '美容',
           '<h2>产品说明</h2><p>鱼胶原蛋白肽，分子量小于 1000 道尔顿。</p>', 1, 810, 4.40, 143, now(), now()
    union all select 15, 'SPU015', '儿童多种维生素软糖', '3 岁以上适用，天然水果味', 12, 2,
           'https://picsum.photos/seed/spu15/600', 'https://picsum.photos/seed/spu15a/800', '儿童',
           '<h2>产品说明</h2><p>软糖剂型，儿童易接受。</p>', 1, 1180, 4.70, 246, now(), now()
) as seed
-- product_spu 的唯一键是 spu_code：运营改过商品编号之后，id 守卫会放行、spu_code 撞车
where not exists (select 1 from product_spu where spu_code = seed.spu_code);

-- ==================== 规格 ====================
-- 决定「买哪一个」。属性描述事实，规格决定选择
insert into product_spec (id, spu_id, name, sort)
select * from (
    select 1 as id, 1 as spu_id, '规格' as name, 1 as sort
    union all select 2, 1, '包装', 2
    union all select 3, 3, '口味', 1
    union all select 4, 3, '净含量', 2
    union all select 5, 5, '规格', 1
    union all select 6, 7, '规格', 1
    union all select 7, 10, '规格', 1
    union all select 8, 12, '规格', 1
    union all select 9, 15, '口味', 1
) as seed
where not exists (select 1 from product_spec where id = seed.id);

insert into product_spec_value (id, spec_id, spec_value, sort)
select * from (
    select 1 as id, 1 as spec_id, '400IU×90粒' as spec_value, 1 as sort
    union all select 2, 1, '400IU×180粒', 2
    union all select 3, 1, '1000IU×90粒', 3
    union all select 4, 2, '瓶装', 1
    union all select 5, 2, '礼盒装', 2
    union all select 6, 3, '香草味', 1
    union all select 7, 3, '巧克力味', 2
    union all select 8, 4, '1kg', 1
    union all select 9, 4, '2.27kg', 2
    union all select 10, 5, '30 袋', 1
    union all select 11, 5, '60 袋', 2
    union all select 12, 6, '100 粒', 1
    union all select 13, 6, '200 粒', 2
    union all select 14, 7, '60 片', 1
    union all select 15, 7, '120 片', 2
    union all select 16, 8, '30 包', 1
    union all select 17, 8, '90 包', 2
    union all select 18, 9, '草莓味', 1
    union all select 19, 9, '橙子味', 2
) as seed
where not exists (select 1 from product_spec_value where id = seed.id);

-- ==================== SKU ====================
-- 价格单位「分」。原始价格用于展示划线价
insert into product_sku (id, spu_id, sku_code, price, original_price, stock, image, status)
select * from (
    select 1 as id, 1 as spu_id, 'SKU001' as sku_code, 5900 as price, 7900 as original_price, 320 as stock, 'https://picsum.photos/seed/sku1/400' as image, 1 as status
    union all select 2, 1, 'SKU002', 9900, 12900, 180, 'https://picsum.photos/seed/sku2/400', 1
    union all select 3, 1, 'SKU003', 7900, 9900, 260, 'https://picsum.photos/seed/sku3/400', 1
    union all select 4, 2, 'SKU004', 12800, 15800, 210, 'https://picsum.photos/seed/sku4/400', 1
    union all select 5, 3, 'SKU005', 26800, 32800, 150, 'https://picsum.photos/seed/sku5/400', 1
    union all select 6, 3, 'SKU006', 26800, 32800, 130, 'https://picsum.photos/seed/sku6/400', 1
    union all select 7, 3, 'SKU007', 49800, 59800, 70, 'https://picsum.photos/seed/sku7/400', 1
    union all select 8, 4, 'SKU008', 22800, 27800, 90, 'https://picsum.photos/seed/sku8/400', 1
    union all select 9, 5, 'SKU009', 15800, 19800, 400, 'https://picsum.photos/seed/sku9/400', 1
    union all select 10, 5, 'SKU010', 27800, 33800, 220, 'https://picsum.photos/seed/sku10/400', 1
    union all select 11, 6, 'SKU011', 6900, 8900, 300, 'https://picsum.photos/seed/sku11/400', 1
    union all select 12, 7, 'SKU012', 16800, 20800, 260, 'https://picsum.photos/seed/sku12/400', 1
    union all select 13, 7, 'SKU013', 29800, 35800, 140, 'https://picsum.photos/seed/sku13/400', 1
    union all select 14, 8, 'SKU014', 19800, 24800, 180, 'https://picsum.photos/seed/sku14/400', 1
    union all select 15, 9, 'SKU015', 12800, 15800, 240, 'https://picsum.photos/seed/sku15/400', 1
    union all select 16, 10, 'SKU016', 6900, 8900, 350, 'https://picsum.photos/seed/sku16/400', 1
    union all select 17, 10, 'SKU017', 11800, 14800, 200, 'https://picsum.photos/seed/sku17/400', 1
    union all select 18, 11, 'SKU018', 8900, 10800, 280, 'https://picsum.photos/seed/sku18/400', 1
    union all select 19, 12, 'SKU019', 25800, 31800, 160, 'https://picsum.photos/seed/sku19/400', 1
    union all select 20, 12, 'SKU020', 69800, 82800, 80, 'https://picsum.photos/seed/sku20/400', 1
    union all select 21, 13, 'SKU021', 36800, 43800, 60, 'https://picsum.photos/seed/sku21/400', 1
    union all select 22, 14, 'SKU022', 18800, 22800, 190, 'https://picsum.photos/seed/sku22/400', 1
    union all select 23, 15, 'SKU023', 7900, 9900, 420, 'https://picsum.photos/seed/sku23/400', 1
    union all select 24, 15, 'SKU024', 7900, 9900, 380, 'https://picsum.photos/seed/sku24/400', 1
) as seed
-- product_sku 的唯一键是 sku_code。**这一条是最容易踩的**：规格组合重算会删掉旧 SKU、
-- 按新 id 建回来（这正是 `verify-sku-regen.mjs` 盯着的那条路径），
-- 建回来的行 id 变了、sku_code 没变 —— 于是 id 守卫放行，撞上 sku_code
where not exists (select 1 from product_sku where sku_code = seed.sku_code);

-- ==================== SKU ↔ 规格值 ====================
insert into product_sku_spec (id, sku_id, spec_id, spec_value_id)
select * from (
    select 1 as id, 1 as sku_id, 1 as spec_id, 1 as spec_value_id
    union all select 2, 1, 2, 4
    union all select 3, 2, 1, 2
    union all select 4, 2, 2, 4
    union all select 5, 3, 1, 3
    union all select 6, 3, 2, 5
    union all select 7, 5, 3, 6
    union all select 8, 5, 4, 8
    union all select 9, 6, 3, 7
    union all select 10, 6, 4, 8
    union all select 11, 7, 3, 6
    union all select 12, 7, 4, 9
    union all select 13, 9, 5, 10
    union all select 14, 10, 5, 11
    union all select 15, 12, 6, 12
    union all select 16, 13, 6, 13
    union all select 17, 16, 7, 14
    union all select 18, 17, 7, 15
    union all select 19, 19, 8, 16
    union all select 20, 20, 8, 17
    union all select 21, 23, 9, 18
    union all select 22, 24, 9, 19
) as seed
-- 这一条**已经真实挡住过一次启动**：某次重算把 sku 9/10 的规格行删了又建，
-- id 从 13/14 变成 97/98，于是这两行的 id 守卫全部放行、插进去撞上 uk_sku_spec(sku_id, spec_id)，
-- product-service 直接起不来。守卫换成它真正管唯一性的那两列之后才成立
where not exists (
    select 1 from product_sku_spec
    where sku_id = seed.sku_id and spec_id = seed.spec_id
);

-- ==================== 商品参数 ====================
-- 属性定义挂类目，取值挂商品
insert into product_attribute (id, category_id, name, input_type, unit, sort)
select * from (
    select 1 as id, 2 as category_id, '剂型' as name, 'SELECT' as input_type, null as unit, 1 as sort
    union all select 2, 2, '适用人群', 'MULTI_SELECT', null, 2
    union all select 3, 2, '每份含量', 'TEXT', null, 3
    union all select 4, 3, '蛋白质含量', 'TEXT', 'g/100g', 1
    union all select 5, 4, '活菌数', 'TEXT', 'CFU/袋', 1
    union all select 6, 4, '储存条件', 'SELECT', null, 2
    union all select 7, 14, '钙含量', 'TEXT', 'mg/片', 1
    union all select 8, 5, '适用场景', 'TEXT', null, 1
) as seed
where not exists (select 1 from product_attribute where id = seed.id);

insert into spu_attribute_value (id, spu_id, attribute_id, attr_value)
select * from (
    select 1 as id, 1 as spu_id, 1 as attribute_id, '软胶囊' as attr_value
    union all select 2, 1, 2, '成人,老年人'
    union all select 3, 1, 3, '400IU/粒'
    union all select 4, 2, 1, '片剂'
    union all select 5, 2, 2, '成人'
    union all select 6, 3, 4, '80'
    union all select 7, 4, 4, '72'
    union all select 8, 5, 5, '100亿'
    union all select 9, 5, 6, '阴凉干燥处'
    union all select 10, 9, 2, '成人'
    union all select 11, 10, 7, '600'
    union all select 12, 10, 2, '成人,老年人'
    union all select 13, 11, 7, '315'
    union all select 14, 11, 2, '成人,老年人,乳糖不耐受人群'
    union all select 15, 12, 2, '孕妇'
    union all select 16, 13, 8, '进食受限人群的营养补充'
    union all select 17, 15, 2, '儿童'
) as seed
-- 唯一键是 uk_spu_attribute(spu_id, attribute_id)：同一个商品的同一个参数只该有一行
where not exists (
    select 1 from spu_attribute_value
    where spu_id = seed.spu_id and attribute_id = seed.attribute_id
);
