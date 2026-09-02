<p align="center">
  <strong>EnvoyMart · 智能电商平台</strong><br/>
  Spring Cloud 微服务 + Spring AI Agent + Vue 3 全栈
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Java-21-589636" />
  <img src="https://img.shields.io/badge/Spring%20Boot-4.1.1-6DB33F" />
  <img src="https://img.shields.io/badge/Spring%20Cloud-2025.1.3-6DB33F" />
  <img src="https://img.shields.io/badge/Spring%20AI-2.0.1-6DB33F" />
  <img src="https://img.shields.io/badge/Vue-3.5-4FC08D" />
  <img src="https://img.shields.io/badge/license-MIT-blue" />
</p>

---

## 项目简介

EnvoyMart 是基于 Spring Cloud Alibaba + Spring AI + Vue 3 的智能电商平台，覆盖用户、商品、订单、支付、物流、评价等核心业务，并在其上构建 Agent 能力：RAG 知识问答、多步工具编排、长期记忆、MCP 工具发布。

**工程重点不在于"接了个大模型"，而在于让 Agent 可观测、可评测、可降级。**

- **微服务底座**：Spring Cloud Alibaba（Nacos + Sentinel + Gateway），9 个 Maven 模块
- **Agent 编排层**：自研 `agent-core`（意图路由 / ReAct / Plan-and-Execute / 工具注册 / 记忆 / RAG）
- **模型接入层**：Spring AI 2.0 `ChatModel`，OpenAI 兼容协议（默认百炼，可切 DeepSeek / Ollama）
- **检索**：BM25 + 向量混合召回 → RRF 融合 → gte-rerank 精排；带 Hit Rate / MRR / NDCG 评测
- **记忆**：LLM 抽取事实/偏好 → 向量库语义召回 → 注入 system prompt
- **MCP**：把订单、物流、商品、取消订单能力以 MCP 协议对外发布，端点带鉴权
- **可靠性**：ReAct 死循环检测、高危操作人工确认（HITL）、链路异常整体降级
- **可观测**：Micrometer + OTLP + Prometheus，每次模型调用记录耗时与 token
- **流式**：`/ai/chat/stream`（SSE），首字延迟只取决于首个 token 到达时间

## 系统架构

```
                        ┌──────────────────────────────┐
                        │      Vue 3 SPA 前端           │
                        │  商城 · 购物车 · AI 智能助手   │
                        └──────────────┬───────────────┘
                                       │ HTTP
                        ┌──────────────▼───────────────┐
                        │  API 网关 (Spring Cloud       │
                        │  Gateway + Sentinel)          │
                        │  JWT 鉴权 · 限流 · 路由       │
                        └──┬──────┬──────┬──────┬───────┘
                           │      │      │      │
                 ┌─────────┘      │      │      └─────────┐
                 ▼                ▼      ▼                ▼
          ┌────────────┐  ┌────────────┐ ┌────────────┐ ┌────────────┐
          │ 认证服务    │  │ 商品服务    │ │ 订单服务    │ │ AI 服务     │
          │ auth 9001  │  │ product    │ │ order 9003 │ │ ai 9004    │
          └────────────┘  │ 9002       │ └────────────┘ └─────┬──────┘
          ┌────────────┐  └────────────┘ ┌────────────┐        │
          │ 支付服务    │                 │ 评价服务    │ ┌──────▼──────┐
          │ payment    │                 │ review     │ │ agent-core  │
          │ 9005       │                 │ 9006       │ │ （编排层）   │
          └────────────┘                 └────────────┘ └──────┬──────┘
                                                               │
          ┌────────────────────────────────────────────────────▼──────┐
          │  基础设施：Nacos · Redis · RabbitMQ · ES 9 · MySQL · Milvus │
          └───────────────────────────────────────────────────────────┘
```

## 模块清单

| 模块 | 端口 | 说明 |
|------|------|------|
| `gateway-service` | 8080 | 网关：路由 + JWT 鉴权 + Sentinel 限流 |
| `auth-service` | 9001 | 用户认证与 JWT 签发 |
| `product-service` | 9002 | 商品 CRUD + ES 搜索 + Redis 热点缓存 |
| `order-service` | 9003 | 订单与购物车 + Redisson 分布式锁 + RabbitMQ 事件 |
| `ai-service` | 9004 | Agent 编排、RAG、记忆、MCP Server |
| `payment-service` | 9005 | 支付创建/回调 |
| `review-service` | 9006 | 商品评价 |
| `agent-core` | — | 自研 Agent 编排层（纯 Java 库，无 Spring 依赖） |
| `common` | — | 公共模块（Result / JWT / 异常处理 / 上下文透传） |

## Agent 执行链路

```
执行前                          执行中                              执行后
┌────────┐ ┌────────┐ ┌───────┐ ┌────────┐ ┌──────────┐ ┌────────┐ ┌──────────────┐
│Memory  │→│ RAG    │→│Prompt │→│意图路由 │→│工具执行   │→│回答合成 │→│记忆沉淀       │
│语义召回 │ │混合检索 │ │组装   │ │Skill/  │ │ToolRegistry│ │LLM 生成│ │LLM 抽取事实   │
│        │ │+ 重排   │ │       │ │PAE/ReAct│ │+ 轨迹记录 │ │        │ │→ 向量库       │
└────────┘ └────────┘ └───────┘ └────────┘ └──────────┘ └────────┘ └──────────────┘
```

