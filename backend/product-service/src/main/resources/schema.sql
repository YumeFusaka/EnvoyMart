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
    description text
);
