# T1 平台全功能与复杂 Agent 一次性收口实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` or `superpowers:subagent-driven-development` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 彻底完成 `平台全功能与复杂Agent一次性收口计划.md` 的任务 1–8，覆盖平台功能、复杂 Agent、消息反馈、评测快照、图谱诊断、统一验收和一次真实模型收尾；不实施 Multi-Agent。

**Architecture:** 先建立覆盖矩阵和稳定契约，再修消息身份与持久化，再补 Agent 读写能力，随后收口评测快照和图谱诊断，最后运行确定性回放、全平台脚本和一次真实模型专项。写操作使用结构化参数、稳定 operationId、服务端审批令牌和最终状态复核；评测页面只读持久化快照。

**Tech Stack:** Java 21、Spring Boot 4.1.1、LangChain4j 1.20.0、LangGraph4j 1.8.27、MCP Java SDK 2.0.1、Redis、Milvus、Neo4j、RabbitMQ、Vue 3、Vite、pnpm、Maven。

---

## 一、T1 边界

### 纳入

- `平台全功能与复杂Agent一次性收口计划.md` 任务 1–8 全部内容。
- 当前用户反馈的双商品加购、点踩消息不存在、评测快照重启后消失三个缺陷。
- Agent 商品检索、购物车、优惠券、结算、订单、物流、售后、工单、知识检索和图谱问答能力。
- 复杂多轮对话、列表下标引用、步骤依赖、审批确认、失败恢复、重启恢复和权限边界。

### 排除

- Multi-Agent、子 Agent、peer 协作、supervisor 拆分。
- T2 `EnvoyMart-待优化清单.md` 和 T3 `EnvoyMart-项目深挖问答.md` 中未被本计划明确覆盖的工程优化。它们只在 T1 完成后启动。

### 完成定义

1. 任务 1–8 的代码、测试、脚本和文档全部落盘。
2. 所有写操作都有最终状态断言，不能只断言助手文本。
3. 所有复杂场景都有 caseId、requestId、工具 DAG、参数来源、确认状态和副作用结果。
4. 评测快照可跨重启读取，损坏/版本不兼容有明确错误态，页面访问不触发模型。
5. 图谱拒绝记录按批次隔离、可追溯到候选和原文。
6. 确定性测试、前端检查、全平台脚本全部通过后，检索、回答、复杂 Agent、图谱各人工触发一次真实收尾。

## 二、实施依赖

| 顺序 | 任务 | 依赖 | 交付 |
|---|---|---|---|
| T1-0 | 基线和覆盖矩阵 | 无 | 固定当前事实和 48 条以上复杂场景契约 |
| T1-1 | 消息身份、点踩、完整会话选择 | T1-0 | 权威消息 ID 全链路一致 |
| T1-2 | Agent 工具和计划数据通道 | T1-0 | 读写工具、列表下标、operationId、审批参数 |
| T1-3 | 评测快照和页面语义 | T1-0 | DTO、原子写入、重启读取、错误状态 |
| T1-4 | 图谱诊断和批次证据 | T1-0 | 原始候选、拒绝原因、批次聚合和详情 |
| T1-5 | 确定性复杂对话和后端契约 | T1-1/T1-2/T1-3/T1-4 | 不依赖模型随机性的回放门禁 |
| T1-6 | 全平台 API/UI/Agent 验收脚本 | T1-1/T1-2/T1-3/T1-4 | 权威状态和权限矩阵脚本 |
| T1-7 | 真实模型专项和最终收口 | T1-5/T1-6 | 一次真实评测、重启复核和材料同步 |

## 三、任务计划

### Task T1-0：基线、覆盖矩阵和复杂场景数据契约

**Files:**

- Create: `EnvoyMart/frontend/scripts/fixtures/platform-feature-matrix.json`
- Create: `EnvoyMart/frontend/scripts/fixtures/complex-agent-dialogues.json`
- Create: `EnvoyMart/frontend/scripts/fixtures/failure-recovery-scenarios.json`
- Create: `EnvoyMart/docs/待办/T1-平台全功能与复杂Agent覆盖矩阵.md`
- Modify: `EnvoyMart/frontend/scripts/verify-all.mjs`
- Modify: `EnvoyMart/frontend/scripts/lib/verify-util.mjs`

