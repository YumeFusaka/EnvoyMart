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
--
-- <b>与守卫配套的另一半：守卫写在自然键上时，代理键就不该写死。</b>
-- 这两件事是一起出事的：守卫按自然键放行，而写死的 id 属于上一版的行序，
-- 于是插进去撞 PRIMARY。id 有人引用（别的表拿它当外键）时才写死，那是数据的一部分；
-- 没人引用的 id 一律交给自增。

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
    -- 以下四类是为「多条件检索」补的：原来只有 15 个商品、覆盖面窄到举不出一个像样的
    -- 三条件场景（价格 + 正向 + 否定筛完只剩一条或一条不剩），演示时看不出筛选真的在起作用
    union all select 16, 1, '睡眠助眠', 2, '1/16', 6, 1
    union all select 17, 1, '护眼明目', 2, '1/17', 7, 1
    union all select 18, 1, '免疫提升', 2, '1/18', 8, 1
    union all select 19, 1, '体重管理', 2, '1/19', 9, 1
) as seed
where not exists (select 1 from category where id = seed.id);

-- ==================== 品牌 ====================
insert into brand (id, name, logo, description, status)
select * from (
    select 1 as id, '益生源' as name, '/img/photos/brand1-120.svg' as logo, '专注益生菌与肠道健康' as description, 1 as status
    union all select 2, '元气元素', '/img/photos/brand2-120.svg', '维生素与矿物质补充', 1
    union all select 3, '肌力方', '/img/photos/brand3-120.svg', '运动营养与蛋白质', 1
    union all select 4, '康倍适', '/img/photos/brand4-120.svg', '特殊医学用途配方食品', 1
    union all select 5, '深海鲜', '/img/photos/brand5-120.svg', '海洋来源营养', 1
    union all select 6, '本草纪', '/img/photos/brand6-120.svg', '中式草本与现代营养结合', 1
) as seed
-- brand 的唯一键是 name，不是 id
where not exists (select 1 from brand where name = seed.name);

