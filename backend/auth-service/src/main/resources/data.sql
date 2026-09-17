-- 演示账号种子数据。口令为 123456，此处存 BCrypt 哈希（每次哈希盐不同，两行哈希值不同是正常的）。
--
-- 写成「不存在才插入」而不是「先删后插」：后者会在每次启动时无条件覆盖这两个账号，
-- 把改过的昵称、头像、口令一律打回种子值。种子数据的作用是"让空库能跑起来"，
-- 不是"每次启动都重置演示账号"。
--
-- 语法选择同 product-service：`insert ... select ... where not exists (...)` 是
-- H2(MODE=MySQL) 与 MySQL 都认的写法。H2 的 `merge into ... key()` 是专有语法，
-- 换成本地 MySQL 时会在初始化阶段直接失败（表建好了但数据插不进去）。

insert into sys_user (id, username, password, nickname, role_name, avatar)
select 'u1001', 'alice', '$2a$10$NKEj99ey0D.6HZnCheDsM.PHlQUBTj3m.PRq4KuEBkCosuw0kXpi.', 'Alice', 'customer', 'https://images.unsplash.com/photo-1494790108377-be9c29b29330?w=200'
where not exists (select 1 from sys_user where id = 'u1001');

insert into sys_user (id, username, password, nickname, role_name, avatar)
select 'u1002', 'bob', '$2a$10$NKEj99ey0D.6HZnCheDsM.PHlQUBTj3m.PRq4KuEBkCosuw0kXpi.', 'Bob', 'customer', 'https://images.unsplash.com/photo-1500648767791-00dcc994a43e?w=200'
where not exists (select 1 from sys_user where id = 'u1002');
