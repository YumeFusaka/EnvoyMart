# EnvoyMart Agent 电商平台

<p align="center">
  <strong>Spring Cloud + 自研 Agent 框架 + Vue3 智能电商平台</strong>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Java-17-589636" />
  <img src="https://img.shields.io/badge/Spring%20Boot-3.5.14-6DB33F" />
  <img src="https://img.shields.io/badge/Spring%20Cloud-2025.0.0-6DB33F" />
  <img src="https://img.shields.io/badge/Vue-3.5-4FC08D" />
  <img src="https://img.shields.io/badge/license-MIT-blue" />
</p>

---

## 项目简介

EnvoyMart 是基于 Spring Cloud Alibaba + 自研 Agent 框架 + Vue3 构建的智能电商平台，覆盖用户、商品、订单、支付、物流、评价与客服等核心业务，并扩展智能导购、RAG 知识增强问答、Agent 多步推理与工具编排能力，实现"业务系统 + 智能决策"一体化电商架构。

- **微服务底座**：Spring Cloud Alibaba（Nacos + Sentinel + Gateway），9 个微服务独立部署
- **Agent 框架**：自研三层架构（LLM 接入 → ReAct/PAE 推理 → Tool/Skill 执行）+ RAG 知识增强 + Memory 持久记忆
- **前端**：Vue 3 + TypeScript + Element Plus，6 个完整页面
- **中间件**：Redis 缓存 & 分布式锁 + RabbitMQ 事件驱动 + Elasticsearch 搜索引擎

## 系统架构

```
                       ┌─────────────────────────────┐
                       │    Vue 3 SPA 前端            │
                       │  商城 · 购物车 · AI 智能助手  │
                       └─────────────┬───────────────┘
                                     │ HTTP
                       ┌─────────────▼───────────────┐
                       │   API 网关 (Spring Cloud     │
                       │   Gateway + Sentinel)        │
                       │   JWT 鉴权 · 限流 · 路由     │
                       └──┬──────┬──────┬──────┬─────┘
                          │      │      │      │
              ┌───────────┘      │      │      └───────────┐
              ▼                  ▼      ▼                  ▼
      ┌────────────┐   ┌────────────┐   ┌────────────┐   ┌────────────┐
      │ 认证服务    │   │ 商品服务    │   │ 订单服务    │   │ AI 服务     │
      │ auth       │   │ product    │   │ order      │   │ ai         │
      │ 9001       │   │ 9002       │   │ 9003       │   │ 9004       │
      └────────────┘   └─────┬──────┘   └─────┬──────┘   └─────┬──────┘
             ┌──────────┐    │                │                │
             │ 支付服务   │   │   ┌──────────┐ │   ┌──────────┐ │
             │ payment  │   │   │ 评价服务   │ │   │ Agent    │ │
             │ 9005     │   │   │ review    │ │   │ Core     │ │
             └──────────┘   │   │ 9006      │ │   │ (库)     │ │
                            │   └──────────┘ │   └──────────┘ │
                            ▼                ▼                ▼
                    ┌───────────────────────────────────────────┐
                    │           基础设施层                       │
                    │  Nacos · Redis · RabbitMQ · ES · MySQL    │
                    └───────────────────────────────────────────┘
```

## 微服务清单

| 服务 | 端口 | 说明 | 技术栈 |
|------|------|------|--------|
| `gateway-service` | 8080 | API 网关，路由转发 + JWT 鉴权 + Sentinel 限流 | Spring Cloud Gateway, Sentinel |
| `auth-service` | 9001 | 用户认证与 JWT 签发 | Spring Boot, MyBatis-Plus, JJWT |
| `product-service` | 9002 | 商品 CRUD + ES 全文搜索 + Redis 热点缓存 | MyBatis-Plus, ES, Redis |
| `order-service` | 9003 | 订单与购物车 + Redis 缓存 + Redisson 锁 + RabbitMQ 事件 | Redisson, RabbitMQ |
| `ai-service` | 9004 | 智能客服 & 导购 | Agent Core, Feign |
| `payment-service` | 9005 | 支付创建/回调 + 支付完成事件 | MyBatis-Plus, RabbitMQ |
| `review-service` | 9006 | 商品评价 | MyBatis-Plus |
| `agent-core` | — | 自研 Agent 框架（嵌入 ai-service 运行） | 纯 Java 库 |

## 自研 Agent 框架

### 三层架构

```
┌──────────────────────────────────────────────────────┐
│                  能力层 (Capability)                   │
│  Tool 注册中心 · Skill 编排 · MCP 协议适配             │
├──────────────────────────────────────────────────────┤
│                  推理层 (Reasoning)                    │
│  ReAct (实时问答) · Plan-and-Execute (多步编排)        │
├──────────────────────────────────────────────────────┤
│                  LLM 接入层 (LLM)                      │
│  LLMProvider 接口 · MockLLMProvider · 消息协议         │
└──────────────────────────────────────────────────────┘
```

### 三阶段执行链路

```
执行前                                     执行中                                        执行后
┌──────────┐  ┌───────────┐  ┌────────┐  ┌──────────┐  ┌──────────────┐  ┌──────────┐  ┌───────────────────┐
│ Memory   │→ │ RAG 知识   │→ │ Prompt │→ │ ReAct /  │→ │ ToolRegistry  │→ │ LLM      │→ │ MemoryConsolidator│
│ 加载     │  │ 检索       │  │ 注入   │  │ PAE 推理  │  │ 调用业务工具  │  │ 生成回答  │  │ 长期记忆沉淀       │
└──────────┘  └───────────┘  └────────┘  └──────────┘  └──────────────┘  └──────────┘  └───────────────────┘
```

