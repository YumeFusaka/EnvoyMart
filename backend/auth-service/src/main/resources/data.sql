-- 演示账号口令为 123456，此处存 BCrypt 哈希（每次哈希盐不同，两行哈希值不同是正常的）
--
-- 用「先删后插」而不是 H2 的 `merge into ... key(...)`：后者是 H2 专有语法，MySQL 不认识，
-- 换成本地 MySQL 时会在初始化阶段直接失败（表建好了但数据插不进去）。
-- 先删后插是标准 SQL，H2 与 MySQL 都能跑，且天然幂等——重复启动不会主键冲突。
delete from sys_user where id in ('u1001', 'u1002');
insert into sys_user (id, username, password, nickname, role_name, avatar) values
('u1001', 'alice', '$2a$10$NKEj99ey0D.6HZnCheDsM.PHlQUBTj3m.PRq4KuEBkCosuw0kXpi.', 'Alice', 'customer', 'https://images.unsplash.com/photo-1494790108377-be9c29b29330?w=200'),
('u1002', 'bob', '$2a$10$NKEj99ey0D.6HZnCheDsM.PHlQUBTj3m.PRq4KuEBkCosuw0kXpi.', 'Bob', 'customer', 'https://images.unsplash.com/photo-1500648767791-00dcc994a43e?w=200');