- [ ] 固定当前 commit、服务端口、中间件健康状态、前端路由、公开 API、Agent 工具和当前评测快照作为 T1 基线。
- [ ] 覆盖 38 个买家/管理端页面、全部公开业务 API、全部当前 Agent 工具和每项写操作。
- [ ] 复杂对话不少于 48 条，其中至少 24 条为 3 轮以上、20 条使用 2 个以上工具、12 条包含确认后写操作、10 条包含失败恢复/重规划、8 条覆盖重启恢复。
- [ ] 每条场景记录初始数据、消息序列、期望工具 DAG、参数引用、确认点、允许澄清/拒答、最终数据库/Redis/MQ 状态和日志断言。
- [ ] 能力矩阵明确 `UI_ONLY`、`API_ONLY`、`AGENT_READ`、`AGENT_WRITE_CONFIRM`、`UNAVAILABLE`；Multi-Agent 标为 `OUT_OF_SCOPE`。
- [ ] 运行基线：

```powershell
cd EnvoyMart\backend
mvn -o test
cd ..\frontend
pnpm type-check
pnpm build
pnpm lint
```

Expected: 形成带时间、commit、版本和已知失败项的基线报告。

### Task T1-1：消息 ID、点踩和全对话选择

**Files:**

- Modify: `EnvoyMart/backend/ai-service/src/main/java/yumefusaka/envoymart/aiservice/memory/ChatHistoryStore.java`
- Modify: `EnvoyMart/backend/ai-service/src/main/java/yumefusaka/envoymart/aiservice/service/impl/AiAssistantServiceImpl.java`
- Modify: `EnvoyMart/backend/ai-service/src/main/java/yumefusaka/envoymart/aiservice/controller/ChatSessionController.java`
- Modify: `EnvoyMart/backend/ai-service/src/main/java/yumefusaka/envoymart/aiservice/memory/BadCaseStore.java`
- Modify: `EnvoyMart/frontend/src/api/ai.ts`
- Modify: `EnvoyMart/frontend/src/types/models.ts`
- Modify: `EnvoyMart/frontend/src/components/ai/ChatMessageList.vue`
- Modify: `EnvoyMart/frontend/src/views/AiAssistantView.vue`
- Modify: `EnvoyMart/frontend/src/views/admin/AdminBadCaseView.vue`
- Test: `EnvoyMart/backend/ai-service/src/test/java/yumefusaka/envoymart/aiservice/memory/ChatHistoryStoreTest.java`
- Test: `EnvoyMart/backend/ai-service/src/test/java/yumefusaka/envoymart/aiservice/memory/BadCaseStoreTest.java`
- Create: `EnvoyMart/frontend/scripts/verify-chat-feedback.mjs`

- [ ] 服务端每轮生成权威 `turnId`、`userMessageId`、`assistantMessageId`，流式完成事件返回 `assistantMessageId`。
- [ ] 历史加载、前端内存消息、Bad Case、审批确认和重新生成统一使用权威 ID；重新生成保留被改写助手消息 ID。
- [ ] 点踩面板改为当前会话完整消息的可滚动选择区，默认选择当前问题和当前回答。
- [ ] 服务端校验 selectedMessageIds 均属于当前用户和当前 session；越权、缺失、撤销、刷新恢复均返回明确状态。
- [ ] 审核通过并补齐标注后进入独立追加测试集，不把反馈数据混入基础评测分母。
- [ ] 运行：

```powershell
cd EnvoyMart\backend
mvn -o -pl ai-service -am test
cd ..\frontend
node scripts/verify-chat-feedback.mjs
```

Expected: 点踩不再出现“助手消息不存在”；刷新、撤销、管理审核和完整会话选择均可复现。

### Task T1-2：Agent 工具、跨步骤参数和写操作安全

**Files:**

