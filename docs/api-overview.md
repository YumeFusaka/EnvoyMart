# EnvoyMart API 概览

## Auth Service (9001)

| 方法 | 路径 | 描述 |
|------|------|------|
| POST | /auth/login | 用户登录，返回 JWT |
| POST | /auth/register | 用户注册 |
| GET  | /auth/profile | 获取当前用户信息 |

## Product Service (9002)

| 方法 | 路径 | 描述 |
|------|------|------|
| GET  | /products | 商品列表（支持 keyword/category 筛选） |
| GET  | /products/search | ES 搜索引擎（keyword + category + 分页） |
| GET  | /products/{id} | 商品详情 |
| GET  | /products/recommendations | 智能推荐（基于 query 关键词匹配） |
| POST | /products/stock/deduct | 库存扣减 |

## Order Service (9003)

| 方法 | 路径 | 描述 |
|------|------|------|
| POST | /cart | 添加购物车 |
| GET  | /cart | 查看购物车 |
| PUT  | /cart/{id} | 更新购物车数量 |
| POST | /orders/checkout | 下单结算（含分布式锁扣库存） |
| GET  | /orders | 订单列表 |
| GET  | /orders/{id} | 订单详情 |
| GET  | /orders/{id}/logistics | 物流追踪 |

## Payment Service (9005)

| 方法 | 路径 | 描述 |
|------|------|------|
| POST | /payments | 创建支付 |
| POST | /payments/callback | 支付回调 |
| GET  | /payments/{orderId} | 查询支付状态 |

## Review Service (9006)

| 方法 | 路径 | 描述 |
|------|------|------|
| POST | /reviews | 创建评价 |
| GET  | /reviews/{productId} | 商品评价列表 |

## AI Service (9004)

| 方法 | 路径 | 描述 |
|------|------|------|
| POST | /ai/chat | 智能对话（支持商品/订单/物流查询） |

## Gateway (8080)

所有请求统一通过网关 `http://localhost:8080` 接入，由网关路由至对应微服务。