### RAG 知识增强

- **文档处理**：结构化切分商品数据、活动规则、售后政策、物流说明
- **混合检索**：Hybrid Retriever 融合关键词匹配（BM25）与向量语义检索
- **RRF 融合**：互惠排名融合算法合并多路召回 Top-K 结果
- **Prompt 融合**：检索结果注入 LLM 上下文，生成可解释回答

### 执行模式

| 模式 | 适用场景 | 特点 |
|------|---------|------|
| **ReAct** | 实时问答、商品咨询、售后快速回复 | Thought-Action-Observation 循环推理 |
| **Plan-and-Execute** | 物流追踪、比价、多步售后流程 | 先拆解为子任务，再分步执行 |
| **Skill 匹配** | 标准化业务流程（退换货、投诉） | 预定义工作流编排，一步直达 |

## 基础设施集成

| 中间件 | 用途 | 关键实现 |
|--------|------|---------|
| **Nacos** | 服务注册发现 + 配置管理 | `@EnableDiscoveryClient` + `lb://` 路由 |
| **Sentinel** | 网关限流熔断 | Sentinel Gateway 集成 + Dashboard 监控 |
| **Redis** | 购物车缓存 + 热点商品缓存 | RedisTemplate + 72h TTL |
| **Redisson** | 分布式锁（防超卖） | `RLock.tryLock()` 锁定库存扣减 |
| **RabbitMQ** | 事件驱动（订单/支付/库存） | Topic Exchange + 死信队列兜底 |
| **Elasticsearch** | 商品全文搜索 | 多字段组合查询 + IK 分词 |

## 前端页面

| 页面 | 功能 |
|------|------|
| 登录页 | 用户名密码登录，JWT 存储，路由守卫 |
| 电商工作台 | 商品网格 + 搜索/分类筛选 + 购物车 + 订单面板 + 物流追踪 |
| 商品详情页 | 商品信息 + 标签 + 价格 + 加入购物车 |
| 订单管理页 | 订单列表 + 物流时间线 + 去支付 |
| 支付页 | 多支付方式选择 + 支付模拟 + 成功页 |
| AI 智能助手 | 多轮对话 + 知识检索 + 工具调用 + 商品推荐卡片联动 |

## 快速启动

```bash
# 1. 启动基础设施 (Docker)
docker compose up -d nacos redis rabbitmq mysql elasticsearch

# 2. 启动后端服务 (Maven)
cd backend
mvn spring-boot:run -pl services/auth-service -am
mvn spring-boot:run -pl services/product-service -am
mvn spring-boot:run -pl services/order-service -am
mvn spring-boot:run -pl services/ai-service -am

# 3. 启动前端
cd frontend
pnpm install
pnpm dev
```

访问地址：
- 前端：`http://localhost:5173`
- API 网关：`http://localhost:8080`
- Nacos 控制台：`http://localhost:8848`
- Knife4j 文档：`http://localhost:8080/doc.html`

## 技术栈

| 类别 | 技术 |
|------|------|
| **语言** | Java 17, TypeScript |
| **微服务** | Spring Boot 3.5, Spring Cloud 2025, Spring Cloud Alibaba 2025 |
| **注册中心** | Nacos 2.5 |
| **网关** | Spring Cloud Gateway |
| **限流熔断** | Sentinel 1.8 |
| **数据库** | MySQL 8.4 / H2 内存库 |
| **ORM** | MyBatis-Plus 3.5 |
| **缓存** | Redis 7.4 + Redisson 3.49 |
| **消息队列** | RabbitMQ 4.1 |
| **搜索引擎** | Elasticsearch 8.17 |
| **前端** | Vue 3.5, Vite 5, Element Plus, Pinia, Axios |
| **AI 框架** | 自研 Agent 框架（ReAct/PAE/Memory/RAG/Tool/MCP/Skill） |
| **服务调用** | OpenFeign + Nacos 负载均衡 |
| **包管理** | Maven, pnpm |

## 项目结构

```
EnvoyMart/
├── docker-compose.yml          # 基础设施编排
├── backend/
│   ├── pom.xml                 # 聚合 POM
│   ├── Dockerfile              # 多阶段构建
│   └── services/
│       ├── common/             # 公共模块（Result, JWT, 异常处理）
│       ├── gateway-service/    # API 网关
│       ├── auth-service/       # 认证服务
│       ├── product-service/    # 商品服务
│       ├── order-service/      # 订单服务
│       ├── payment-service/    # 支付服务
│       ├── review-service/     # 评价服务
│       ├── ai-service/         # AI 智能服务
│       └── agent-core/         # 自研 Agent 框架
├── frontend/
│   └── src/
│       ├── views/              # 页面（6个）
│       ├── components/         # 组件（6个）
│       ├── api/                # API 层（5个模块）
│       ├── types/              # TypeScript 类型
│       ├── stores/             # Pinia 状态管理
│       ├── router/             # Vue Router
│       └── utils/              # Axios 封装
└── docs/
    ├── architecture.md         # 架构设计文档
    ├── api-overview.md         # API 概览
    └── quick-start.md          # 快速启动
```

## License

MIT © 2025-2026 YumeFusaka
