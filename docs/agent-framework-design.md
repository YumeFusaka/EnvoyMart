# Agent 框架设计说明

## 设计目标

构建一套与业务逻辑解耦的 Agent 框架，支持电商场景下的智能问答、订单查询、售后处理等需求。核心目标：推理策略可切换、工具可注册、知识可检索、记忆可持久化。

## 三层架构

```
┌─────────────────────────────────┐
│         LLM 接入层               │
│  LLMProvider 接口                │
│  MockLLMProvider（演示）          │
│  OpenaiLLMProvider（预留）        │
├─────────────────────────────────┤
│         推理层                    │
│  ReActEngine   PAEEngine         │
│  Thought→Action→Observation     │
│  Plan→Act→Evaluate              │
│  意图路由（轻量 LLM 分类器）       │
├─────────────────────────────────┤
│         能力层                    │
│  Tool 接口  │  Skill 接口        │
│  ToolRegistry│  SkillRegistry    │
│  MCPAdapter  │  WorkflowEngine   │
└─────────────────────────────────┘
```

### LLM 接入层

`LLMProvider` 定义统一的 `chat()` 和 `chatStream()` 接口，所有模型适配器实现此接口即可接入。

当前使用 `MockLLMProvider` 返回模拟响应，便于前端联调和演示。生产环境可替换为真实模型实现，无需改动上层代码。

### 推理层

**意图路由**：用户请求进入后，先通过轻量 LLM 分类器判断任务类型：
- 匹配到 Skill → 按预设流程执行
- 复杂多步任务 → PAE 引擎
- 一般问答 → ReAct 引擎

**ReAct**：Thought → Action → Observation 循环，适合实时问答。每轮 LLM 决定是调用工具还是直接回答，工具结果回写上下文进入下一轮。

**PAE**：Plan → Act → Evaluate，适合多步骤任务。先拆解步骤计划，再逐步执行并评估每步结果，失败时终止或跳过可选步骤。

### 能力层

**Tool**：最细粒度的业务能力单元，每个 Tool 封装一个具体操作（查订单、查物流等）。通过 `ToolDefinition` 暴露元数据供 LLM 识别调用时机。

**Skill**：多个 Tool 的组合编排，通过 WorkflowEngine 按步骤执行，处理跨工具的数据流转和异常。

**MCP 协议适配**：`MCPAdapter` 实现 Tool 接口，将本地工具调用转换为 MCP 协议格式，支持远程工具注册与调用。

## 三阶段执行链路

```
执行前                     执行中                          执行后
┌──────┐  ┌──────┐  ┌──────┐  ┌──────────┐  ┌──────┐  ┌──────┐
│Memory│→ │ RAG  │→ │Prompt│→ │ 意图路由  │→ │ Tool │→ │记忆  │
│加载   │  │ 检索  │  │注入  │  │→推理引擎   │  │ 调用 │  │沉淀  │
└──────┘  └──────┘  └──────┘  └──────────┘  └──────┘  └──────┘
```

1. **执行前**：ShortTermMemory 加载对话上下文，RAG 检索领域知识，组装 system prompt
2. **执行中**：意图路由决定推理模式，ReAct/PAE 循环执行，ToolRegistry 调用业务工具
3. **执行后**：LongTermMemory 通过 mem0 沉淀用户偏好与事实

## 工具清单

| 工具 | 类名 | 输入 | 输出 |
|------|------|------|------|
| 订单查询 | OrderTool | userId, orderId | 订单详情、订单列表 |
| 商品推荐 | ProductTool | query, limit | 推荐商品列表 |
| 物流追踪 | LogisticsTool | userId, orderId | 物流轨迹、承运商 |

## 异常处理策略

- **LLM 调用失败**：降级到关键词意图匹配
- **工具执行异常**：异常信息回写 Observation，LLM 决定重试或告知用户
- **RAG 服务不可用**：跳过知识注入，仅使用模型自身知识
