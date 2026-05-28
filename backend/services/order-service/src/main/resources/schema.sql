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
