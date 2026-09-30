-- 商品库建表脚本。
--
-- 数据模型是三层：**SPU（商品概念）→ SKU（可售单元）→ 规格值**。
-- 价格与库存挂在 SKU 上——挂在 SPU 上就表达不了「128G 比 256G 便宜 800 块」。
--
-- 索引一律内联在 CREATE TABLE 里：独立的 `create index if not exists` 是
-- H2/PostgreSQL 语法，**MySQL 不支持**，会在初始化阶段直接失败。
-- 字段说明用 `--` 行注释而非内联 COMMENT：后者在 H2 的 MySQL 兼容模式下支持不稳定。

-- ==================== 类目与品牌 ====================

create table if not exists category (
    id bigint auto_increment primary key,
    parent_id bigint not null default 0,
    name varchar(64) not null,
    -- 1 一级 / 2 二级 / 3 三级
    level tinyint not null,
    -- 祖级路径，形如 1/5/12。查整棵子树用 `path like '1/5/%'` 一次索引命中，
    -- 不必写递归查询——这是类目树最常用的形态
    path varchar(255) not null,
    sort int not null default 0,
    status tinyint not null default 1,
    index idx_category_parent (parent_id),
    index idx_category_path (path)
);

create table if not exists brand (
    id bigint auto_increment primary key,
    name varchar(64) not null unique,
    logo varchar(512),
    description varchar(500),
    status tinyint not null default 1
);

-- ==================== SPU / SKU ====================

create table if not exists product_spu (
    id bigint auto_increment primary key,
    spu_code varchar(64) not null unique,
    name varchar(255) not null,
    subtitle varchar(255),
    category_id bigint not null,
    brand_id bigint,
    main_image varchar(512),
    -- 轮播图，逗号分隔。图片只用于展示、不参与查询条件，因此不做规范化
    images varchar(2048),
    -- mediumtext 而不是 text：MySQL 里 text 的上限是 65535 **字节**（注意不是字符），
    -- 中文按 utf8mb4 每字 4 字节算，约 1.6 万字就顶到上限；而接口契约允许 20 万字符。
    -- 两者对不上时的表现是：保存商品返回 500，日志里只有一句
    -- `Data truncation: Data too long for column 'detail_html'`。
    -- mediumtext 是 16MB，装得下契约允许的最大值。
    --
    -- 注意：本文件全部是 create table if not exists，对**已经存在的库**不会改列。
    -- 老库需要手工执行一次
    --   alter table product_spu modify detail_html mediumtext;
    detail_html mediumtext,
    -- 营销标签（"热销" "新品"），逗号分隔。与「属性」的区别：属性描述事实，
    -- 标签服务于运营，所以它不挂类目、也没有候选项约束
    tags varchar(255),
    -- 0 下架 / 1 上架
    status tinyint not null default 0,
    sales int not null default 0,
    -- 评分聚合冗余在这里，而不是每次从评价表实时算：
    -- 商品列表要按评分排序，聚合查询落不到索引上
    rating_avg decimal(3,2) not null default 5.00,
    review_count int not null default 0,
    created_at datetime not null,
    updated_at datetime not null,
    index idx_spu_category_status (category_id, status),
    index idx_spu_brand (brand_id)
);

create table if not exists product_sku (
    id bigint auto_increment primary key,
    spu_id bigint not null,
    sku_code varchar(64) not null unique,
    -- 金额一律以「分」为单位的整数存储。
    -- 浮点会累积误差，而「分摊后各子项之和 = 总额」是必须成立的恒等式——
    -- 整数分可以被断言，decimal/double 不行
    price bigint not null,
    original_price bigint,
    stock int not null default 0,
    image varchar(512),
    status tinyint not null default 1,
    index idx_sku_spu_status (spu_id, status)
);

-- ==================== 规格（决定买哪一个） ====================

create table if not exists product_spec (
    id bigint auto_increment primary key,
    spu_id bigint not null,
    -- 颜色 / 容量 / 口味
    name varchar(32) not null,
    sort int not null default 0,
    index idx_spec_spu (spu_id)
);