-- ==================== 商品（SPU） ====================
-- 价格与库存在 SKU 上，这里只有商品概念与展示信息。
--
-- **sales / rating_avg / review_count 三个列不在这里写**，它们走建表默认值，
-- 真值分别来自销量台账（product_sales_ledger）与评价库，由链路算出来回写。
--
-- 种子原先把 1280 / 4.80 / 216 这类数字直接写死，后果是商品页写着「216 条评价」、
-- 点开评价区只有 1 条 —— 两个数字出自两套互不相干的口径，而并排放在同一个页面上
-- 也看不出该信谁。派生值一旦有了第二个来源，两个来源迟早会打架。
insert into product_spu (id, spu_code, name, subtitle, category_id, brand_id, main_image, images, tags, detail_html, status, created_at, updated_at)
select * from (
    select 1 as id, 'SPU001' as spu_code, '维生素 D3 软胶囊' as name, '每粒 400IU，助力钙吸收' as subtitle,
           2 as category_id, 2 as brand_id,
           '/img/photos/spu1-600.svg' as main_image,
           '/img/photos/spu1a-800.svg,/img/photos/spu1b-800.svg,/img/photos/spu1c-800.svg' as images,
           '热销,骨骼健康' as tags,
           '<h2>产品说明</h2><p>维生素 D3 有助于促进钙的吸收，维持骨骼健康。适用于日常日照不足、久坐办公的人群。</p><h2>服用建议</h2><p>随餐服用吸收更佳。</p>' as detail_html,
           1 as status, now() as created_at, now() as updated_at
    union all select 2, 'SPU002', '复合维生素矿物质片', '每日一片，覆盖 12 种维生素与 8 种矿物质', 2, 2,
           '/img/photos/spu2-600.svg', '/img/photos/spu2a-800.svg', '综合补充',
           '<h2>产品说明</h2><p>针对成人日常营养缺口设计的复合配方。</p>', 1, now(), now()
    union all select 3, 'SPU003', '乳清蛋白粉', '每份 24g 蛋白质，低脂低糖', 3, 3,
           '/img/photos/spu3-600.svg', '/img/photos/spu3a-800.svg', '运动,增肌',
           '<h2>产品说明</h2><p>分离乳清蛋白，乳糖含量低。</p>', 1, now(), now()
    union all select 4, 'SPU004', '植物蛋白粉', '豌豆与糙米双蛋白，适合素食人群', 3, 3,
           '/img/photos/spu4-600.svg', '/img/photos/spu4a-800.svg', '素食',
           '<h2>产品说明</h2><p>植物来源，不含乳制品。</p>', 1, now(), now()
    union all select 5, 'SPU005', '益生菌粉', '每袋 100 亿活菌，独立包装', 4, 1,
           '/img/photos/spu5-600.svg', '/img/photos/spu5a-800.svg', '肠道健康',
           '<h2>产品说明</h2><p>含多种乳杆菌与双歧杆菌。</p>', 1, now(), now()
    union all select 6, 'SPU006', '膳食纤维粉', '水溶性膳食纤维，无味易冲调', 4, 1,
           '/img/photos/spu6-600.svg', '/img/photos/spu6a-800.svg', null,
           '<h2>产品说明</h2><p>可加入水、牛奶或饮品中。</p>', 1, now(), now()
    union all select 7, 'SPU007', '鱼油软胶囊', '深海鱼油，富含 EPA 与 DHA', 6, 5,
           '/img/photos/spu7-600.svg', '/img/photos/spu7a-800.svg', '心脑血管',
           '<h2>产品说明</h2><p>深海小型鱼提取，经分子蒸馏纯化。</p>', 1, now(), now()
    union all select 8, 'SPU008', '辅酶 Q10 软胶囊', '每粒 100mg', 6, 5,
           '/img/photos/spu8-600.svg', '/img/photos/spu8a-800.svg', null,
           '<h2>产品说明</h2><p>辅酶 Q10 是人体自身合成的物质。</p>', 1, now(), now()
    union all select 9, 'SPU009', '维生素 K2 软胶囊', '每粒 90μg，与钙同补', 2, 6,
           '/img/photos/spu9-600.svg', '/img/photos/spu9a-800.svg', '骨骼健康',
           '<h2>产品说明</h2><p>维生素 K2 参与骨钙素的活化。</p><h2>注意事项</h2><p>正在服用抗凝药物者，服用前请咨询医师或药师。</p>', 1, now(), now()
    union all select 10, 'SPU010', '碳酸钙 D3 咀嚼片', '含钙 600mg，添加维生素 D3', 14, 2,
           '/img/photos/spu10-600.svg', '/img/photos/spu10a-800.svg', null,
           '<h2>产品说明</h2><p>咀嚼片剂型，无需吞服。辅料中含乳糖。</p>', 1, now(), now()
    union all select 11, 'SPU011', '柠檬酸钙胶囊', '不含乳糖，随餐与否均可', 14, 2,
           '/img/photos/spu11-600.svg', '/img/photos/spu11a-800.svg', null,
           '<h2>产品说明</h2><p>柠檬酸钙对胃酸依赖小，空腹也可服用。</p>', 1, now(), now()
    union all select 12, 'SPU012', '孕期复合营养包', '含叶酸、铁、钙、DHA', 11, 4,
           '/img/photos/spu12-600.svg', '/img/photos/spu12a-800.svg', '孕期',
           '<h2>产品说明</h2><p>针对孕期营养需求设计。</p>', 1, now(), now()
    union all select 13, 'SPU013', '全营养配方粉', '特殊医学用途，整蛋白型', 5, 4,
           '/img/photos/spu13-600.svg', '/img/photos/spu13a-800.svg', null,
           '<h2>产品说明</h2><p>适用于进食受限人群的营养补充，使用前请咨询医师或临床营养师。</p>', 1, now(), now()
    union all select 14, 'SPU014', '胶原蛋白肽粉', '小分子肽，易吸收', 3, 6,
           '/img/photos/spu14-600.svg', '/img/photos/spu14a-800.svg', '美容',
           '<h2>产品说明</h2><p>鱼胶原蛋白肽，分子量小于 1000 道尔顿。</p>', 1, now(), now()
    union all select 15, 'SPU015', '儿童多种维生素软糖', '3 岁以上适用，天然水果味', 12, 2,
           '/img/photos/spu15-600.svg', '/img/photos/spu15a-800.svg', '儿童',
           '<h2>产品说明</h2><p>软糖剂型，儿童易接受。</p>', 1, now(), now()
    union all select 16, 'SPU016', '褪黑素缓释片', '每片 3mg，缓释 8 小时', 16, 2,
           '/img/photos/spu16-600.svg', '/img/photos/spu16a-800.svg', '睡眠',
           '<h2>产品说明</h2><p>褪黑素是松果体分泌的激素，参与调节睡眠节律，适用于倒时差与作息紊乱的人群。</p><h2>注意事项</h2><p>不建议长期连续服用；服用后请勿驾驶。</p>', 1, now(), now()
    union all select 17, 'SPU017', 'γ-氨基丁酸软糖', '每粒 100mg，睡前半小时', 16, 6,
           '/img/photos/spu17-600.svg', '/img/photos/spu17a-800.svg', '睡眠,情绪',
           '<h2>产品说明</h2><p>γ-氨基丁酸是中枢神经系统中的抑制性神经递质。</p>', 1, now(), now()
    union all select 18, 'SPU018', '叶黄素酯软胶囊', '每粒叶黄素酯 10mg，搭配玉米黄质', 17, 5,
           '/img/photos/spu18-600.svg', '/img/photos/spu18a-800.svg', '护眼',
           '<h2>产品说明</h2><p>叶黄素酯是叶黄素的稳定前体，在体内转化为叶黄素后集中于视网膜黄斑区。</p>', 1, now(), now()
    union all select 19, 'SPU019', '越橘叶黄素片', '北欧越橘提取物与叶黄素复合配方', 17, 6,
           '/img/photos/spu19-600.svg', '/img/photos/spu19a-800.svg', '护眼',
           '<h2>产品说明</h2><p>越橘提取物富含花青素，适合长时间用眼的人群。</p>', 1, now(), now()
    union all select 20, 'SPU020', '维生素 C 咀嚼片', '每片 500mg，橙味', 18, 2,
           '/img/photos/spu20-600.svg', '/img/photos/spu20a-800.svg', '免疫',
           '<h2>产品说明</h2><p>维生素 C 参与胶原蛋白合成，咀嚼片剂型无需吞服。</p>', 1, now(), now()
    union all select 21, 'SPU021', '锌硒宝片', '锌 10mg 与硒 50μg 复合配方', 18, 2,
           '/img/photos/spu21-600.svg', '/img/photos/spu21a-800.svg', '免疫',
           '<h2>产品说明</h2><p>锌与硒是人体必需的微量元素。</p>', 1, now(), now()
    union all select 22, 'SPU022', '共轭亚油酸软胶囊', '每粒 1000mg CLA', 19, 3,
           '/img/photos/spu22-600.svg', '/img/photos/spu22a-800.svg', '体重管理,运动',
           '<h2>产品说明</h2><p>共轭亚油酸来源于红花籽油，通常与规律运动配合使用。</p>', 1, now(), now()
    union all select 23, 'SPU023', '白芸豆膳食纤维片', '餐前一片，含白芸豆提取物', 19, 6,
           '/img/photos/spu23-600.svg', '/img/photos/spu23a-800.svg', '体重管理',
           '<h2>产品说明</h2><p>白芸豆提取物含 α-淀粉酶抑制蛋白，配合膳食纤维使用。</p>', 1, now(), now()
    union all select 24, 'SPU024', '氨糖软骨素钙片', '氨糖 750mg、软骨素 250mg 与钙 200mg', 14, 4,
           '/img/photos/spu24-600.svg', '/img/photos/spu24a-800.svg', '关节,骨骼健康',
           '<h2>产品说明</h2><p>氨基葡萄糖与硫酸软骨素是关节软骨的组成成分。</p><h2>注意事项</h2><p>对甲壳类过敏者慎用。</p>', 1, now(), now()
    union all select 25, 'SPU025', '孕妇钙片', '柠檬酸钙 500mg 与维生素 D3 400IU，孕期哺乳期适用', 14, 4,
           '/img/photos/spu25-600.svg', '/img/photos/spu25a-800.svg', '孕期,骨骼健康',
           '<h2>产品说明</h2><p>柠檬酸钙对胃酸依赖小，孕期胃部不适时也可随餐或空腹服用。</p><h2>注意事项</h2><p>请按医师或营养师建议的剂量服用。</p>', 1, now(), now()
    union all select 26, 'SPU026', '儿童钙软糖', '3 岁以上适用，每粒含钙 100mg', 12, 2,
           '/img/photos/spu26-600.svg', '/img/photos/spu26a-800.svg', '儿童,骨骼健康',
           '<h2>产品说明</h2><p>软糖剂型，含维生素 D 帮助钙吸收。</p>', 1, now(), now()
    union all select 27, 'SPU027', '孕期 DHA 藻油软胶囊', '每粒 DHA 200mg，藻油来源', 11, 4,
           '/img/photos/spu27-600.svg', '/img/photos/spu27a-800.svg', '孕期',
           '<h2>产品说明</h2><p>DHA 藻油来源，适合孕期与哺乳期补充。</p>', 1, now(), now()
) as seed
-- product_spu 的唯一键是 spu_code：运营改过商品编号之后，id 守卫会放行、spu_code 撞车
where not exists (select 1 from product_spu where spu_code = seed.spu_code);

