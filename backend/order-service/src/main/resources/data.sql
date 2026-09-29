-- 交易库种子数据：售后政策。
--
-- `insert ... select ... from (派生表) where not exists (...)` —— 一次插入多行且整体幂等。
-- `spring.sql.init.mode=always` 让这个脚本每次启动都跑一遍，不幂等的写法会让政策重复堆积，
-- 而政策引擎取的是「第一条匹配」，重复项会随机命中，表现为时长在两次重启之间莫名变化。
--
-- doc_ref 指向知识库文档编号：规则给结论，知识库给依据。

insert into after_sale_policy
    (id, category_id, type, returnable, return_days, quality_days, max_refund_ratio, requirements, doc_ref)
select * from (
    -- 全类目默认：7 天无理由、15 天质量问题
    select 1 as id, null as category_id, 'REFUND_ONLY' as type, 1 as returnable,
           7 as return_days, 15 as quality_days, 1.00 as max_refund_ratio,
           '商品需保持完好、不影响二次销售' as requirements, 'KB-0001' as doc_ref
    union all select 2, null, 'RETURN_REFUND', 1, 7, 15, 1.00,
           '商品需保持完好、不影响二次销售，寄回运费由责任方承担', 'KB-0001'
    union all select 3, null, 'EXCHANGE', 1, 7, 15, 1.00,
           '仅支持同款同规格换货，需商品完好', 'KB-0002'

    -- 特殊医学用途配方食品：食品安全考虑，**拆封后不支持无理由退货**，
    -- 质量问题仍按 15 天处理。这条是「政策按类目定制」的实际用例
    union all select 4, 5, 'RETURN_REFUND', 1, 0, 15, 1.00,
           '特殊医学用途配方食品一经拆封不支持无理由退货；存在质量问题的除外', 'KB-0003'
    union all select 5, 5, 'REFUND_ONLY', 1, 0, 15, 1.00,
           '特殊医学用途配方食品一经拆封不支持无理由退货；存在质量问题的除外', 'KB-0003'

    -- 功能性食品：同为食品，规则一致
    union all select 6, 6, 'RETURN_REFUND', 1, 0, 15, 1.00,
           '食品类商品一经拆封不支持无理由退货；存在质量问题的除外', 'KB-0004'
) as seed
where not exists (select 1 from after_sale_policy where id = seed.id);
