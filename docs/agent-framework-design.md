# Agent 框架设计说明

## 设计目标

把"业务可控"和"模型自主"分开：

- **模型接入、工具调用循环、MCP 协议**交给 Spring AI，不重复造轮子；
- **推理模式选择、上下文预算、工具编排、记忆、检索、降级**由自研 `agent-core` 负责，因为这些是框架没覆盖、且生产上真正出问题的地方。

## 分层

```
┌──────────────────────────────────────────────────────────────┐
│  接入层（ai-service）                                          │
│  SpringAiLLMProvider · MilvusVectorStore · DashScopeReranker  │
│  LlmMemoryConsolidator · ToolRegistryCallbackProvider(MCP)    │
├──────────────────────────────────────────────────────────────┤
│  编排层（agent-core，纯 Java，无 Spring 依赖）                  │
│  Agent（意图路由） · ReActEngine · PAEEngine · ContextManager   │
│  ToolRegistry · SkillRegistry/WorkflowEngine · Memory · RAG    │
├──────────────────────────────────────────────────────────────┤
│  基础设施                                                      │
│  Spring AI ChatModel/EmbeddingModel · Milvus · Redis · 业务服务 │
└──────────────────────────────────────────────────────────────┘
```

依赖方向单向：编排层只依赖自己定义的接口（`LLMProvider`、`VectorStore`、`EmbeddingService`、`Reranker`、`Memory`、`Tool`），接入层提供实现。

## 关键契约

| 接口 | 职责 | 实现 |
|------|------|------|
| `LLMProvider` | 模型对话与多步规划 | `SpringAiLLMProvider` / `MockLLMProvider`（无 Key 降级） |
| `Tool` | 单个业务能力 | `OrderTool` / `LogisticsTool` / `ProductTool` |
| `VectorStore` | 文本入、切片出的语义检索 | `MilvusVectorStore` / `InMemoryVectorStore` |
| `Reranker` | 召回结果精排 | `DashScopeReranker` / `Reranker.NOOP` |
| `Memory` | 短期窗口与长期语义召回 | `ShortTermMemory` / `LongTermMemory` |
| `MemoryConsolidator` | 从对话中抽取事实 | `LlmMemoryConsolidator` |

## 执行三阶段

```
执行前                                执行中                        执行后
Memory.recall ┐                                              ┌ MemoryConsolidator.extract
RAG.retrieve  ├→ systemPrompt → 意图路由 → 工具执行 → 回答合成 ─┤        ↓
              ┘                 (Skill/PAE/ReAct)             └ LongTermMemory.add
```

1. **执行前**：RAG 混合检索 + 长期记忆语义召回，一起组装进 system prompt
2. **执行中**：按 Skill → PAE → ReAct 的优先级选策略；工具执行结果记录轨迹
3. **执行后**：LLM 抽取跨会话成立的事实/偏好，写入长期记忆向量库

## 推理模式

| 模式 | 触发条件 | 特点 |
|------|---------|------|
| **Skill** | 命中已注册 Skill | 预定义工作流，确定性最高 |
| **Plan-and-Execute** | LLM 规划出非空的可执行计划 | 先拆步再执行，每步有评估；计划只允许引用已注册工具 |
| **ReAct** | 其余情况 | 基于 RAG 知识直接回答，带工具循环 |

**为什么 PAE 规划为空要回落 ReAct**：规划为空说明没有工具能帮上忙，这时应该让模型基于检索到的知识回答，而不是把"没有可用工具"当成最终答复。

## 可靠性护栏

| 风险 | 措施 |
|------|------|
| 死循环 | ReAct 对同一「工具 + 参数」计数，超阈值即中止并给出可读提示 |
| 无限迭代 | ReAct `maxIterations`、PAE `maxSteps` 双重上限 |
| 工具异常 | 异常信息结构化回写为 observation，不中断推理；PAE 中非可选步骤失败则终止计划 |
| 模型/工具链路异常 | Agent 整体兜底，降级为可读回复而非 500 |
| 外部依赖不可用 | 无 Key → Mock 模型；无 Milvus → 内存向量库；重排失败 → 保持原顺序 |

## 工具与 MCP

同一份 `ToolDefinition` 有两个消费方：

- `ToolRegistryToolCallback` 适配成 Spring AI 的 `ToolCallback`，供 Agent 链路调用；
- `ToolRegistryCallbackProvider` 把全部工具注册给 MCP Server，经 `/mcp`（Streamable HTTP）对外发布。

因此新增一个业务工具只需实现 `Tool` 并注册一次，Agent 与 MCP 客户端同时可用。

## 长期记忆

```
对话 → LlmMemoryConsolidator（LLM 抽取）
         ↓ 只抽跨会话成立的事实/偏好，不抽一次性意图
     LongTermMemory.add
         ↓ 写入独立向量库（与知识库隔离，避免污染检索）
     下一轮 Memory.recall → 注入 system prompt
```

记忆与知识分库是刻意的：两者混在同一个 collection 里，知识检索会被用户偏好污染。

## 异常处理策略

- **LLM 调用失败**：规划降级为关键词规则；对话降级为可读提示
- **工具执行异常**：异常信息回写 Observation，由模型决定重试或告知用户
- **RAG 不可用**：跳过知识注入，仅使用模型自身知识
- **记忆沉淀失败**：仅记录日志，不影响主链路
