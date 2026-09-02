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
| POST | /ai/chat | 智能对话（RAG + 工具调用 + 记忆） |
| POST | /ai/chat/stream | SSE 流式对话（delta / done / error） |
| POST | /mcp | MCP Server（Streamable HTTP），发布 order_query / logistics_query / product_search / order_cancel |
| GET  | /actuator/prometheus | 指标（含 Spring AI 的 gen_ai.* 语义指标） |

**高危操作确认**：请求体带 `approved: true` 时才会执行 `order_cancel`；未确认时返回 `pendingActions` 列出待确认工具。

**MCP 鉴权**：`/mcp` 需携带 `Authorization: Bearer <JWT>` 或 `X-MCP-API-Key: <key>`（后者需配置 `MCP_API_KEY`）。

`/ai/chat` 返回体中的三个字段可用于确认链路是否真的走通：

| 字段 | 含义 |
|------|------|
| `knowledge` | RAG 命中的知识片段 |
| `toolCalls` | 本轮实际发生的工具调用轨迹 |
| `recommendedProducts` | 工具结果中抽取出的商品卡片 |

## 接口文档

各服务暴露 OpenAPI 文档（springdoc）：`http://localhost:<port>/swagger-ui/index.html`

## Gateway (8080)

所有请求统一通过网关 `http://localhost:8080` 接入，由网关路由至对应微服务。网关负责 JWT 鉴权、Sentinel 限流与路由转发；无 Nacos 时用静态实例列表解析 `lb://`。
