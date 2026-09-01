# EnvoyMart 快速启动

## 环境要求

- **JDK 21**（Spring Boot 4.1 最低支持 17，本项目基线 21）
- Maven 3.9+
- Node.js 20+ / pnpm
- Docker（可选，用于基础设施；缺失时服务会自动降级）

## 启动基础设施（Docker）

```bash
docker compose up -d nacos redis rabbitmq mysql elasticsearch milvus
```

不启动也能跑：网关在无 Nacos 时用静态实例列表解析 `lb://`，RAG 在无 Milvus 时走内存向量库，模型在无 Key 时回退 Mock。

## 启动后端

```bash
cd backend
mvn clean install -DskipTests

# 按需启动（-pl 指定模块）
mvn -pl gateway-service spring-boot:run
mvn -pl auth-service spring-boot:run
mvn -pl product-service spring-boot:run
mvn -pl order-service spring-boot:run
mvn -pl ai-service spring-boot:run
```

## 模型配置

AI 能力需要模型 Key；不配也能启动，但会回退到 `MockLLMProvider`（只回显，不做真实推理）。

```bash
export LLM_API_KEY=<你的 Key>
export LLM_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1  # 默认值
export LLM_MODEL=qwen-plus
export LLM_EMBEDDING_MODEL=text-embedding-v4
```

换 DeepSeek / OpenAI / 本地 Ollama 只需改 `LLM_BASE_URL` 与 `LLM_MODEL`（均走 OpenAI 兼容协议）。

启用 Milvus 作为向量库：

```bash
export SPRING_PROFILES_ACTIVE=milvus
export MILVUS_HOST=127.0.0.1
export MILVUS_PORT=19530
```

## 启动前端

```bash
cd frontend
pnpm install
pnpm dev
```

## 访问地址

| 组件 | 地址 |
|------|------|
| 前端页面 | http://localhost:5173 |
| API 网关 | http://localhost:8080 |
| Nacos 控制台 | http://localhost:8848 |
| RabbitMQ 管理 | http://localhost:15672 (envoymart/envoymart123) |
| Sentinel 控制台 | http://localhost:8718 |
| 接口文档 | http://localhost:9001/swagger-ui/index.html（各服务同路径） |
| MCP 端点 | http://localhost:9004/mcp |
| 指标 | http://localhost:9004/actuator/prometheus |

## 验证 Agent 链路

```bash
# 直接打 ai-service（网关需要 JWT，调试时绕开更方便）
curl -X POST http://127.0.0.1:9004/ai/chat \
  -H "Content-Type: application/json" \
  -H "X-User-Id: u1001" \
  -d '{"sessionId":"s1","message":"帮我推荐几款百元以内的耳机"}'
```

返回体里的 `knowledge`（RAG 命中）、`toolCalls`（工具调用轨迹）、`recommendedProducts`（商品卡片）可用于确认链路是否真的走通。

## 验证 MCP 工具发布

```bash
# 1. 初始化握手，拿 session id
curl -D - -X POST http://127.0.0.1:9004/mcp \
  -H "Content-Type: application/json" -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"curl","version":"1.0"}}}'

# 2. 用上一步返回的 Mcp-Session-Id 列出工具
curl -X POST http://127.0.0.1:9004/mcp \
  -H "Content-Type: application/json" -H "Accept: application/json, text/event-stream" \
  -H "Mcp-Session-Id: <上一步的 session id>" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}'
```
