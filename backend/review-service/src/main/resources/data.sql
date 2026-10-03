-- 演示用评价种子。
--
-- 它不是「给商品凑一个好看的评分」，而是一批**看起来像真的发生过的**评价记录：
-- 星级有高有低（所以商品的均分不是清一色的 5.0）、时间有先后、有匿名有实名、
-- 有带图有商家回复。演示时点开商品详情，评价区、星级筛选、分页、均分与评论数
-- 才都是活的；否则每个商品都是「暂无评价」，那条聚合回写链路也就无从展示。
--
-- **order_id / order_item_id 用负数**，与真实订单的自增正数天然隔开：
-- 这几行在订单库里查不到，一眼能认出是种子而不是真单。同时也让
-- `uk_review_order_item_user` 这个唯一约束在种子与真实评价之间不会撞车。
--
-- 守卫用 order_item_id（它对我们来说是主键级唯一），而不是 spu_code 那类业务编号：
-- 重启时（H2 内存库）重复执行是常态，MySQL 下也会跨重启保留，两边都得幂等。
--
-- useful_count 是直接写的，没有配套的 review_useful 行：它代表的是**别人**投过的票，
-- 而那些人不在这个库里。一人一票的约束只作用于走接口投出来的票，种子不参与。
--
-- **user_id 这里写的是用户名（alice / zhangwei …），而接口写入的是 JWT subject（u1001）**：
-- 种子代表"别的买家"，本来就不需要对应真实账号。但这个名字上的分叉踩过一次坑——
-- 验收脚本曾拿种子行的 user_id 当当前用户去造夹具，夹具于是挂到了另一个用户名下，
-- 连带把"每日 5 条上限"的断言顶红，顺着那条红线才发现台账少算销量（见 13d）。
-- **读种子行取当前用户身份之前，先确认你要的是"买家名"还是"登录主体"。**

insert into review (spu_id, sku_id, order_id, order_item_id, user_id, rating, content,
                    is_anonymous, status, useful_count, created_at)
