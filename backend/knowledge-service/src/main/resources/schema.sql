-- 知识库建表脚本。
--
-- 切片的**向量**落在 Milvus、**实体关系**落在 Neo4j，都不在这两张表里。
-- 这里存的是文档与切片的**原文与位置**——它们是溯源的锚点：
-- 向量库告诉你「哪一片最相关」，这两张表告诉你「那一片是哪份文件的第几章第几条」。
--
-- 字段照着语料实际的元信息来（`knowledge/*.md` 的 front-matter），不预留没人填的列：
-- 本仓库已经吃过一次「schema 描述得很好、但从来没有代码写过它」的亏
-- （售后政策表的 doc_ref 指向了不存在的文档编号，两份互不引用的文件谁也不喊）。
-- 加列的成本很低，加一个永远为空的列则是把「没做」伪装成「做了」。
create table if not exists knowledge_document (
    id bigint auto_increment primary key,
    -- 对外引用的文档编号，形如 KB-0007。
    -- 售后政策表（order-service/data.sql）与知识图谱的关系都靠它回指来源文档
    doc_no varchar(32) not null,
    title varchar(255) not null,
    -- 文档类型：manual 厂商说明书 / policy 平台规则 / regulation 监管规范
    --          / spec 参数规格 / guide 使用指南
    source varchar(32) not null,
    -- 领域范围：nutrition 营养 / after_sale 售后 / logistics 物流
    --          / payment 支付 / promotion 促销 / food_safety 食品安全
    scope varchar(32) not null,
    version varchar(32) not null,
    tags varchar(255),
    -- 0 停用 / 1 启用。停用后不再进入检索索引，但历史引用仍能反查到它——
    -- 停用一份文档不该让已经给出的回答失去依据
    status tinyint not null default 1,
    content text not null,
    created_at datetime not null,
    updated_at datetime not null,
    constraint uk_document_doc_no unique (doc_no),
    index idx_document_scope_status (scope, status)
);

create table if not exists knowledge_chunk (
    id bigint auto_increment primary key,
    doc_id bigint not null,
    -- 与 Milvus 中的向量主键**同一个值**。引用回跳的入口就是它：
    -- 检索命中带回来的 chunkId → 这里 → 完整位置与所在文档全文
    chunk_id varchar(64) not null,
    chunk_index int not null,
    -- 渲染后的切片正文（含位置前缀），即真正送去向量化、真正出现在 prompt 里的那段字。
    -- 存原文会让「管理台看到的」与「模型看到的」不是一回事
    content text not null,
    -- 结构分层切分给出的位置串：《文档标题》 > 第三章 用法用量
    position varchar(255),
    -- 切片在文档正文中的起始字符偏移。前端凭它跳回原文并高亮，
    -- 单位是 UTF-16 码元（与 Java 的 String.indexOf、JS 的 slice 一致）
    char_offset int,
    created_at datetime not null,
    constraint uk_chunk_chunk_id unique (chunk_id),
    index idx_chunk_doc (doc_id)
);

-- 商品-文档关联表。
--
-- **它存在的理由**：商品与它在库里的说明文档之间原本没有任何结构化关系，两者的绑定
-- 完全依赖「图谱构建期，模型从正文里抽出 SPUx -CONTAINS-> 成分 这条边」。实测这条边
-- 会被模型漏抽（37 个商品里 13 个因此没有节点），而且漏抽时没有任何现象——
-- 文档在库里、也进了向量库，只是图上少了「它属于哪个商品」这一条。
--
-- 这是一张**应用级**的强约束表：product / knowledge / ai 三个库分属不同服务，无法建
-- 数据库外键，一致性由三个触发点保证（商品下架 / 上架 / 改文档）。
--
-- 为什么是多对多而不是 knowledge_document 上加一列 spu_id：
--   ① 一篇文档可能覆盖多个商品（如「褪黑素与 GABA 类助眠产品说明书」对应两个 SPU）；
--   ② 说明书正文会提到别的商品，那是「被提及」不是「归属」；
--   ③ 粒度可能是 SKU 而非 SPU。
create table if not exists knowledge_doc_product (
    id bigint auto_increment primary key,
    doc_no varchar(32) not null,
    -- 归属商品。用 SPU 编号而不是商品名：商品会改名、会重名（实测 SPU20 与 SPU29
    -- 同名「维生素 C 咀嚼片」），而编号不会。
    spu_id bigint not null,
    -- 预留的 SKU 维度：为空表示「这份文档针对整个 SPU」。
    -- 用法用量按粒数算的说明书是 SKU 级的（90 粒装 / 180 粒装），
    -- 当前语料还没有这种实例，但列先留在，避免将来改表。
    sku_id bigint null,
    -- SUBJECT  本文档的主体就是该商品（说明书、成分表、质检报告）
    -- MENTIONED 本文档只是提到该商品（领域文档里的举例）
    -- **只有 SUBJECT 参与生命周期联动**：商品下架要连坐的是它的说明书，
    -- 不是「某篇政策文档里顺带提过它」
    role varchar(16) not null,
    -- 这条关联是谁定的：MANUAL 上传时人工声明 / BACKFILL 历史回填 / GRAPH 图谱对齐
    -- 便于回答「这条关系可信吗」——人工声明的与模型推出来的不该同等看待
    matched_by varchar(16) not null,
    matched_at datetime not null,
    -- 同一篇文档对同一个商品只留一条 SUBJECT。两条会让「下架时停用哪些文档」出重复项
    constraint uk_doc_product unique (doc_no, spu_id, role),
    -- 下架商品时按 (spu_id, role) 反查要连坐的文档，这是那条查询的索引
    index idx_doc_product_spu (spu_id, role)
);
