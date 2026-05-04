create table if not exists cart_item (
    id bigint auto_increment primary key,
    user_id varchar(32) not null,
    product_id bigint not null,
    quantity int not null
);

create table if not exists shop_order (
    id bigint auto_increment primary key,
    order_no varchar(64) not null unique,
    user_id varchar(32) not null,
    recipient_name varchar(64) not null,
    recipient_phone varchar(32) not null,
    address varchar(255) not null,
    total_amount decimal(10,2) not null,
    status varchar(32) not null,
    created_at timestamp
);

create table if not exists shop_order_item (
    id bigint auto_increment primary key,
    order_id bigint not null,
    product_id bigint not null,
    product_name varchar(128) not null,
    product_image varchar(255),
    unit_price decimal(10,2) not null,
    quantity int not null,
    subtotal decimal(10,2) not null
);

-- 查询索引：高频查询 user_id + status 维度
create index if not exists idx_shop_order_user on shop_order(user_id, status);
create index if not exists idx_shop_order_created on shop_order(created_at desc);
create index if not exists idx_shop_order_item_order on shop_order_item(order_id);
create index if not exists idx_cart_item_user on cart_item(user_id);