-- ==================== 销量台账 ====================
-- 演示用的历史销量。它不是「给商品写一个好看的数字」，而是一批**已经发生过、
-- 但订单在另一个库里查不到的成交**——order_id 用负数，与真实订单的自增正数
-- 天然隔开，一眼能认出这几行是种子而不是真单。
--
-- 写成台账而不是直接写 product_spu.sales，是为了让那个数字有来源、能对账：
-- 「支付成功累计销量」走的也是同一条路（先落一行台账、再累加 sales），
-- 所以演示时新增一笔支付，销量是真的会动，而不是在看一个写死的常量。
insert into product_sales_ledger (order_item_id, order_id, spu_id, quantity, created_at)
select * from (
    select -1 as order_item_id, -1 as order_id, 1 as spu_id, 1280 as quantity, now() as created_at
    union all select -2, -2, 2, 960, now()
    union all select -3, -3, 3, 2100, now()
    union all select -4, -4, 4, 430, now()
    union all select -5, -5, 5, 1560, now()
    union all select -6, -6, 6, 320, now()
    union all select -7, -7, 7, 870, now()
    union all select -8, -8, 8, 540, now()
    union all select -9, -9, 9, 260, now()
    union all select -10, -10, 10, 720, now()
    union all select -11, -11, 11, 390, now()
    union all select -12, -12, 12, 640, now()
    union all select -13, -13, 13, 180, now()
    union all select -14, -14, 14, 810, now()
    union all select -15, -15, 15, 1180, now()
    union all select -16, -16, 16, 940, now()
    union all select -17, -17, 17, 460, now()
    union all select -18, -18, 18, 720, now()
    union all select -19, -19, 19, 530, now()
    union all select -20, -20, 20, 2340, now()
    union all select -21, -21, 21, 680, now()
    union all select -22, -22, 22, 410, now()
    union all select -23, -23, 23, 590, now()
    union all select -24, -24, 24, 860, now()
    union all select -25, -25, 25, 1320, now()
    union all select -26, -26, 26, 1480, now()
    union all select -27, -27, 27, 640, now()
) as seed
where not exists (select 1 from product_sales_ledger where order_item_id = seed.order_item_id);

