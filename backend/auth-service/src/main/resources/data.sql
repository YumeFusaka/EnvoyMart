-- 演示账号种子数据。口令为 123456，此处存 BCrypt 哈希（每次哈希盐不同，两行哈希值不同是正常的）。
--
-- 写成「不存在才插入」而不是「先删后插」：后者会在每次启动时无条件覆盖这两个账号，
-- 把改过的昵称、头像、口令一律打回种子值。种子数据的作用是"让空库能跑起来"，
-- 不是"每次启动都重置演示账号"。
--
-- 语法选择同 product-service：`insert ... select ... where not exists (...)` 是
-- H2(MODE=MySQL) 与 MySQL 都认的写法。H2 的 `merge into ... key()` 是专有语法，
-- 换成本地 MySQL 时会在初始化阶段直接失败（表建好了但数据插不进去）。

insert into sys_user (id, username, password, nickname, role_name, avatar, status, created_at, updated_at)
select 'u1001', 'alice', '$2a$10$NKEj99ey0D.6HZnCheDsM.PHlQUBTj3m.PRq4KuEBkCosuw0kXpi.', 'Alice', 'USER', 'https://images.unsplash.com/photo-1494790108377-be9c29b29330?w=200', 1, now(), now()
where not exists (select 1 from sys_user where id = 'u1001');

insert into sys_user (id, username, password, nickname, role_name, avatar, status, created_at, updated_at)
select 'u1002', 'bob', '$2a$10$NKEj99ey0D.6HZnCheDsM.PHlQUBTj3m.PRq4KuEBkCosuw0kXpi.', 'Bob', 'USER', 'https://images.unsplash.com/photo-1500648767791-00dcc994a43e?w=200', 1, now(), now()
where not exists (select 1 from sys_user where id = 'u1002');

-- 管理员账号。role_name 是「角色」的唯一事实来源：登录时写进 JWT 的 role claim，
-- 由网关剥掉客户端伪造的头后注入 X-User-Role，管理接口的 @RequireAdmin 据此判定。
-- 因此这里改的是角色，不是给某个账号开的一个特殊接口开关。
insert into sys_user (id, username, password, nickname, role_name, avatar, status, created_at, updated_at)
select 'u1003', 'admin', '$2a$10$LhoPOers6/bbmiaJ/UbBauzAM2nJvOYD1GvENxez7/Sb.XsOxyzki', '管理员', 'ADMIN', null, 1, now(), now()
where not exists (select 1 from sys_user where id = 'u1003');

-- 地址簿种子数据。给演示账号各备两条，这样下单时能演示「切换收货地址」，
-- 而不是每次都要现场新建。
insert into user_address (id, user_id, receiver_name, receiver_phone, province, city, district, detail, is_default, tag, created_at, updated_at)
select 1, 'u1001', 'Alice', '13800138001', '上海市', '上海市', '浦东新区', '张江路 100 号 1 号楼 101 室', 1, '家', now(), now()
where not exists (select 1 from user_address where id = 1);

insert into user_address (id, user_id, receiver_name, receiver_phone, province, city, district, detail, is_default, tag, created_at, updated_at)
select 2, 'u1001', 'Alice', '13800138001', '上海市', '上海市', '徐汇区', '漕溪北路 88 号 20 层', 0, '公司', now(), now()
where not exists (select 1 from user_address where id = 2);

insert into user_address (id, user_id, receiver_name, receiver_phone, province, city, district, detail, is_default, tag, created_at, updated_at)
select 3, 'u1002', 'Bob', '13900139002', '北京市', '北京市', '朝阳区', '建国路 甲 6 号 SOHO 现代城 B 座 1801', 1, '家', now(), now()
where not exists (select 1 from user_address where id = 3);