select * from (
    -- 维生素 D3 软胶囊：主力商品，评价给足九条，均分落在 4.2 而不是满分
    select 1 as spu_id, 1 as sku_id, -1 as order_id, -1 as order_item_id, 'alice' as user_id, 5 as rating,
           '吃了一个月，复查血清 25(OH)D 从 18 升到 32 ng/mL，医生说达标了。随餐服用没有肠胃不适。' as content,
           0 as is_anonymous, 'PUBLISHED' as status, 3 as useful_count, '2026-09-21 20:15:00' as created_at
    union all select 1, 1, -2, -2, 'zhangwei', 5, '买了 90 粒装的，比药店划算不少。胶囊很小，好吞。', 0, 'PUBLISHED', 5, '2026-09-18 10:42:00'
    union all select 1, 2, -3, -3, 'lisi', 4, '整体满意，就是瓶口那层密封膜有点难撕。', 0, 'PUBLISHED', 1, '2026-09-15 19:03:00'
    union all select 1, 3, -4, -4, 'wangfang', 5, '给家里老人买的，配合钙片一起吃，这个冬天基本没再喊腿抽筋。', 0, 'PUBLISHED', 7, '2026-09-11 08:26:00'
    union all select 1, 2, -5, -5, 'chenjie', 3, '含量是够的，但颗粒比我之前买的偏大一点，吞咽有点费劲。', 0, 'PUBLISHED', 2, '2026-09-08 21:50:00'
    union all select 1, 1, -6, -6, 'sunqi', 2, '物流太慢了，等了六天才到。产品本身没问题，这一分扣在配送上。', 0, 'PUBLISHED', 0, '2026-09-03 14:12:00'
    union all select 1, 3, -7, -7, 'zhouyu', 5, '回购第三次了，一直吃这个牌子。', 0, 'PUBLISHED', 1, '2026-08-29 11:35:00'
    union all select 1, 1, -8, -8, 'heyan', 4, '含量标注清楚，每天一粒不会超量，这点比很多牌子做得好。', 1, 'PUBLISHED', 0, '2026-08-25 17:44:00'
    -- 下面这条（order_item_id = -9）带着商家回复，列数与本批不同，单独放在文件后面

    -- 复合维生素矿物质片
    union all select 2, 4, -20, -10, 'huangbo', 5, '每天一片，不用记着吃好几种，省事。', 0, 'PUBLISHED', 2, '2026-09-19 15:07:00'
    union all select 2, 4, -21, -11, 'xuting', 4, '片剂有点大，掰开吃也行，不影响效果。', 0, 'PUBLISHED', 0, '2026-09-13 12:31:00'
    union all select 2, 4, -22, -12, 'liuqiang', 3, '吃了两周没什么明显感觉，可能要长期吃才看得出来。', 0, 'PUBLISHED', 1, '2026-09-05 18:59:00'

    -- 乳清蛋白粉
    union all select 3, 5, -30, -13, 'zhangwei', 5, '溶解度很好，摇一摇就化开了，不会结块。', 0, 'PUBLISHED', 3, '2026-09-22 07:48:00'
    union all select 3, 6, -31, -14, 'lisi', 5, '香草味不齁，练完半小时喝刚好。', 0, 'PUBLISHED', 1, '2026-09-17 20:05:00'
    union all select 3, 7, -32, -15, 'sunqi', 4, '性价比不错，就是大桶装开封后得尽快喝完。', 0, 'PUBLISHED', 2, '2026-09-10 16:22:00'
    union all select 3, 5, -33, -16, 'alice', 5, '乳糖不耐受喝这个也没胀气，分离乳清确实不一样。', 0, 'PUBLISHED', 6, '2026-09-06 21:13:00'
    union all select 3, 6, -34, -17, 'chenjie', 2, '口味偏甜了，喝到后面有点腻。', 0, 'PUBLISHED', 3, '2026-08-31 13:40:00'

    -- 益生菌粉
    union all select 5, 9, -50, -18, 'wangfang', 5, '吃了两周腹胀明显改善，独立包装出差带着很方便。', 0, 'PUBLISHED', 8, '2026-09-20 22:02:00'
    union all select 5, 9, -51, -19, 'zhouyu', 5, '温水冲服，不会把活菌烫死，说明书写得清楚。', 0, 'PUBLISHED', 2, '2026-09-14 09:55:00'
    union all select 5, 10, -52, -20, 'huangbo', 4, '效果是有的，就是价格偏高，长期吃有点肉疼。', 0, 'PUBLISHED', 1, '2026-09-07 19:26:00'
    union all select 5, 9, -53, -21, 'yanglin', 3, '对我没什么用，可能肠道情况因人而异。', 1, 'PUBLISHED', 0, '2026-08-28 10:11:00'

    -- 鱼油软胶囊
    union all select 7, 12, -70, -22, 'heyan', 5, '没有腥味，打嗝也不返味，这点很关键。', 0, 'PUBLISHED', 9, '2026-09-23 18:37:00'
    union all select 7, 13, -71, -23, 'xuting', 5, '给父母买的，这回复查血脂指标有下降。', 0, 'PUBLISHED', 4, '2026-09-16 11:29:00'
    union all select 7, 12, -72, -24, 'sunqi', 4, '颗粒偏大，一次要吞两粒。', 0, 'PUBLISHED', 0, '2026-09-02 15:48:00'

    -- 维生素 K2 软胶囊
    union all select 9, 15, -90, -25, 'alice', 5, '和 D3 一起补的。我在吃抗凝药，客服提醒先问医生，很负责。', 0, 'PUBLISHED', 5, '2026-09-24 20:41:00'
    union all select 9, 15, -91, -26, 'zhangwei', 4, '配合钙片一起吃，自己没什么特别感觉，但体检指标是好的。', 0, 'PUBLISHED', 1, '2026-09-09 08:14:00'

    -- 碳酸钙 D3 咀嚼片
    union all select 10, 16, -100, -27, 'lisi', 5, '咀嚼片不用吞，家里老人很喜欢这个剂型。', 0, 'PUBLISHED', 3, '2026-09-25 17:33:00'
    union all select 10, 16, -101, -28, 'chenjie', 4, '辅料里有乳糖，我乳糖不耐受，后来换成柠檬酸钙那款了。', 0, 'PUBLISHED', 7, '2026-09-12 13:06:00'
    union all select 10, 17, -102, -29, 'wangfang', 5, '味道像奶片，小孩也愿意吃。', 0, 'PUBLISHED', 2, '2026-08-27 19:52:00'

    -- 孕期复合营养包
    union all select 12, 19, -120, -30, 'zhouyu', 5, '产检时医生看了成分表说可以，叶酸含量合适。', 0, 'PUBLISHED', 10, '2026-09-26 10:08:00'
    union all select 12, 19, -121, -31, 'huangbo', 4, '分装很细，一天一包不会漏也不会忘。', 0, 'PUBLISHED', 1, '2026-09-04 14:37:00'
    union all select 12, 20, -122, -32, 'heyan', 5, '孕期胃口不好，这个冲水喝还能接受。', 0, 'PUBLISHED', 3, '2026-08-26 21:20:00'

    -- 儿童多种维生素软糖
    union all select 15, 23, -150, -33, 'yanglin', 5, '孩子当糖吃，每天主动要，不用追着喂。', 0, 'PUBLISHED', 4, '2026-09-27 18:25:00'
    union all select 15, 23, -151, -34, 'xuting', 4, '味道是真好，但得放高一点，不然孩子会偷吃。', 0, 'PUBLISHED', 6, '2026-09-01 09:44:00'
    union all select 15, 24, -152, -35, 'sunqi', 5, '比药片好喂多了，省心。', 0, 'PUBLISHED', 0, '2026-08-24 16:03:00'
) as seed
where not exists (select 1 from review where order_item_id = seed.order_item_id);