-- sales 是台账的汇总，每次启动重算一次。
-- 它与「支付后累加」这条路径算出来的结果一致，所以不会覆盖真实销量 ——
-- 它把的是另一种情况：冗余被谁改错了之后，能从权威来源自愈。
update product_spu
set sales = coalesce((select sum(l.quantity) from product_sales_ledger l where l.spu_id = product_spu.id), 0);

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
    select 1 as id, 1 as spu_id, 'SKU001' as sku_code, 5900 as price, 7900 as original_price, 320 as stock, '/img/photos/sku1-400.svg' as image, 1 as status
    union all select 2, 1, 'SKU002', 9900, 12900, 180, '/img/photos/sku2-400.svg', 1
    union all select 3, 1, 'SKU003', 7900, 9900, 260, '/img/photos/sku3-400.svg', 1
    union all select 4, 2, 'SKU004', 12800, 15800, 210, '/img/photos/sku4-400.svg', 1
    union all select 5, 3, 'SKU005', 26800, 32800, 150, '/img/photos/sku5-400.svg', 1
    union all select 6, 3, 'SKU006', 26800, 32800, 130, '/img/photos/sku6-400.svg', 1
    union all select 7, 3, 'SKU007', 49800, 59800, 70, '/img/photos/sku7-400.svg', 1
    union all select 8, 4, 'SKU008', 22800, 27800, 90, '/img/photos/sku8-400.svg', 1
    union all select 9, 5, 'SKU009', 15800, 19800, 400, '/img/photos/sku9-400.svg', 1
    union all select 10, 5, 'SKU010', 27800, 33800, 220, '/img/photos/sku10-400.svg', 1
    union all select 11, 6, 'SKU011', 6900, 8900, 300, '/img/photos/sku11-400.svg', 1
    union all select 12, 7, 'SKU012', 16800, 20800, 260, '/img/photos/sku12-400.svg', 1
    union all select 13, 7, 'SKU013', 29800, 35800, 140, '/img/photos/sku13-400.svg', 1
    union all select 14, 8, 'SKU014', 19800, 24800, 180, '/img/photos/sku14-400.svg', 1
    union all select 15, 9, 'SKU015', 12800, 15800, 240, '/img/photos/sku15-400.svg', 1
    union all select 16, 10, 'SKU016', 6900, 8900, 350, '/img/photos/sku16-400.svg', 1
    union all select 17, 10, 'SKU017', 11800, 14800, 200, '/img/photos/sku17-400.svg', 1
    union all select 18, 11, 'SKU018', 8900, 10800, 280, '/img/photos/sku18-400.svg', 1
    union all select 19, 12, 'SKU019', 25800, 31800, 160, '/img/photos/sku19-400.svg', 1
    union all select 20, 12, 'SKU020', 69800, 82800, 80, '/img/photos/sku20-400.svg', 1
    union all select 21, 13, 'SKU021', 36800, 43800, 60, '/img/photos/sku21-400.svg', 1
    union all select 22, 14, 'SKU022', 18800, 22800, 190, '/img/photos/sku22-400.svg', 1
    union all select 23, 15, 'SKU023', 7900, 9900, 420, '/img/photos/sku23-400.svg', 1
    union all select 24, 15, 'SKU024', 7900, 9900, 380, '/img/photos/sku24-400.svg', 1
    union all select 25, 16, 'SKU025', 9900, 12900, 260, '/img/photos/sku25-400.svg', 1
    union all select 26, 17, 'SKU026', 11800, 14800, 300, '/img/photos/sku26-400.svg', 1
    union all select 27, 18, 'SKU027', 15800, 19800, 220, '/img/photos/sku27-400.svg', 1
    union all select 28, 19, 'SKU028', 13800, 16800, 240, '/img/photos/sku28-400.svg', 1
    union all select 29, 20, 'SKU029', 4900, 6900, 500, '/img/photos/sku29-400.svg', 1
    union all select 30, 21, 'SKU030', 8900, 10800, 280, '/img/photos/sku30-400.svg', 1
    union all select 31, 22, 'SKU031', 19800, 24800, 170, '/img/photos/sku31-400.svg', 1
    union all select 32, 23, 'SKU032', 12800, 15800, 210, '/img/photos/sku32-400.svg', 1
    union all select 33, 24, 'SKU033', 16800, 20800, 190, '/img/photos/sku33-400.svg', 1
    union all select 34, 25, 'SKU034', 12900, 15900, 230, '/img/photos/sku34-400.svg', 1
    union all select 35, 26, 'SKU035', 8900, 10900, 340, '/img/photos/sku35-400.svg', 1
    union all select 36, 27, 'SKU036', 21800, 26800, 150, '/img/photos/sku36-400.svg', 1
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
-- 属性定义挂类目，取值挂商品。
--
-- **「适用人群」在每个类目下各有一行**（id 2 / 9 / 11 / 12 / 15 / 17 / 19 / 21），不是重复：
-- 属性定义是**按类目**维护的（管理端按类目列出可填参数，见 CatalogAdminServiceImpl），
-- 而「适用人群」这类横跨全部品类的参数，本来就该在每棵子树里各挂一次。
-- 检索侧只看名字与取值、不看它挂在哪个类目，所以筛选一行代码都不用改。
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
    union all select 9, 14, '适用人群', 'MULTI_SELECT', null, 2
    -- 「是否含乳糖」是**结构化**回答「乳糖不耐受能不能吃」的那一个。
    -- 靠文本排除答不了这个问题：写着「不含乳糖」的商品里也含「乳糖」两个字，
    -- 按字面排除会把它一起排掉——**恰好排掉的是唯一能吃的那个**
    union all select 10, 14, '是否含乳糖', 'SELECT', null, 3
    union all select 11, 11, '适用人群', 'MULTI_SELECT', null, 1
    union all select 12, 12, '适用人群', 'MULTI_SELECT', null, 1
    union all select 13, 12, '剂型', 'SELECT', null, 2
    union all select 14, 16, '剂型', 'SELECT', null, 1
    union all select 15, 16, '适用人群', 'MULTI_SELECT', null, 2
    union all select 16, 17, '剂型', 'SELECT', null, 1
    union all select 17, 17, '适用人群', 'MULTI_SELECT', null, 2
    union all select 18, 18, '剂型', 'SELECT', null, 1
    union all select 19, 18, '适用人群', 'MULTI_SELECT', null, 2
    union all select 20, 19, '剂型', 'SELECT', null, 1
    union all select 21, 19, '适用人群', 'MULTI_SELECT', null, 2
    union all select 22, 14, '剂型', 'SELECT', null, 4
    union all select 23, 11, '剂型', 'SELECT', null, 2
) as seed
where not exists (select 1 from product_attribute where id = seed.id);