- Create/Modify: `EnvoyMart/backend/ai-service/src/main/java/yumefusaka/envoymart/aiservice/tool/CartUpdateTool.java`
- Create/Modify: `EnvoyMart/backend/ai-service/src/main/java/yumefusaka/envoymart/aiservice/tool/CartRemoveTool.java`
- Create/Modify: `EnvoyMart/backend/ai-service/src/main/java/yumefusaka/envoymart/aiservice/tool/CouponReceiveTool.java`
- Create/Modify: `EnvoyMart/backend/ai-service/src/main/java/yumefusaka/envoymart/aiservice/tool/TicketCreateTool.java`
- Modify: `EnvoyMart/backend/ai-service/src/main/java/yumefusaka/envoymart/aiservice/tool/ProductTool.java`
- Modify: `EnvoyMart/backend/ai-service/src/main/java/yumefusaka/envoymart/aiservice/tool/AddToCartTool.java`
- Modify: `EnvoyMart/backend/ai-service/src/main/java/yumefusaka/envoymart/aiservice/client/OrderClient.java`
- Modify: `EnvoyMart/backend/ai-service/src/main/java/yumefusaka/envoymart/aiservice/config/AiAgentConfig.java`
- Modify: `EnvoyMart/backend/agent-core/src/main/java/yumefusaka/envoymart/agent/core/AgentGraph.java`
- Modify: `EnvoyMart/backend/agent-core/src/main/java/yumefusaka/envoymart/agent/llm/PlanStep.java`
- Modify: plan contract prompts under `EnvoyMart/backend/agent-core/src/main/java/yumefusaka/envoymart/agent/llm/`
- Test: `EnvoyMart/backend/agent-core/src/test/java/yumefusaka/envoymart/agent/core/AgentGraphApprovalTest.java`
- Test: `EnvoyMart/backend/agent-core/src/test/java/yumefusaka/envoymart/agent/core/AgentGraphReplanTest.java`
- Test: `EnvoyMart/backend/ai-service/src/test/java/yumefusaka/envoymart/aiservice/tool/WriteToolConfirmationTest.java`
- Create: `EnvoyMart/backend/ai-service/src/test/java/yumefusaka/envoymart/aiservice/tool/AgentCommerceToolContractTest.java`

- [ ] 工具表、MCP server 和能力矩阵统一列出新增工具，禁止出现不存在的 `after_sale_preview`；明确预览是服务 API/确定性流程还是工具。
- [ ] `ProductTool` 输出每个候选的 SPU、SKU、规格、价格和可购买状态，`rawData` 提供结构化 ID。
- [ ] 计划支持 `$N.field`、`$N[i].field`；引用列表必须显式下标，多元素列表禁止默认取第一个。
- [ ] 计划编译阶段建立 `stepOutputBindings`，缺前置产出、未来引用、未解析引用和重复 operationId 直接阻断。
- [ ] 高危写操作全部 `requiresConfirmation(true)`，审批载荷绑定用户、会话、动作、参数和 operationId；确认后不重新问模型。
- [ ] 写操作完成后复查 SKU、规格、数量、价格、可购买状态、订单、优惠券或工单权威结果。
- [ ] 购物车、结算、订单取消、售后、优惠券和工单重复提交只产生一次副作用；下游返回可核验的第一次结果。
- [ ] 运行：

```powershell
cd EnvoyMart\backend
mvn -o -pl agent-core,ai-service,order-service,promotion-service -am test
cd ..\frontend
node scripts/verify-mcp-confirm.mjs
node scripts/verify-agent-side-effects.mjs
```

Expected: 双商品分别绑定、只生成一个确认批次、每个写步骤最多执行一次，最终购物车/订单/优惠券/工单状态与意图一致。

### Task T1-3：评测快照持久化和页面语义

**Files:**