-- 列名不叫 `value`：它是 H2 的保留字，会直接报语法错误。
-- 加引号能绕过，但 MySQL 用反引号、H2 用双引号，两边不能共用一份 DDL。
create table if not exists product_spec_value (
    id bigint auto_increment primary key,
    spec_id bigint not null,
    spec_value varchar(64) not null,
    sort int not null default 0,
    index idx_spec_value_spec (spec_id)
);

-- SKU ↔ 规格值。用规范化关联表而非在 SKU 上存 JSON：
-- JSON 存法写起来快，但「找出所有黑色的 SKU」只能全表扫后内存过滤，
-- 规范化后是一次索引命中
create table if not exists product_sku_spec (
    -- 代理主键只是为了让 ORM 能按单字段操作这一行；
    -- 真正的业务规则是下面那条复合唯一约束（一个 SKU 的一个规格项只能有一个值）
    id bigint auto_increment primary key,
    sku_id bigint not null,
    spec_id bigint not null,
    spec_value_id bigint not null,
    constraint uk_sku_spec unique (sku_id, spec_id),
    index idx_sku_spec_value (spec_value_id)
);

-- ==================== 属性（描述是什么） ====================

-- 属性挂在「类目」上：同类目商品共用一套参数模板
create table if not exists product_attribute (
    id bigint auto_increment primary key,
    category_id bigint not null,
    name varchar(64) not null,
    -- TEXT 文本 / SELECT 单选 / MULTI_SELECT 多选
    input_type varchar(16) not null,
    unit varchar(16),
    sort int not null default 0,
    index idx_attribute_category (category_id)
);

create table if not exists product_attribute_value (
    id bigint auto_increment primary key,
    attribute_id bigint not null,
    attr_value varchar(255) not null,
    sort int not null default 0,
    index idx_attribute_value_attribute (attribute_id)
);

create table if not exists spu_attribute_value (
    id bigint auto_increment primary key,
    spu_id bigint not null,
    attribute_id bigint not null,
    attr_value varchar(500) not null,
    constraint uk_spu_attribute unique (spu_id, attribute_id)
);

-- ==================== 库存 ====================

-- 库存流水。每一次变动都留痕：谁扣的、哪张单、变动前后各是多少。
-- 原实现的库存只是商品表上的一列，回补失败时只能打一行日志——
-- 没有这张表就没有对账的依据。
create table if not exists stock_log (
    id bigint auto_increment primary key,
    sku_id bigint not null,
    -- DEDUCT 扣减 / RESTORE 回补 / INBOUND 入库
    change_type varchar(16) not null,
    quantity int not null,
    before_stock int not null,
    after_stock int not null,
    -- ORDER / AFTER_SALE / MANUAL
    biz_type varchar(32),
    biz_id varchar(64),
    remark varchar(255),
    created_at datetime not null,
    index idx_stock_log_sku (sku_id, created_at),
    index idx_stock_log_biz (biz_type, biz_id)
);

-- ==================== 收藏 ====================

-- 用户收藏的商品。
--
-- 为什么放在商品库而不是用户库：收藏夹页要展示商品卡片（图、价、评分、是否在售），
-- 而这些字段全在商品域。放进用户库的话，列表页的每一次刷新都要跨服务取数，
-- 一处拿不到就退化成「收藏了一条查不到的东西」。用户 id 在这里只是一个字符串标记，
-- 与订单库、售后库保存 user_id 的方式一致——每个库各存自己需要的那个 id。
--
-- 不做物理删除：用户取消收藏即删行（收藏是可再生的用户偏好，不是凭证，
-- 留一份历史没有对账价值，只会让「再次收藏」撞上唯一约束）。
create table if not exists user_favorite (
    id bigint auto_increment primary key,
    user_id varchar(32) not null,
    spu_id bigint not null,
    created_at datetime not null,
    -- 同一用户同一商品只能有一条。重复收藏由这条约束裁决，不靠「先查再插」——
    -- 后者在连点两次时两条都查不到、两条都插进去
    constraint uk_favorite_user_spu unique (user_id, spu_id),
    -- 收藏夹按「最近收藏在前」翻页
    index idx_favorite_user_time (user_id, created_at)
);
