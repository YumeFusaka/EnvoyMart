-- 营销库种子数据：优惠券模板。
--
-- `insert ... select ... where not exists` —— 幂等，见 order-service 的同名文件说明。

insert into coupon (id, name, type, amount, discount, threshold, scope_type, total_count, received_count, valid_days, status, created_at)
select * from (
    -- 无门槛券：最容易演示（任何金额都能用）
    select 1 as id, '新人无门槛券' as name, 'FIXED' as type, 1000 as amount, null as discount,
           0 as threshold, 'ALL' as scope_type, 1000 as total_count, 0 as received_count,
           30 as valid_days, 1 as status, now() as created_at
    -- 满减券：能演示「差多少金额可用」
    union all select 2, '满 200 减 30', 'FIXED', 3000, null, 20000, 'ALL', 500, 0, 30, 1, now()
    union all select 3, '满 500 减 80', 'FIXED', 8000, null, 50000, 'ALL', 300, 0, 30, 1, now()
    -- 折扣券：演示折扣计算与向下取整
    union all select 4, '全场 8.5 折', 'DISCOUNT', null, 0.85, 10000, 'ALL', 200, 0, 15, 1, now()
    -- 限类目券：作用域字段有实际用上，而不是摆设
    union all select 5, '营养保健满 100 减 20', 'FIXED', 2000, null, 10000, 'CATEGORY', 300, 0, 30, 1, now()
) as seed
where not exists (select 1 from coupon where id = seed.id);

-- 限类目券的作用域：「营养保健」，也就是一级类目 1。
-- 类目是棵树，判定问的是「商品的类目路径里有没有它」—— 商品挂的是叶子类目
-- （2 维生素矿物质、3 蛋白质氨基酸、…），路径形如 `1/2`，祖先链上就有 1，
-- 所以一级类目开券天然覆盖整棵子树，运营按名字配即可。
-- 曾经这里枚举的是五个叶子类目（2,3,4,5,6），因为当时的匹配是精确比 id，
-- 写一级类目一张都匹配不上、券永远用不出去；物化路径下来之后那个绕法不再需要。
update coupon set scope_ids = '1'
 where id = 5 and (scope_ids is null or scope_ids = '' or scope_ids = '2,3,4,5,6');