- Create: `EnvoyMart/backend/ai-service/src/main/java/yumefusaka/envoymart/aiservice/eval/EvalSnapshotStore.java`
- Create: `EnvoyMart/backend/ai-service/src/main/java/yumefusaka/envoymart/aiservice/eval/EvalSnapshotDto.java`
- Modify: `EnvoyMart/backend/ai-service/src/main/java/yumefusaka/envoymart/aiservice/eval/ProductionRetrievalEvalService.java`
- Modify: `EnvoyMart/backend/ai-service/src/main/java/yumefusaka/envoymart/aiservice/eval/GroundingLiveEvalService.java`
- Modify: `EnvoyMart/backend/ai-service/src/main/java/yumefusaka/envoymart/aiservice/controller/GroundingEvalController.java`
- Modify: `EnvoyMart/frontend/src/views/KnowledgeEvalView.vue`
- Modify: `EnvoyMart/frontend/src/views/AnswerQualityView.vue`
- Modify: `EnvoyMart/frontend/src/views/admin/AdminEvaluationView.vue`
- Test: `EnvoyMart/backend/ai-service/src/test/java/yumefusaka/envoymart/aiservice/eval/GroundingLiveEvalServiceTest.java`
- Create: `EnvoyMart/backend/ai-service/src/test/java/yumefusaka/envoymart/aiservice/eval/EvalSnapshotStoreTest.java`
- Modify: `EnvoyMart/frontend/scripts/verify-eval-ui.mjs`
- Modify: `EnvoyMart/frontend/scripts/verify-answer-quality-ui.mjs`
- Modify: `EnvoyMart/frontend/scripts/verify-eval-evidence.mjs`

- [ ] 快照 DTO 只使用稳定字符串、数字、列表和 Map，不直接序列化 `DocumentChunk`、`RetrievalOutcome` 等内部对象。
- [ ] 写入采用临时文件 + 原子替换；保留当前快照和带 runId 的历史快照；目录由 `EVAL_SNAPSHOT_DIR` 注入。
- [ ] 页面区分 `NEVER_RUN`、`RUNNING`、`COMPLETED`、`SNAPSHOT_CORRUPTED`、`VERSION_INCOMPATIBLE`，读取失败不得伪装为未运行。
- [ ] 兼容旧快照逐字段迁移，缺字段显示“未记录”，不能用当前运行数据补历史数据。
- [ ] 公开页只读快照，admin 页是唯一触发入口；刷新、访问和脚本验收不启动模型。
- [ ] 运行：

```powershell
cd EnvoyMart\backend
mvn -o -pl ai-service -am test
cd ..\frontend
node scripts/verify-eval-ui.mjs
node scripts/verify-answer-quality-ui.mjs
node scripts/verify-eval-evidence.mjs
```

Expected: 重启 ai-service 后快照、错误状态、历史 runId 和证据链仍可读取。

### Task T1-4：图谱诊断根因收口

**Files:**

- Modify: `EnvoyMart/backend/knowledge-service/src/main/java/yumefusaka/envoymart/knowledgeservice/graph/TripleValidator.java`
- Modify: `EnvoyMart/backend/knowledge-service/src/main/java/yumefusaka/envoymart/knowledgeservice/graph/GraphService.java`
- Modify: `EnvoyMart/backend/knowledge-service/src/main/java/yumefusaka/envoymart/knowledgeservice/entity/GraphBuildFailureEntity.java`
- Modify: graph persistence migration under `EnvoyMart/backend/knowledge-service/src/main/resources/db/migration/`
- Modify: graph builder under `EnvoyMart/backend/ai-service/src/main/java/yumefusaka/envoymart/aiservice/knowledge/KnowledgeGraphBuilder.java`
- Modify: `EnvoyMart/frontend/src/api/admin/knowledge.ts`
- Modify: `EnvoyMart/frontend/src/views/admin/AdminGraphFailuresView.vue`
- Test: `EnvoyMart/backend/knowledge-service/src/test/java/yumefusaka/envoymart/knowledgeservice/graph/TripleValidatorTest.java`
- Test: `EnvoyMart/backend/knowledge-service/src/test/java/yumefusaka/envoymart/knowledgeservice/graph/GraphServiceRecallTest.java`
- Test: `EnvoyMart/backend/knowledge-service/src/test/java/yumefusaka/envoymart/knowledgeservice/graph/GraphServiceCoverageTest.java`
- Create: `GraphServiceFailureRecordTest.java`
- Modify: `EnvoyMart/frontend/scripts/verify-knowledge-ui.mjs`
- Modify: `EnvoyMart/frontend/scripts/verify-admin-console.mjs`

