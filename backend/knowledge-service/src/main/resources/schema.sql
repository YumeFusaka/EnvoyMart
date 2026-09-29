-- 知识库建表脚本。
--
-- 切片的**向量**落在 Milvus、**实体关系**落在 Neo4j，都不在这两张表里。
-- 这里存的是文档与切片的**原文与位置**——它们是溯源的锚点：
-- 向量库告诉你「哪一片最相关」，这两张表告诉你「那一片是哪份文件的第几章第几条」。
--
-- 位置元数据（chapter / clause / page_no / char_offset）加上文档级的
-- version / issuer / effective_date，构成引用的七元组。
-- 这是「点引用跳原文并高亮」能成立的全部前提——
-- 缺任何一项，溯源就退化成「某份文档里说过」。
create table if not exists knowledge_document (
    id bigint auto_increment primary key,
    -- 对外引用的文档编号，形如 KB-0007。
    -- 售后政策表与图谱关系都靠它回指来源文档
    doc_no varchar(32) not null unique,
    title varchar(255) not null,
    -- POLICY 政策 / MANUAL 说明书 / FAQ 常见问题 / SPEC 参数规格 / GUIDE 指南
    doc_type varchar(32) not null,
    -- 发布机构。同样是政策，厂商说明书和平台规则的可信度不同，检索时要能区分
    issuer varchar(128),
    version varchar(32),
    effective_date date,
    -- 0 停用 / 1 启用。停用后不再被召回，但历史引用仍能反查到它
    status tinyint not null default 1,
    file_path varchar(512),
    content text not null,
    created_at datetime not null,
    updated_at datetime not null,
    index idx_document_type_status (doc_type, status)
);

create table if not exists knowledge_chunk (
    id bigint auto_increment primary key,
    doc_id bigint not null,
    chunk_index int not null,
    content text not null,
    -- 以下四列来自结构分层切分：章 → 条 → 段 → 句，
    -- 每片自带它在原文中的位置，脱离文档后仍能自证语境
    chapter varchar(128),
    clause varchar(128),
    page_no int,
    char_offset int,
    -- Milvus 中的向量主键。检索命中后靠它反查本行，才能拿到完整的引用位置
    vector_id varchar(64),
    constraint uk_chunk_doc_index unique (doc_id, chunk_index),
    index idx_chunk_doc (doc_id)
);