**路由策略**：匹配 Skill → 按工作流执行；LLM 规划出可执行步骤 → Plan-and-Execute；否则 → ReAct（带 RAG 知识直接回答）。

**可靠性护栏**：ReAct 同一「工具+参数」重复调用超阈值即中止；模型/工具异常整体降级为可读回复；重排/嵌入失败自动回退。

## 检索评测（可复现）

`RetrievalQualityTest` 用两组标注样本锁住检索质量：

| 样本组 | Hit Rate@3 | MRR | 说明 |
|--------|-----------|-----|------|
| 字面重合查询（8 条） | 1.000 | 1.000 | 关键词检索的强项 |
| 口语化改写（4 条） | 0.750 | 0.750 | 暴露纯关键词检索的短板，是引入向量检索与重排的量化依据 |

## 快速启动

```bash
# 0. 依赖 JDK 21（Boot 4.1 最低 17，本项目用 21）

# 1. 基础设施（可选，缺失时服务会自动降级）
docker compose up -d nacos redis rabbitmq mysql elasticsearch milvus

# 2. 后端
cd backend
mvn clean install -DskipTests

# 启动网关与业务服务
mvn -pl gateway-service spring-boot:run
mvn -pl auth-service spring-boot:run
mvn -pl product-service spring-boot:run
mvn -pl order-service spring-boot:run
mvn -pl ai-service spring-boot:run

# 3. 前端
cd frontend && pnpm install && pnpm dev
```

**AI 能力所需的模型配置**（不配也能启动，会自动回退到 Mock 模型）：

```bash
export LLM_API_KEY=<百炼 / DeepSeek / OpenAI 的 Key>
export LLM_MODEL=qwen-plus                 # 对话模型
export LLM_EMBEDDING_MODEL=text-embedding-v4
# 可选：接入 Milvus 作为向量库
export SPRING_PROFILES_ACTIVE=milvus
```

访问地址：
- 前端：`http://localhost:5173`
- API 网关：`http://localhost:8080`
- 接口文档：`http://localhost:9001/swagger-ui/index.html`（各服务同路径）
- MCP 端点：`http://localhost:9004/mcp`（Streamable HTTP）
- 指标：`http://localhost:9004/actuator/prometheus`

## 技术栈

| 类别 | 技术 |
|------|------|
| 语言 | Java 21, TypeScript |
| 微服务 | Spring Boot 4.1.1, Spring Cloud 2025.1.3, Spring Cloud Alibaba 2025.1.0.0 |
| AI 框架 | Spring AI 2.0.1（ChatModel / Tool Calling / MCP Server / EmbeddingModel） |
| Agent | 自研 agent-core：意图路由、ReAct、Plan-and-Execute、ToolRegistry、Skill/Workflow |
| 检索 | BM25 + 向量混合召回、RRF 融合、gte-rerank 精排、Hit Rate/MRR/NDCG 评测 |
| 向量库 | Milvus（生产）/ 内存 IVF 索引（本地降级） |
| 记忆 | LLM 事实抽取 + 向量语义召回，知识与记忆分库隔离 |
| 可观测 | Micrometer Tracing + OTLP + Prometheus，逐次调用记录 token 与耗时 |
| 数据库 | MySQL 8.4 / H2（本地） |
| ORM | MyBatis-Plus 3.5.17 |
| 缓存 | Redis 7.4 + Redisson 4.7 |
| 消息队列 | RabbitMQ 4.1 |
| 搜索引擎 | Elasticsearch 9.4.5 |
| 前端 | Vue 3.5, Vite 8, Element Plus, Pinia, Axios |
| 接口文档 | springdoc-openapi 3.1.1 |
| 鉴权 | jjwt 0.13 |
| 包管理 | Maven, pnpm |

## 项目结构

```
EnvoyMart/
├── docker-compose.yml
├── backend/
│   ├── pom.xml                 # 聚合 POM（Boot 4.1.1 + Spring AI 2.0.1）
│   ├── Dockerfile
│   ├── common/                 # Result / JWT / 异常处理 / 身份透传
│   ├── gateway-service/
│   ├── auth-service/
│   ├── product-service/
│   ├── order-service/
│   ├── payment-service/
│   ├── review-service/
│   ├── agent-core/             # 自研 Agent 编排层
│   │   └── src/main/java/.../agent/
│   │       ├── core/           # Agent / ReActEngine / PAEEngine / ContextManager
│   │       ├── llm/            # LLMProvider 契约、PlanStep、ToolExecution
│   │       ├── memory/         # 短期/长期记忆与固化器
│   │       ├── rag/            # 分词、混合检索、重排、向量库、评测器
│   │       ├── skill/          # Skill / Workflow
│   │       └── tool/           # Tool / ToolRegistry / MCP 适配
│   └── ai-service/             # Agent 装配、Spring AI 接入、MCP Server、记忆与 RAG 实现
└── frontend/
    └── src/                    # 页面 / 组件 / API / 状态管理
```

## License

MIT © 2025-2026 YumeFusaka