- [ ] 拒绝记录保存原始候选、规范化前后端点和类型、别名命中、关系、quote、偏移、阶段、原因码、批次、文档、是否可重试和脱敏详情。
- [ ] 统计按构建批次隔离，分别展示抽取失败、事实校验拒绝、写入失败和跳过；分页不能固定取前 200 条混算。
- [ ] 管理端支持批次、文档、阶段、原因码、实体和关系筛选，详情能回到原文 quote。
- [ ] 只修复有原文证据且能证明是词表/别名错误的事实；没有原文锚定或 quote 改写的候选继续拒绝。
- [ ] 图谱说明区固定写明 AI 提候选、规则判真、Neo4j 保存通过事实三层职责。
- [ ] 运行：

```powershell
cd EnvoyMart\backend
mvn -o -pl knowledge-service,ai-service -am test
cd ..\frontend
node scripts/verify-knowledge-ui.mjs
node scripts/verify-admin-console.mjs
```

Expected: 任一拒绝条目可从批次统计追到候选、规范化结果、原因和原文。

### Task T1-5：确定性复杂对话回放和后端契约测试

**Files:**

- Create: `EnvoyMart/backend/agent-core/src/test/resources/complex-dialogues/*.json`
- Create: `EnvoyMart/backend/agent-core/src/test/java/yumefusaka/envoymart/agent/core/AgentComplexDialogueContractTest.java`
- Create: `EnvoyMart/backend/ai-service/src/test/java/yumefusaka/envoymart/aiservice/tool/AgentCommerceToolContractTest.java`
- Create: `EnvoyMart/backend/ai-service/src/test/java/yumefusaka/envoymart/aiservice/service/AgentSideEffectVerificationTest.java`
- Modify: `EnvoyMart/backend/agent-core/src/test/java/yumefusaka/envoymart/agent/core/AgentGraphReplanTest.java`

- [ ] 固定计划和工具返回，回放完整 DAG，不依赖真实模型随机性。
- [ ] 覆盖参数引用、列表下标、步骤依赖、确认令牌、重规划、幂等、取消、失败分类、最终副作用和恢复。
- [ ] 覆盖普通用户、管理员、未登录用户、跨用户 sessionId、越权消息 ID、快照损坏和版本不兼容。
- [ ] 每个 case 记录工具顺序、参数来源、确认状态、最终状态和预期日志断言。
- [ ] 运行：

```powershell
cd EnvoyMart\backend
mvn -o -pl agent-core,ai-service -am test
```

Expected: 回放测试在没有真实模型、没有外部网络的条件下稳定通过。

### Task T1-6：全平台 API/UI/Agent 验收脚本

**Files:**

- Create: `EnvoyMart/frontend/scripts/verify-platform-matrix.mjs`
- Create: `EnvoyMart/frontend/scripts/verify-complex-agent-dialogues.mjs`
- Create: `EnvoyMart/frontend/scripts/verify-agent-side-effects.mjs`
- Create: `EnvoyMart/frontend/scripts/verify-failure-recovery.mjs`
- Create: `EnvoyMart/frontend/scripts/verify-persistence-restart.mjs`
- Create: `EnvoyMart/frontend/scripts/verify-permission-matrix.mjs`
- Modify: `EnvoyMart/frontend/scripts/lib/verify-util.mjs`

- [ ] 覆盖两个不同商品检索、选规格、加购、查询、修改、删除和最终购物车状态。
- [ ] 覆盖地址读取、用户确认、结算、支付状态、订单查询、取消、物流、售后预览/申请/审核和工单完整流转。
- [ ] 覆盖优惠券查询、领取、结算使用和失效；收藏、评价、知识检索、图谱问答和无证据拒答。
- [ ] 覆盖工具空结果、业务失败、连接失败、超时、重复确认、确认取消和服务重启后继续。
- [ ] 所有写场景重新读取权威接口或存储状态；脚本输出 caseId、requestId、DAG、最终状态和失败阶段。
- [ ] 运行：

