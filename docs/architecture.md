# EnvoyMart 系统架构设计

## 一、总体架构

```
┌──────────────────────────────────────────────────────────────────┐
│                         客户端层                                  │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐           │
│  │  Web 端       │  │  移动端      │  │  第三方 API   │           │
│  │ (Vue3 SPA)   │  │  (预留)      │  │  (预留)       │           │
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘           │
└─────────┼─────────────────┼─────────────────┼────────────────────┘
          │                 │                 │
┌─────────▼─────────────────▼─────────────────▼────────────────────┐
│                     网关层 (Gateway Service)                      │
│          Spring Cloud Gateway + Sentinel + JWT Auth               │
│              请求路由 / 限流熔断 / 统一鉴权                         │
└─────────┬─────────────────┬─────────────────┬────────────────────┘
          │                 │                 │
┌─────────▼──────────┐ ┌───▼──────────┐ ┌───▼──────────────────────┐
│  业务微服务层        │ │  AI/Agent 层 │ │  基础设施                  │
│                     │ │              │ │                          │
│  Auth Service       │ │  AI Service  │ │  Nacos 注册中心           │
│  Product Service    │ │  Agent Core  │ │  Sentinel 控制台          │
│  Order Service      │ │  (自研框架)   │ │  RabbitMQ 消息队列         │
│  Payment Service    │ │  RAG Engine  │ │  Redis 缓存               │
│  Review Service     │ │  MCP Adapter │ │  Elasticsearch 搜索引擎    │
│  (网关/物流等)        │ │              │ │  MySQL 数据库             │
└─────────────────────┘ └──────────────┘ └──────────────────────────┘
```

## 二、微服务清单

| 服务 | 端口 | 描述 | 技术栈 |
|------|------|------|--------|
| gateway-service | 8080 | API 网关，路由转发与鉴权 | Spring Cloud Gateway, Sentinel |
| auth-service | 9001 | 用户认证与 JWT 签发 | Spring Boot, MyBatis-Plus, JWT |
| product-service | 9002 | 商品管理与搜索 | Spring Boot, MyBatis-Plus, ES, Redis |
| order-service | 9003 | 订单与购物车 | Spring Boot, MyBatis-Plus, Redis, RabbitMQ |
| ai-service | 9004 | 智能客服与导购 | Spring Boot, Agent Core, Feign |
| payment-service | 9005 | 支付处理与回调 | Spring Boot, MyBatis-Plus, RabbitMQ |
| review-service | 9006 | 商品评价 | Spring Boot, MyBatis-Plus |
| agent-core | — | 自研 Agent 框架（嵌入 AI Service） | 纯 Java 库，无 Spring 依赖 |

## 三、Spring Cloud Alibaba 集成

- **Nacos Discovery**：所有微服务通过 `@EnableDiscoveryClient` 注册至 Nacos，网关通过 `lb://` 前缀实现负载均衡调用。
- **Sentinel**：网关层集成 Sentinel 限流熔断，配置降级响应与 Dashboard 监控，保护下游服务。
- **Feign**：服务间通过 `@FeignClient` 声明式 HTTP 调用，集成 Nacos 实现客户端负载均衡。

## 四、自研 Agent 框架（agent-core）

### 三层架构

```
┌────────────────────────────────────────────────────┐
│                  能力层 (Capability Layer)           │
│  Tool 注册中心  │  Skill 工作流  │  MCP 协议适配    │
├────────────────────────────────────────────────────┤
│                  推理层 (Reasoning Layer)            │
│  ReAct 引擎 (实时问答)  │  PAE 引擎 (多步编排)       │
├────────────────────────────────────────────────────┤
│                  LLM 接入层 (LLM Layer)              │
│  LLMProvider 统一接口  │  MockLLMProvider           │
│  消息格式  │  模型配置  │  响应解析                  │
└────────────────────────────────────────────────────┘
```

### 执行三阶段

1. **执行前**：Memory System 加载上下文 + RAG Engine 检索领域知识 → 注入 Prompt
2. **执行中**：根据任务复杂度匹配推理模式，ToolRegistry 调用业务工具，结果回写推理循环
3. **执行后**：MemoryConsolidator 将偏好与事实沉淀至长期记忆

## 五、RAG 知识增强引擎

- **文档处理**：对商品数据、活动规则、售后政策等进行结构化切分
- **向量化**：EmbeddingService 将文本转为向量，InMemoryVectorStore 存储
- **混合检索**：HybridRetriever 融合关键词匹配（BM25）与向量语义检索，RRF 算法融合 Top-K 结果
- **生成**：检索结果注入 Prompt，LLM 生成可解释回答

## 六、事件驱动架构

```
订单创建 → [order.created] → 库存预扣 / 通知
支付完成 → [payment.completed] → 订单状态更新 / 发货通知
库存变更 → [stock.updated] → 缓存刷新 / 补货预警
失败消息 → [DLX] → 死信队列 → 人工处理
```

- **交换机**：Topic Exchange，支持通配符路由
- **可靠性**：消息确认 + 死信队列兜底
- **解耦**：订单、支付、库存流程通过事件异步衔接

## 七、缓存与并发

- **Redis 缓存**：购物车 72h TTL、热点商品详情缓存、商品列表缓存
- **Redisson 分布式锁**：下单时逐商品加锁，防止超卖，锁超时自动释放
- **Redis Template**：Jackson 序列化，支持泛型对象与 Java 8 时间类型

## 八、部署依赖

| 组件 | 版本 | 用途 |
|------|------|------|
| Java | 17 | 运行时 |
| Spring Boot | 3.5.14 | 微服务框架 |
| Spring Cloud | 2025.0.0 | 微服务治理 |
| Spring Cloud Alibaba | 2025.0.0.0 | Nacos + Sentinel |
| MySQL / H2 | 8.4 / 内嵌 | 持久化 |
| Redis | 7.4 | 缓存 + 分布式锁 |
| RabbitMQ | 4.1 | 消息队列 |
| Elasticsearch | 8.17 | 搜索引擎 |
| Nacos | 2.5.1 | 注册中心/配置中心 |
