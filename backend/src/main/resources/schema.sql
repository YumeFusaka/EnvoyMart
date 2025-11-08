create table if not exists sys_user (
    id varchar(32) primary key,
    username varchar(64) not null unique,
    password varchar(128) not null,
    nickname varchar(64) not null,
    role_name varchar(32) not null,
    avatar varchar(255),
    created_at timestamp default current_timestamp
);

create table if not exists product (
    id bigint primary key,
    name varchar(128) not null,
    subtitle varchar(255),
    category varchar(64) not null,
    brand varchar(64) not null,
    tags varchar(255),
    price decimal(10,2) not null,
    stock int not null,
    monthly_sales int not null,
    image varchar(255),
    sales_copy varchar(255),
    semantic_keywords varchar(255),
    description text,
    created_at timestamp default current_timestamp
);

create table if not exists cart_item (
    id bigint auto_increment primary key,
    user_id varchar(32) not null,
    product_id bigint not null,
    quantity int not null,
    selected_flag bit default 1,
    created_at timestamp default current_timestamp,
    updated_at timestamp default current_timestamp
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
    created_at timestamp default current_timestamp
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