-- SPU 的参数原先**一律挂在维生素类目（id=2）的定义上**，因为那时只有那一套定义。
-- 现在母婴、骨骼这些类目各自有了自己的「适用人群」，把这几行迁到本类目的定义上：
-- 管理端是按类目列出可填参数的，挂在别人类目下会变成一条「编辑器认不出来」的取值。
--
-- **位置必须在下面那条 insert 之前**，而且下面的 insert 要直接写新的 attribute_id。
-- 反过来（先 insert 后迁移）在第二次启动时会炸：迁移走的那一行不再存在，
-- insert 的 (spu_id, attribute_id) 守卫于是放行、把它插回来，接着迁移又把它改成
-- 那个已经被占用的 id —— 撞唯一键，**服务起不来**。文件开头那条「守卫要对着真正管唯一性的列写」
-- 讲的是同一类事故。
update spu_attribute_value set attribute_id = 9 where spu_id = 10 and attribute_id = 2;
update spu_attribute_value set attribute_id = 9 where spu_id = 11 and attribute_id = 2;
update spu_attribute_value set attribute_id = 11 where spu_id = 12 and attribute_id = 2;
update spu_attribute_value set attribute_id = 12 where spu_id = 15 and attribute_id = 2;

-- 上面几张表都把 id 写死在种子行里（它们的 id 被别的表引用着，是数据的一部分），
-- **只有这一张不写**：它的 id 没有任何地方引用，纯粹是代理键，而种子行会随品类扩充
-- 整体前移/后移。写死 id 的后果不是多插一行，是**服务起不来**——老库上这些行的 id
-- 还是上一版的行序，守卫挡的是自然键（放行），而它要占的那个 id 已经被别人占了：
-- `Duplicate entry '28' for key 'spu_attribute_value.PRIMARY'`，报错里那个 28
-- 在种子文件里只是「第 28 行」，照着它排查会一头雾水。交给自增，行序怎么变都不会撞。
insert into spu_attribute_value (spu_id, attribute_id, attr_value)
select * from (
    select 1 as spu_id, 1 as attribute_id, '软胶囊' as attr_value
    union all select 1, 2, '成人,老年人'
    union all select 1, 3, '400IU/粒'
    union all select 2, 1, '片剂'
    union all select 2, 2, '成人'
    union all select 3, 4, '80'
    union all select 4, 4, '72'
    union all select 5, 5, '100亿'
    union all select 5, 6, '阴凉干燥处'
    union all select 9, 2, '成人'
    union all select 10, 7, '600'
    union all select 10, 9, '成人,老年人'
    union all select 11, 7, '315'
    union all select 11, 9, '成人,老年人,乳糖不耐受人群'
    union all select 12, 11, '孕妇'
    union all select 13, 8, '进食受限人群的营养补充'
    union all select 15, 12, '儿童'
    union all select 16, 14, '片剂'
    union all select 16, 15, '成人'
    union all select 17, 14, '软糖'
    union all select 17, 15, '成人'
    union all select 18, 16, '软胶囊'
    union all select 18, 17, '成人,老年人'
    union all select 19, 16, '片剂'
    union all select 19, 17, '成人,老年人'
    union all select 20, 18, '片剂'
    union all select 20, 19, '成人,儿童'
    union all select 21, 18, '片剂'
    union all select 21, 19, '成人'
    union all select 22, 20, '软胶囊'
    union all select 22, 21, '成人'
    union all select 23, 20, '片剂'
    union all select 23, 21, '成人'
    union all select 24, 9, '成人,老年人'
    union all select 24, 10, '不含'
    union all select 24, 22, '片剂'
    union all select 25, 9, '孕妇'
    union all select 25, 10, '不含'
    union all select 25, 22, '片剂'
    union all select 25, 7, '500'
    union all select 26, 12, '儿童'
    union all select 26, 13, '软糖'
    union all select 27, 11, '孕妇'
    union all select 27, 23, '软胶囊'
    union all select 10, 10, '含'
    union all select 10, 22, '片剂'
    union all select 11, 10, '不含'
    union all select 11, 22, '胶囊'
    union all select 15, 13, '软糖'
    union all select 9, 1, '软胶囊'
) as seed
-- 唯一键是 uk_spu_attribute(spu_id, attribute_id)：同一个商品的同一个参数只该有一行
where not exists (
    select 1 from spu_attribute_value
    where spu_id = seed.spu_id and attribute_id = seed.attribute_id
);

