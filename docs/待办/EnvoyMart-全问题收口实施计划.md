# EnvoyMart 全问题分阶段路线图（总计划）

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` or `superpowers:subagent-driven-development` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 规划 Q1–Q42、O1–O44、S01–S19 中除 Multi-Agent（Q42/O11）之外的全部问题、缺陷和工程级不足；当前执行阶段只启动 T1。

**Architecture:** 采用单 Agent、单显式状态图架构，不拆 Multi-Agent。按“身份与状态 → 写操作契约 → Agent 能力 → 记忆/上下文 → RAG/知识 → 观测与性能 → Deep Research/受控推理 → 全链路验收”的依赖顺序实施；所有增强能力都必须由确定性契约、权限、预算、审批、证据门和 trace 约束。

**Tech Stack:** Java 21、Spring Boot 4.1.1、LangChain4j 1.20.0、LangGraph4j 1.8.27、MCP Java SDK 2.0.1、Redis、Milvus、Neo4j、RabbitMQ、Vue 3、Vite、pnpm、Maven。

> 当前执行入口是 `EnvoyMart/docs/待办/T1-平台全功能与复杂Agent一次性收口实施计划.md`。本文保留全量问题的长期分阶段路线；T2 使用待优化清单，T3 使用项目深挖问答。Multi-Agent 是唯一永久排除项。

---

## 一、范围和完成定义

### 纳入范围

- Q1–Q42 的所有缺陷和工程不足，Q42 中 Multi-Agent 仅记录为明确不做的架构边界。
- O1–O44 全部条目。
- S01–S19 全部补充条目。
- Deep Research 的异步任务、检索扇出、来源管理、综合写作和质量评测。
- 受控 ToT/LATS 的有限计划候选、确定性过滤、预算约束和可回溯评分。

### 不纳入范围

- Multi-Agent、子 Agent、peer 协作、supervisor 拆分均不实现。
- 不以新增 Multi-Agent 作为解决权限、上下文或团队边界问题的方式。

### 全局不变量

1. 身份、租户、会话和审批权限只能由服务端上下文决定，不能由模型参数或记忆决定。
2. 高危写操作必须有稳定 operationId、明确幂等策略、审批载荷和最终状态证据。
3. `$N.field` 引用必须形成真实数据依赖，引用失败不能把占位串传入写工具。
4. 工具结果必须区分成功、无数据、业务失败、传输失败和状态未知。
5. 检索、图谱、评测、记忆和 Agent 状态的降级都必须可观测，不能把失败伪装成空结果。
6. 所有真实模型评测人工触发且只触发一次；页面访问和脚本验收不得自动调用模型。
7. 每批改动必须运行该批确定性测试、相邻回归和端到端脚本，并同步材料。

## 二、工作流和依赖

| 批次 | 名称 | 主要覆盖 | 前置条件 | 交付证据 |
|---|---|---|---|---|
| B0 | 基线与契约冻结 | 全部条目 | 无 | 基线报告、覆盖矩阵、版本清单 |
| B1 | 身份、状态、并发与删除 | O4/O23/O44/S02/S19 | B0 | 隔离、并发、删除、重启测试 |
| B2 | 审批、幂等、计划 DSL、失败语义 | O1/O13/O14/O25/O30/S01/S07/S12/S16/S22 | B1 | 高危契约、引用、参数、重试测试 |
| B3 | 消息反馈、Agent 工具和平台能力 | O2/O9/O16/O17/O18/O19/O29/S03/S14 | B2 | 工具契约、反馈、记忆和副作用验收 |
| B4 | 意图、上下文、Prompt、预算和记忆质量 | O3/O5/O6/O8/O10/O12/O20/O21/O22/S03/S04/S05/S15/S21 | B1/B2 | 评测集、成本指标、记忆质量报告 |
| B5 | RAG、知识更新、图谱、解析和检索性能 | O28/O31/O32/O33/O34/O35/O36/O37/O38/S09/S10/S11/S13/S15/S20 | B2/B4 | 检索 A/B、重建一致性、图谱诊断、解析评测 |
| B6 | 观测、服务安全、并发性能和 MCP | O7/O24/O26/O30/O40/O41/O42/O43/S06/S07/S08/S14/S16/S22 | B1/B2/B5 | 指标、告警、压力、PII、内部认证证据 |
| B7 | Deep Research 和受控推理 | Q38/Q39/S17 | B2/B5/B6 | 异步研究、候选计划、预算和引用验收 |
| B8 | 统一回归、真实评测和材料收口 | 全部（排除 Q42/O11） | B1–B7 | 全量脚本、单测、真实快照、材料一致 |

## 三、任务计划

### Task 0：建立基线、覆盖矩阵和版本契约

**Files:**

- Create: `EnvoyMart/frontend/scripts/fixtures/platform-feature-matrix.json`
- Create: `EnvoyMart/frontend/scripts/fixtures/complex-agent-dialogues.json`
- Create: `EnvoyMart/frontend/scripts/fixtures/failure-recovery-scenarios.json`
- Create: `EnvoyMart/docs/待办/EnvoyMart-全问题收口覆盖矩阵.md`
- Modify: `EnvoyMart/frontend/scripts/verify-all.mjs`
- Modify: `EnvoyMart/frontend/scripts/lib/verify-util.mjs`
- Modify: `EnvoyMart/docs/待办/EnvoyMart-问题与改动方案总清单.md`

- [ ] 固定当前 commit、Java/Maven/pnpm/Node 版本、服务端口、工具清单、页面路由、评测快照和中间件健康状态。
- [ ] 为 Q/O/S 每个条目建立映射：代码入口、测试、脚本、日志/指标断言、最终状态和材料文件。
- [ ] 能力矩阵使用 `UI_ONLY`、`API_ONLY`、`AGENT_READ`、`AGENT_WRITE_CONFIRM`、`UNAVAILABLE`，并把 Multi-Agent 标记为 `OUT_OF_SCOPE`。
- [ ] 所有复杂场景声明初始数据、消息序列、期望工具 DAG、引用、确认点、失败路径和最终状态。
- [ ] 运行基线：

```powershell
cd EnvoyMart\backend
mvn -o test
cd ..\frontend
pnpm type-check
pnpm build
pnpm lint
```

Expected: 记录当前通过项和已知失败项；不得把基线红灯伪装成新改动回归。

### Task 1：身份隔离、会话互斥、现场生命周期

**Files:**

- Modify: `EnvoyMart/backend/agent-core/src/main/java/yumefusaka/envoymart/agent/memory/ShortTermMemoryStore.java`
- Modify: `EnvoyMart/backend/ai-service/src/main/java/yumefusaka/envoymart/aiservice/memory/RedisShortTermMemoryStore.java`
- Modify: `EnvoyMart/backend/agent-core/src/main/java/yumefusaka/envoymart/agent/core/task/SessionContextStore.java`
- Modify: `EnvoyMart/backend/ai-service/src/main/java/yumefusaka/envoymart/aiservice/memory/RedisSessionContextStore.java`
- Modify: `EnvoyMart/backend/agent-core/src/main/java/yumefusaka/envoymart/agent/core/task/TaskCheckpoint.java`
- Modify: `EnvoyMart/backend/ai-service/src/main/java/yumefusaka/envoymart/aiservice/memory/RedisTaskStateStore.java`
- Modify: `EnvoyMart/backend/ai-service/src/main/java/yumefusaka/envoymart/aiservice/controller/ChatSessionController.java`
- Modify: `EnvoyMart/backend/ai-service/src/main/java/yumefusaka/envoymart/aiservice/service/impl/AiAssistantServiceImpl.java`
- Test: `EnvoyMart/backend/ai-service/src/test/java/yumefusaka/envoymart/aiservice/memory/RedisShortTermMemoryStoreDegradeTest.java`
- Test: `EnvoyMart/backend/ai-service/src/test/java/yumefusaka/envoymart/aiservice/memory/ChatHistoryDeletionTest.java`
- Create: session isolation/concurrency/delete integration tests under `EnvoyMart/backend/ai-service/src/test/java/yumefusaka/envoymart/aiservice/memory/`

- [ ] 将 STM 键改为 `stm:{userId}:{sessionId}`，读取时校验条目归属，旧键自然过期且不迁移。
- [ ] 为 `(userId, sessionId)` 增加 Redis 锁或版本 CAS；覆盖普通消息、审批执行、checkpoint 恢复和删除并发。
- [ ] 增加 `SessionLifecycle` 清理入口，统一删除历史、STM、session context、checkpoint 和墓碑。
- [ ] 为 `TaskCheckpoint` 增加 `schemaVersion`，不兼容时安全丢弃并输出结构化原因。
- [ ] 验证 Redis 失败语义：STM 可降级为空并计数；历史、删除和 WAITING_USER checkpoint 失败必须显式失败。
- [ ] 运行：

```powershell
cd EnvoyMart\backend
mvn -o -pl ai-service,agent-core -am test
cd ..\frontend
node scripts/verify-memory.mjs
node scripts/verify-permission-matrix.mjs
```

Expected: 同 sessionId 跨用户无法读取；并发请求不会覆盖状态；删除后不能恢复旧现场。

### Task 2：高危审批、幂等、计划 DSL 和失败语义

**Files:**

- Modify: `EnvoyMart/backend/agent-core/src/main/java/yumefusaka/envoymart/agent/tool/ToolDefinition.java`
- Modify: `EnvoyMart/backend/agent-core/src/main/java/yumefusaka/envoymart/agent/tool/ToolRegistry.java`
- Modify: `EnvoyMart/backend/agent-core/src/main/java/yumefusaka/envoymart/agent/tool/ApprovalTokens.java`
- Modify: `EnvoyMart/backend/agent-core/src/main/java/yumefusaka/envoymart/agent/core/AgentGraph.java`
- Modify: `EnvoyMart/backend/agent-core/src/main/java/yumefusaka/envoymart/agent/llm/PlanStep.java`
- Modify: `EnvoyMart/backend/ai-service/src/main/java/yumefusaka/envoymart/aiservice/tool/Downstream.java`
- Modify: downstream write clients/services that accept Agent operation IDs
- Test: `EnvoyMart/backend/agent-core/src/test/java/yumefusaka/envoymart/agent/tool/ApprovalTokensTest.java`
- Test: `EnvoyMart/backend/agent-core/src/test/java/yumefusaka/envoymart/agent/core/AgentGraphApprovalTest.java`
- Test: `EnvoyMart/backend/agent-core/src/test/java/yumefusaka/envoymart/agent/core/AgentGraphReplanTest.java`
- Test: `EnvoyMart/backend/ai-service/src/test/java/yumefusaka/envoymart/aiservice/tool/DownstreamTest.java`

- [ ] 扩展工具元数据，声明 confirmation、幂等策略、operationId 字段和令牌重放策略；高危工具未声明时启动失败。
- [ ] 为每个写步骤生成稳定 operationId；重规划、确认重试、传输重试复用同一 operationId。
- [ ] 非幂等工具增加 Redis/数据库一次性消费；幂等工具由下游唯一约束或幂等表裁决。
- [ ] 计划归一化时从 `$N.field` 和 `$N[i].field` 自动补 `dependsOn`；引用自身/未来步骤直接拒绝。
- [ ] 明确 optional 前置失败：被引用步骤失败时，引用方阻塞，不传占位串。
- [ ] plan 阶段和 act 解析后分别执行参数 schema 校验；错误消息包含未知字段、缺失字段和修正方向。
- [ ] 写工具结果必须返回权威对象；普通回答完成态与成功写工具逐项对账，未执行不得声称完成。
- [ ] 将传输重试、业务码重试、状态未知和写操作不重试固定为不同契约；按工具重要性配置预算。
- [ ] 运行：

```powershell
cd EnvoyMart\backend
mvn -o -pl agent-core,ai-service,order-service,product-service -am test
cd ..\frontend
node scripts/verify-mcp-confirm.mjs
node scripts/verify-approval-chain.mjs
node scripts/verify-downstream-retry.mjs
```

Expected: 双商品、多步骤引用、重复确认、网络重试、非幂等令牌重放均无错误副作用。

### Task 3：消息身份、反馈、Agent 全平台工具和能力矩阵

**Files:**

- Modify: `EnvoyMart/backend/ai-service/src/main/java/yumefusaka/envoymart/aiservice/memory/ChatHistoryStore.java`
- Modify: `EnvoyMart/backend/ai-service/src/main/java/yumefusaka/envoymart/aiservice/memory/BadCaseStore.java`
- Modify: `EnvoyMart/backend/ai-service/src/main/java/yumefusaka/envoymart/aiservice/service/impl/AiAssistantServiceImpl.java`
- Modify: `EnvoyMart/backend/ai-service/src/main/java/yumefusaka/envoymart/aiservice/controller/ChatSessionController.java`
- Modify: `EnvoyMart/frontend/src/api/ai.ts`
- Modify: `EnvoyMart/frontend/src/types/models.ts`
- Modify: `EnvoyMart/frontend/src/components/ai/ChatMessageList.vue`
- Modify: `EnvoyMart/frontend/src/views/AiAssistantView.vue`
- Modify: `EnvoyMart/frontend/src/views/admin/AdminBadCaseView.vue`
- Create/Modify: `EnvoyMart/backend/ai-service/src/main/java/yumefusaka/envoymart/aiservice/tool/CartUpdateTool.java`
- Create/Modify: `EnvoyMart/backend/ai-service/src/main/java/yumefusaka/envoymart/aiservice/tool/CartRemoveTool.java`
- Create/Modify: `EnvoyMart/backend/ai-service/src/main/java/yumefusaka/envoymart/aiservice/tool/CouponReceiveTool.java`
- Create/Modify: `EnvoyMart/backend/ai-service/src/main/java/yumefusaka/envoymart/aiservice/tool/TicketCreateTool.java`
- Modify: `EnvoyMart/backend/ai-service/src/main/java/yumefusaka/envoymart/aiservice/config/McpServerConfig.java`
- Test: `EnvoyMart/backend/ai-service/src/test/java/yumefusaka/envoymart/aiservice/memory/ChatHistoryStoreTest.java`
- Test: `EnvoyMart/backend/ai-service/src/test/java/yumefusaka/envoymart/aiservice/tool/WriteToolConfirmationTest.java`

- [ ] 服务端为每轮生成权威 `turnId`、`userMessageId`、`assistantMessageId`，流式完成事件、历史、重生成、点踩、审批和撤销全部复用。
- [ ] 点踩选择扩展到完整会话，服务端验证消息属于当前用户和当前 session；审核、标注、撤销状态可恢复。
- [ ] 补齐购物车、优惠券、工单、售后和结算工具；每个工具标明 read/write/confirmation/idempotency/authority result。
- [ ] `ProductTool` 的结构化结果携带 SPU、SKU、规格、价格和可购买状态；多商品写操作禁止依赖正文正则。
- [ ] MCP instructions 从注册表和流程定义生成；工具数量、能力矩阵、项目总览和问答材料统一更新。
- [ ] 动态 MCP client、tools/list 白名单和角色可见性纳入本批；Multi-Agent 不得作为替代方案。
- [ ] 运行：

```powershell
cd EnvoyMart\backend
mvn -o -pl ai-service,agent-core -am test
cd ..\frontend
pnpm type-check
pnpm build
node scripts/verify-chat-feedback.mjs
node scripts/verify-platform-matrix.mjs
```

Expected: 消息 ID 全链路一致；每个 Agent 工具有真实权限和最终状态；没有工具的能力不会被模型假装完成。

### Task 4：记忆、上下文、意图和 Prompt 质量

**Files:**

- Modify: `EnvoyMart/backend/agent-core/src/main/java/yumefusaka/envoymart/agent/memory/EpisodicMemory.java`
- Modify: `EnvoyMart/backend/agent-core/src/main/java/yumefusaka/envoymart/agent/memory/ContextBudget.java`
- Modify: `EnvoyMart/backend/agent-core/src/main/java/yumefusaka/envoymart/agent/memory/PerceptualMemory.java`
- Modify: `EnvoyMart/backend/ai-service/src/main/java/yumefusaka/envoymart/aiservice/memory/LlmMemoryConsolidator.java`
- Modify: `EnvoyMart/backend/ai-service/src/main/java/yumefusaka/envoymart/aiservice/memory/RedisProfileRepository.java`
- Modify: `EnvoyMart/backend/agent-core/src/main/java/yumefusaka/envoymart/agent/flow/IntentRouter.java`
- Modify: `EnvoyMart/backend/agent-core/src/main/java/yumefusaka/envoymart/agent/core/task/ClarificationTracker.java`
- Modify: `EnvoyMart/backend/agent-core/src/main/java/yumefusaka/envoymart/agent/core/task/IntentSwitchDetector.java`
- Create: prompt registry/version metadata and memory user-facing API/types
- Create: `EnvoyMart/frontend/scripts/verify-intent.mjs`
- Create: `EnvoyMart/frontend/scripts/verify-context-budget.mjs`
- Test: existing memory, intent, context and prompt tests under `EnvoyMart/backend/agent-core/src/test/java/`

- [ ] 增加记忆来源消息锚点、时间、importance、访问强化和类型/时间衰减；支持单条、槽位和用户级删除/纠错。
- [ ] 接通页面上下文到 `PerceptualMemory`，MemoryTrace 在前端展示并进入验收脚本。
- [ ] 将沉淀触发改为价值信号 + 异步最终一致，保证会话级写入幂等、积压可观测。
- [ ] 增加记忆质量评测：召回准确率、错误记忆率、否定/限定词保真、开关对照实验。
- [ ] 增加会话话题栈、澄清硬上限和意图纠正回流；改写/路由/needRetrieval 合并或预筛并用 TokenLedger 对账。
- [ ] 建立 PromptRegistry、promptVersion、modelVersion、corpusVersion 和留出集；所有评测结果绑定版本。
- [ ] ContextBudget 记录估算 token、实际 usage、分段占比和裁剪原因；按句子/段落结构降级。
- [ ] 运行：

```powershell
cd EnvoyMart\backend
mvn -o -pl agent-core,ai-service -am test
cd ..\frontend
node scripts/verify-memory.mjs
node scripts/verify-intent.mjs
node scripts/verify-context-budget.mjs
```

Expected: 记忆可见、可纠错、可删除；意图和澄清有夹具；上下文成本和版本可回溯。

### Task 5：RAG、知识更新、冲突、图谱和文档解析

**Files:**

- Modify: `EnvoyMart/backend/agent-core/src/main/java/yumefusaka/envoymart/agent/rag/HybridRetriever.java`
- Modify: `EnvoyMart/backend/agent-core/src/main/java/yumefusaka/envoymart/agent/rag/KnowledgePrompt.java`
- Modify: `EnvoyMart/backend/agent-core/src/main/java/yumefusaka/envoymart/agent/rag/FrontMatterParser.java`
- Modify: `EnvoyMart/backend/ai-service/src/main/java/yumefusaka/envoymart/aiservice/rag/MilvusVectorStore.java`
- Modify: `EnvoyMart/backend/ai-service/src/main/java/yumefusaka/envoymart/aiservice/rag/GraphEvidenceRetriever.java`
- Modify: `EnvoyMart/backend/ai-service/src/main/java/yumefusaka/envoymart/aiservice/knowledge/KnowledgeIndexer.java`
- Modify: `EnvoyMart/backend/knowledge-service/src/main/java/yumefusaka/envoymart/knowledgeservice/service/impl/KnowledgeDocumentServiceImpl.java`
- Modify: `EnvoyMart/backend/knowledge-service/src/main/java/yumefusaka/envoymart/knowledgeservice/graph/TripleValidator.java`
- Modify: `EnvoyMart/backend/knowledge-service/src/main/java/yumefusaka/envoymart/knowledgeservice/graph/GraphService.java`
- Modify: `EnvoyMart/backend/knowledge-service/src/main/java/yumefusaka/envoymart/knowledgeservice/entity/GraphBuildFailureEntity.java`
- Create: parser/OCR/structured document ingestion module and graph/index batch metadata migration
- Test: `HybridRetrieverTest.java`, `MilvusVectorStoreTest.java`, `TripleValidatorTest.java`, `GraphServiceRecallTest.java`, `GraphServiceCoverageTest.java`

- [ ] 三路检索并行执行，固定向量→词法→图谱汇合顺序，逐片比较并行前后输出一致。
- [ ] embedding 改为受限批量调用，定义失败断点和重试；Milvus 显式声明索引类型、度量、一致性和生效配置。
- [ ] `needRetrieval` 增加必须检索/必须跳过夹具；WEAK/NONE 自动执行第二组确定性补检索；NONE 增加代码级硬拒答。
- [ ] 增加门判定与最终回答抗命率、图谱覆盖率、索引滞后度、重排降级率和参数来源指标。
- [ ] 知识更新改为事件化 + 对账；全量重建采用新集合/版本指针原子切换，pending 请求不得静默丢弃。
- [ ] 统一所有导入路径的 front matter 校验，增加 `valid_from/valid_until`，冲突按权威度先由代码裁决，同档再交模型。
- [ ] 图谱诊断按批次保存原始候选、规范化字段、quote、原因和可重试性；实体链接、别名、覆盖率和容量纳入评测。
- [ ] 增加 PDF、扫描件、OCR、表格结构还原和解析质量评测；低置信 OCR 不得进入图谱事实。
- [ ] 运行：

```powershell
cd EnvoyMart\backend
mvn -o -pl agent-core,ai-service,knowledge-service -am test
cd ..\frontend
node scripts/verify-agent-retrieval.mjs
node scripts/verify-knowledge-ui.mjs
node scripts/verify-eval-evidence.mjs
```

Expected: 检索结果一致、索引更新可对账、图谱失败可解释、旧文档不会绕过时效过滤、坏文档不会静默入库。

### Task 6：服务安全、PII、并发性能和可观测性

**Files:**

- Modify: `EnvoyMart/backend/common/src/main/java/yumefusaka/envoymart/common/web/InternalCallFilter.java`
- Modify: `EnvoyMart/backend/ai-service/src/main/java/yumefusaka/envoymart/aiservice/security/McpCallGuard.java`
- Modify: `EnvoyMart/backend/ai-service/src/main/java/yumefusaka/envoymart/aiservice/security/McpAuthFilter.java`
- Modify: `EnvoyMart/backend/ai-service/src/main/java/yumefusaka/envoymart/aiservice/config/McpServerConfig.java`
- Modify: `EnvoyMart/backend/ai-service/src/main/java/yumefusaka/envoymart/aiservice/service/impl/AiAssistantServiceImpl.java`
- Create: observation/metrics, alert and redaction components under `EnvoyMart/backend/ai-service/src/main/java/yumefusaka/envoymart/aiservice/observability/`
- Create: `EnvoyMart/frontend/scripts/verify-concurrency.mjs`
- Modify: service DB configuration and internal token rotation configuration
- Test: `McpGuardTest.java`, `McpApprovalAuthorizationTest.java`, `McpServerConfigTest.java`

- [ ] 内部端点统一要求服务身份；实现双密钥轮换窗口、服务级 audience 和过期校验。
- [ ] 数据库运行账号、迁移账号、读写权限分离；对关键查询增加租户条件拦截或契约测试。
- [ ] 日志、trace、工具输出、历史和前端轨迹按路径脱敏；功能路径保留真实收货信息。
- [ ] 增加图节点 Observation、首 token/整体延迟、P95/P99、SSE 取消传播、LLM 熔断、舱壁、有界队列和快速失败。
- [ ] MCP 计数进入 Counter，评测快照保留历史趋势，补 readyz/liveness、JSON 日志、告警动作和 trace exporter。
- [ ] 动态 MCP client 只允许白名单工具，并按角色过滤工具表；Multi-Agent 不得作为权限隔离方案。
- [ ] 运行：

```powershell
cd EnvoyMart\backend
mvn -o -pl common,agent-core,ai-service -am test
cd ..\frontend
node scripts/verify-permission-matrix.mjs
node scripts/verify-request-id.mjs
node scripts/verify-concurrency.mjs
```

Expected: 无凭证直连失败、完整 PII 不出观测面、并发超限快速失败、告警和 trace 能定位节点与下游。

### Task 7：评测快照、Bad Case、参数消融和质量闭环

**Files:**

- Modify: `EnvoyMart/backend/ai-service/src/main/java/yumefusaka/envoymart/aiservice/eval/ProductionRetrievalEvalService.java`
- Modify: `EnvoyMart/backend/ai-service/src/main/java/yumefusaka/envoymart/aiservice/eval/GroundingLiveEvalService.java`
- Modify: `EnvoyMart/backend/ai-service/src/main/java/yumefusaka/envoymart/aiservice/controller/GroundingEvalController.java`
- Modify: `EnvoyMart/backend/ai-service/src/main/java/yumefusaka/envoymart/aiservice/memory/BadCaseStore.java`
- Modify: `EnvoyMart/backend/agent-core/src/main/java/yumefusaka/envoymart/agent/rag/GroundingEvaluator.java`
- Modify: `EnvoyMart/backend/agent-core/src/main/java/yumefusaka/envoymart/agent/rag/RetrievalEvaluator.java`
- Modify: `EnvoyMart/frontend/src/views/KnowledgeEvalView.vue`
- Modify: `EnvoyMart/frontend/src/views/AnswerQualityView.vue`
- Modify: `EnvoyMart/frontend/src/views/admin/AdminEvaluationView.vue`
- Modify: `EnvoyMart/frontend/scripts/verify-eval-ui.mjs`
- Modify: `EnvoyMart/frontend/scripts/verify-answer-quality-ui.mjs`
- Create: eval snapshot DTO/store, holdout fixtures, parameter provenance and Bad Case replay fixtures

- [ ] 快照使用专用 DTO、原子替换、历史 runId、版本兼容和损坏状态；页面只读，不触发模型。
- [ ] 基础集、Bad Case 追加集、holdout 集分开运行、分开计分；失败样本留在分母并记录失败阶段。
- [ ] 每道 guardrail 记录命中、误杀、放行和抗命率；评测数据绑定 model/prompt/corpus/pipeline 版本。
- [ ] 记录 topK、阈值、RRF k、重排超时、扩写变体的来源等级和对照实验结果；补联合消融边界。
- [ ] 运行：

```powershell
cd EnvoyMart\backend
mvn -o -pl agent-core,ai-service -am test
cd ..\frontend
node scripts/verify-eval-ui.mjs
node scripts/verify-answer-quality-ui.mjs
node scripts/verify-eval-evidence.mjs
```

Expected: 页面刷新只读快照；坏快照、版本不兼容、失败样本和追加集均有明确状态。

### Task 8：Deep Research 和受控 ToT/LATS

**Files:**

- Create: async research task state and API under `EnvoyMart/backend/agent-core/src/main/java/yumefusaka/envoymart/agent/research/`
- Create: research orchestration and source registry under `EnvoyMart/backend/ai-service/src/main/java/yumefusaka/envoymart/aiservice/research/`
- Modify: `EnvoyMart/backend/agent-core/src/main/java/yumefusaka/envoymart/agent/core/AgentGraph.java`
- Modify: `EnvoyMart/backend/agent-core/src/main/java/yumefusaka/envoymart/agent/rag/GroundingEvaluator.java`
- Create: candidate-plan scorer and bounded search tests under `EnvoyMart/backend/agent-core/src/test/java/yumefusaka/envoymart/agent/research/`
- Create: research UI and scripts under `EnvoyMart/frontend/src/views/` and `EnvoyMart/frontend/scripts/`

- [ ] 研究任务异步化，具备 taskId、进度、取消、恢复、超时、预算和幂等；不占用普通聊天状态。
- [ ] 多查询改写、并行多源检索、去重归并、来源权威度/时效/冲突裁决和章节大纲综合全部保留引用链。
- [ ] 候选计划最多生成 2–3 个，经确定性权限/依赖/schema/预算过滤后再评分；搜索不能绕过审批和证据门。
- [ ] 评测研究完成率、引用覆盖、来源冲突、耗时、token 成本和取消恢复；失败输出可定位到任务阶段。
- [ ] 明确不引入 Multi-Agent，研究子任务仍由同一 AgentGraph 在统一预算和权限下执行。

### Task 9：全平台复杂场景回放和端到端验收

**Files:**

- Create: `EnvoyMart/backend/agent-core/src/test/resources/complex-dialogues/*.json`
- Create: `EnvoyMart/backend/agent-core/src/test/java/yumefusaka/envoymart/agent/core/AgentComplexDialogueContractTest.java`
- Create: `EnvoyMart/backend/ai-service/src/test/java/yumefusaka/envoymart/aiservice/tool/AgentCommerceToolContractTest.java`
- Create: `EnvoyMart/frontend/scripts/verify-platform-matrix.mjs`
- Create: `EnvoyMart/frontend/scripts/verify-complex-agent-dialogues.mjs`
- Create: `EnvoyMart/frontend/scripts/verify-agent-side-effects.mjs`
- Create: `EnvoyMart/frontend/scripts/verify-failure-recovery.mjs`
- Create: `EnvoyMart/frontend/scripts/verify-persistence-restart.mjs`
- Create: `EnvoyMart/frontend/scripts/verify-permission-matrix.mjs`

- [ ] 覆盖商品检索、双商品加购、购物车增删改、优惠券、结算、订单、物流、售后、工单、知识、图谱和研究任务。
- [ ] 覆盖“第一个/第二个/刚才那件/另一份”、中途纠正、确认取消、重复确认、服务重启、下游超时、无数据和状态未知。
- [ ] 所有写场景重新读取数据库/Redis/MQ/业务 API 权威状态；禁止只断言助手文本。
- [ ] 每个 case 输出 caseId、requestId、工具 DAG、参数来源、确认状态、最终状态和失败阶段。
- [ ] 运行：

```powershell
cd EnvoyMart\frontend
node scripts/verify-all.mjs --slow
```

Expected: 所有纳入矩阵的场景有结果；任何写操作都有最终状态证据；Multi-Agent 不出现在工具或测试矩阵中。

### Task 10：材料、文档和最终真实评测收口

**Files:**

- Modify: `EnvoyMart/docs/项目总览.md`
- Modify: `EnvoyMart/docs/平台改造规划.md`
- Modify: `EnvoyMart/docs/待办/待办总表.md`
- Modify: `EnvoyMart/docs/待办/EnvoyMart-问题与改动方案总清单.md`
- Modify: `EnvoyMart-项目深挖问答.md`
- Modify: `EnvoyMart-待优化清单.md`
- Modify: `JavaAI全栈开发.md`（仅项目经历）
- Modify: `E:\Project\面试训练\AGENTS.md`
- Modify: `E:\Project\面试训练\下阶段规划与会话交接.md`

- [ ] 用代码和最终验证数字更新工具数、高危数、Agent 能力、评测口径、快照状态、记忆隔离和已知短板。
- [ ] Multi-Agent 统一写成明确不做；禁止材料暗示未来会拆分。
- [ ] 确定性测试和 UI/API 验收全绿后，人工触发一次检索评测、一次回答评测、一次复杂 Agent 场景集和一次图谱重建。
- [ ] 重启 ai-service、knowledge-service 和前端，验证快照、历史、Bad Case、图谱诊断和研究任务状态可读。
- [ ] 检查运行期快照、日志、截图、测试账号、密钥和临时产物不进入仓库。

## 四、统一验收门槛

### 后端

- `mvn -o test` 全模块 BUILD SUCCESS，0 失败。
- 高危工具元数据、审批令牌、operationId、引用依赖、schema、重试和失败语义均有单测。
- 身份隔离、会话互斥、删除清理、checkpoint 版本和跨实例状态均有回归。
- RAG、图谱、评测快照、研究任务和工具执行链路均能通过 requestId/trace 回溯。

### 前端和脚本

- `pnpm type-check`、`pnpm build`、`pnpm lint` 通过。
- 页面覆盖加载、空态、错误、权限、只读快照、损坏快照、研究进度和取消状态。
- 旧评测选择器、旧数字和旧工具名全部清理。

### 生产级行为

- 任何重复确认、网络重试、服务重启或重规划都不能产生重复写副作用。
- 任何失败不能被伪装成空结果、未运行、没有依据或已完成。
- 完整手机号、地址、内部 token、异常栈和密钥不出观测面。
- Multi-Agent 不进入代码、工具表、MCP、测试矩阵或材料。

## 五、实施纪律

- 每个批次开始前先运行上一批基线；失败先归类为需求、前提、实现或验证问题，再决定回流层级。
- 每个批次完成后运行本批测试、相邻回归和对应 `verify-*`；没有证据不能标记完成。
- 需要删除、迁移、安装依赖、修改环境或提交代码时，按项目规则单独确认影响范围；本计划不授权提交和推送。
- 真实模型调用只在 B8 人工明确触发，失败样本和用量必须保留。
