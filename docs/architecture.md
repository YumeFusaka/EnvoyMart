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

## 四、Agent 编排层（agent-core）+ 模型接入层（Spring AI）

### 分层

```
┌────────────────────────────────────────────────────┐
│  接入层（ai-service）                                │
│  SpringAiLLMProvider · MilvusVectorStore            │
│  DashScopeReranker · LlmMemoryConsolidator · MCP    │
├────────────────────────────────────────────────────┤
│  编排层（agent-core，纯 Java，无 Spring 依赖）        │
│  Agent 意图路由 · ReAct · Plan-and-Execute          │
│  ToolRegistry · Skill/Workflow · Memory · RAG       │
└────────────────────────────────────────────────────┘
```

模型接入、工具调用循环、MCP 协议由 Spring AI 负责；推理模式选择、上下文预算、工具编排、记忆与检索由 agent-core 负责。

### 执行三阶段

1. **执行前**：RAG 混合检索 + 长期记忆语义召回 → 组装 system prompt
2. **执行中**：按 Skill → PAE → ReAct 优先级选策略，ToolRegistry 调用业务工具并记录轨迹
3. **执行后**：LLM 抽取跨会话事实/偏好 → 写入长期记忆向量库

## 五、RAG 知识增强引擎

- **分词**：CJK bigram + 拉丁字母按边界切分（零依赖，中文可命中）
- **混合检索**：BM25 关键词 + 向量语义，RRF 按 docId 融合
- **重排**：召回后多留候选，交 cross-encoder（百炼 gte-rerank）精排
- **评测**：Hit Rate / MRR / NDCG，标注样本回归（见 `docs/rag-engine-design.md`）
- **降级**：无 Milvus 走内存 IVF，无重排服务保持原顺序

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
| Java | 21 | 运行时 |
| Spring Boot | 4.1.1 | 微服务框架 |
| Spring Cloud | 2025.1.3 | 微服务治理 |
| Spring Cloud Alibaba | 2025.1.0.0 | Nacos + Sentinel |
| Spring AI | 2.0.1 | 模型接入、Tool Calling、MCP Server |
| MySQL / H2 | 8.4 / 内嵌 | 持久化 |
| Redis | 7.4 | 缓存 + 分布式锁 |
| RabbitMQ | 4.1 | 消息队列 |
| Elasticsearch | 9.4.5 | 搜索引擎 |
| Milvus | 2.6 | 向量库（生产，可选） |
| Nacos | 2.5.1 | 注册中心/配置中心 |