-- ==================== 配图修复（picsum 外链 → 本地生成图） ====================
-- 商品/品牌/SKU 的图原先指向 picsum.photos 的随机照片（卖保健品的货架上摆着
-- 风景和猫），现改为 frontend/public/img/photos/ 下由
-- `frontend/scripts/gen-product-images.mjs` 生成的本地 SVG，不依赖外网。
-- 文件名沿用原 URL 里的 seed-尺寸：seed/spu1/600 → spu1-600.svg。
--
-- 上面的种子都是 insert-only（唯一键守卫不会回头改已有行），所以老库要这几行
-- UPDATE 把库里还留着的旧 URL 换掉。id 与 seed 编号一致是种子文件自身的写法
-- （`select 1 as id` 配 `seed/spu1`）；守卫用 like 而不是全表覆盖，
-- 运营在后台改过图的商品不会被误伤。
update brand set logo = concat('/img/photos/brand', id, '-120.svg')
where logo like '%picsum.photos%';

update product_spu set main_image = concat('/img/photos/spu', id, '-600.svg')
where main_image like '%picsum.photos%';

update product_spu
set images = case
    when images like '%,%,%'
        then concat('/img/photos/spu', id, 'a-800.svg,/img/photos/spu', id, 'b-800.svg,/img/photos/spu', id, 'c-800.svg')
    else concat('/img/photos/spu', id, 'a-800.svg')
end
where images like '%picsum.photos%';

update product_sku set image = concat('/img/photos/sku', id, '-400.svg')
where image like '%picsum.photos%';
