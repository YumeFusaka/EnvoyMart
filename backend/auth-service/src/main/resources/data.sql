-- 演示账号口令为 123456，此处存 BCrypt 哈希（每次哈希盐不同，两行哈希值不同是正常的）
merge into sys_user key(id) values
('u1001', 'alice', '$2a$10$NKEj99ey0D.6HZnCheDsM.PHlQUBTj3m.PRq4KuEBkCosuw0kXpi.', 'Alice', 'customer', 'https://images.unsplash.com/photo-1494790108377-be9c29b29330?w=200'),
('u1002', 'bob', '$2a$10$NKEj99ey0D.6HZnCheDsM.PHlQUBTj3m.PRq4KuEBkCosuw0kXpi.', 'Bob', 'customer', 'https://images.unsplash.com/photo-1500648767791-00dcc994a43e?w=200');