```powershell
cd EnvoyMart\frontend
node scripts/verify-platform-matrix.mjs
node scripts/verify-complex-agent-dialogues.mjs
node scripts/verify-agent-side-effects.mjs
node scripts/verify-failure-recovery.mjs
node scripts/verify-persistence-restart.mjs
node scripts/verify-permission-matrix.mjs
```

Expected: 每项平台主写操作至少一条结果；每个 Agent 工具至少一条真实或确定性证据。

### Task T1-7：真实模型专项、重启复核和材料收口

**Files:**

- Modify: `EnvoyMart/docs/待办/待办总表.md`
- Modify: `EnvoyMart/docs/项目总览.md`
- Modify: `EnvoyMart/docs/待办/平台全功能与复杂Agent一次性收口计划.md`
- Modify: `EnvoyMart/docs/待办/EnvoyMart-问题与改动方案总清单.md`
- Modify: `E:\Project\面试训练\AGENTS.md`
- Modify: `E:\Project\面试训练\下阶段规划与会话交接.md`
- Modify: root material files only when facts changed

- [ ] 确定性测试、UI 检查和 T1 脚本全部通过后，人工明确触发一次生产检索质量评测、一次回答质量评测、一次复杂 Agent 场景集和一次图谱全量重建。
- [ ] 保存 runId、requestId、工具轨迹、确认状态、失败阶段、最终状态、模型/语料/prompt 版本和 token 用量；失败样本不得从分母删除。
- [ ] 重启 ai-service、knowledge-service 和前端，再读取评测快照、历史、Bad Case、图谱诊断和工具状态。
- [ ] 更新 T1 实际工具数量、能力矩阵、页面口径、评测数字、图谱统计和已知短板；不更新与 T1 无关的 T2/T3 结论。
- [ ] 检查运行期快照、日志、截图、测试账号、密钥和临时产物不进入项目仓库。

## 四、T1 统一验收门槛

### 后端

- `mvn -o test` 全模块 BUILD SUCCESS，0 失败。
- 消息 ID、Bad Case、快照 DTO、图谱诊断、Agent 工具、审批和幂等测试全部通过。
- 快照重启读取不再出现内部 DTO 反序列化错误；损坏和版本不兼容有明确状态。
- 任一写工具重复提交不会产生重复副作用；工具失败区分无数据、业务失败和下游不可用。

### 前端

- `pnpm type-check`、`pnpm build`、`pnpm lint` 通过。
- 当前 38 个路由覆盖加载、空态、错误态、权限态和关键写操作。
- 点踩支持完整会话选择、提交、刷新恢复、撤销和错误态。
- 两个评测页只读快照，损坏/版本不兼容有明确错误态。
- 图谱诊断支持批次和原因筛选、候选详情和聚合统计。

### 端到端

- `node scripts/verify-all.mjs --slow` 通过，并包含 T1 新增脚本。
- 复杂场景全部有结果，所有写场景都有最终状态断言。
- 双商品最终购物车逐项等于用户意图。
- 每个 Agent 工具至少一条覆盖；每个买家/管理端主写操作至少一条覆盖。
- 日志能按 caseId/requestId 回溯服务边界和工具轨迹。
- Multi-Agent 不出现在工具表、测试矩阵、MCP instructions 或材料中。

## 五、T1 实施纪律

- T1 每个任务完成后先运行本任务测试，再运行相邻回归；失败先分类，不继续盲目改动。
- 真实模型调用只在 T1-7 人工触发，页面访问和脚本验收不得自动触发评测。
- T1 期间不提前实现 T2/T3；发现新问题只登记到总清单并明确是否阻塞 T1。
- 提交、推送、删除临时文件和跨仓库材料清理仍需按项目规则单独确认。