-- 一条待审核的评价：管理台的审核队列不能是空的，否则「审核 → 发布/隐藏」
-- 这条链路演示时无从下手。内容里带联系方式，正是最典型的一类待审样本。
insert into review (spu_id, sku_id, order_id, order_item_id, user_id, rating, content,
                    is_anonymous, status, useful_count, created_at)
select 4, 8, -200, -40, 'lisi', 5,
       '效果不错，需要的可以加我微信详聊，有内部渠道价。', 0, 'PENDING', 0, '2026-09-29 22:47:00'
where not exists (select 1 from review where order_item_id = -40);

-- 带商家回复的那一条。评价不是只写孤岛，商家侧得有能回应的地方；
-- 单独放一条是因为它多带 reply_* 三列，混进上面那批 union 会让列数对不上。
insert into review (spu_id, sku_id, order_id, order_item_id, user_id, rating, content,
                    is_anonymous, status, reply_content, reply_at, reply_by, useful_count, created_at)
select 1, 2, -9, -9, 'yanglin', 5,
       '客服先问我有没有在吃别的药才给的建议，很专业。',
       0, 'PUBLISHED',
       '感谢您的认可！正在服用抗凝药物或甲状腺药物时，维生素 D3 的建议剂量确实需要调整，我们会在下单前主动提醒。',
       '2026-08-21 10:30:00', 'admin', 4, '2026-08-20 09:18:00'
where not exists (select 1 from review where order_item_id = -9);

-- 带图评价。url 指向前端本地生成的 SVG（frontend/public/img/photos/，
-- 由 scripts/gen-product-images.mjs 生成，按评价所属商品出图），不依赖外网。
insert into review_image (review_id, url, sort)
select r.id, seed.url, seed.sort
from (
    select -1 as order_item_id, '/img/photos/rev1a-600.svg' as url,
           0 as sort
    union all select -1, '/img/photos/rev1b-600.svg', 1
    union all select -4, '/img/photos/rev4a-600.svg', 0
    union all select -13, '/img/photos/rev13a-600.svg', 0
    union all select -13, '/img/photos/rev13b-600.svg', 1
    union all select -18, '/img/photos/rev18a-600.svg', 0
    union all select -30, '/img/photos/rev30a-600.svg', 0
    union all select -30, '/img/photos/rev30b-600.svg', 1
) as seed
join review r on r.order_item_id = seed.order_item_id
where not exists (select 1 from review_image i where i.review_id = r.id and i.sort = seed.sort);

-- 老库修复：评价图从 picsum 外链换成本地生成 SVG 后，insert-only 的种子
-- （唯一键守卫）不会回头改已有行，这几行把库里还留着的旧 URL 逐条换掉。
update review_image set url = case url
    when 'https://picsum.photos/seed/rev1a/600' then '/img/photos/rev1a-600.svg'
    when 'https://picsum.photos/seed/rev1b/600' then '/img/photos/rev1b-600.svg'
    when 'https://picsum.photos/seed/rev4a/600' then '/img/photos/rev4a-600.svg'
    when 'https://picsum.photos/seed/rev13a/600' then '/img/photos/rev13a-600.svg'
    when 'https://picsum.photos/seed/rev13b/600' then '/img/photos/rev13b-600.svg'
    when 'https://picsum.photos/seed/rev18a/600' then '/img/photos/rev18a-600.svg'
    when 'https://picsum.photos/seed/rev30a/600' then '/img/photos/rev30a-600.svg'
    when 'https://picsum.photos/seed/rev30b/600' then '/img/photos/rev30b-600.svg'
    else url
end
where url like '%picsum.photos%';
