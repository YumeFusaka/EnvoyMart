create table if not exists sys_user (
    id varchar(32) primary key,
    username varchar(64) not null unique,
    password varchar(128) not null,
    nickname varchar(64) not null,
    role_name varchar(32) not null,
    avatar varchar(255)
);
