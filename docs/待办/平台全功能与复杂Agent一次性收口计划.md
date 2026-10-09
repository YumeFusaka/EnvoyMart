# 平台全功能与复杂 Agent 一次性收口计划

> 状态：计划已确认，尚未实施。实施时作为一个完整任务一次性完成，不交付“最小闭环”或中间版本。
> 日期：2026-10-09

## 目标与完成定义

一次性完成以下目标：

1. 修复用户反馈的双商品加购错误、点踩提交失败、评测快照重启后消失/显示为空态三个缺陷。
2. 让 Agent 覆盖平台现有的可安全自动化能力：商品检索、购物车查询/增删改、优惠券领取、结算、订单查询/取消、物流查询、售后/退款申请、工单提交，以及知识检索和图谱问答。
3. 建立覆盖全平台功能、复杂多轮对话、跨工具参数引用、确认闸、失败恢复、重启恢复和权限边界的统一测试集。
4. 让检索评测、回答质量评测、Bad Case 和图谱诊断的结果都能在重启后稳定读取，并保留可核验的真实执行链路。
5. 用一轮完整回归证明页面、HTTP API、Agent 工具、数据库/Redis/MQ 状态和可观测日志一致。

完成不以“助手回答看起来正确”为判据。所有写操作都必须有最终状态断言；所有评测结果都必须有可反序列化的持久化证据；所有复杂场景都必须记录工具顺序、参数来源、确认状态、requestId 和副作用结果。

## 已确认的问题结论

### 1. 两种商品被加成同一个商品

当前 Agent 已注册 11 个工具，但现有日志显示：用户先问两个商品，后续计划在高危步骤中出现“引用无法解析”，随后 `cart_add` 被重复执行。`ProductSearchResult` 已支持列表结果和 `$0[0].skuId`/`$0[1].skuId` 形式，但执行器对未带下标的列表引用仍有“取第一个”的兼容行为，且重规划后没有把“已执行的第一个商品”与“尚未执行的第二个商品”作为不可混淆的步骤事实。

因此根因不是用户表达不清，也不是购物车页面没有操作，而是 Agent 的写操作计划缺少强制的逐项绑定和执行幂等。修复必须同时堵住：多商品参数绑定、重规划重复执行、同一计划步骤重复提交、执行后无状态复核四个入口。

### 2. 点踩提交报“助手消息不存在”

前端在流式发送时生成 `assistant-${Date.now()}` 临时 ID；`ChatHistoryStore.recordTurn()` 又在服务端生成新的 `a-${UUID}` ID。点踩把前端 ID 发给 `BadCaseStore.upsert()`，服务端从 Redis 历史查不到它，于是返回“助手消息不存在”。当前反馈面板还把消息截断在回答附近的 4 条窗口内，不符合“在整个对话界面选择关联消息”的要求。

### 3. 评测快照并非被重启清空

当前快照文件实际存在：`backend/ai-service/data/eval/production-retrieval.json` 有 70 条样本，`production-grounding.json` 有 36 条样本。但服务重启后读取失败，日志明确记录 `DocumentChunk` 没有可用构造器，随后服务把读取异常降级成 `NEVER/IDLE`，页面因此显示“没有快照”。根因是把内部 Lombok DTO 直接当作持久化 DTO 反序列化，并且读取失败被伪装成“从未运行”。

### 4. 图谱被拒绝条目多的含义

图谱不是“让 AI 读完所有文档后自由画图”。生产链路是：ai-service 按文档调用模型抽取候选三元组；候选携带实体类型、关系、效果和逐字引文；knowledge-service 使用封闭实体/关系词表、商品目录、原文端点锚定、逐字 quote 命中、组合成员完整覆盖等硬规则校验；通过的候选才整体替换该文档的图谱边，拒绝的候选不入图并记录原因。

当前拒绝主要来自：关系与端点类型不匹配、归一化后自环、候选形状不合法、端点不在原文、quote 不逐字命中。它们是模型候选层的丢弃，不等于已有图谱事实失效；但现有诊断页混合历次批次且最多只查 200 条，统计不能直接当作本次拒绝率。另有一类需要专项核对：日志中出现“原文事实看似合理但因类型/别名归一被拒”的候选，必须保留原始候选字段和规范化结果后再判断是模型错误还是校验器/词表缺口。

## 统一架构方案

### A. 身份与持久化契约

- 每一轮对话在服务端生成 `turnId`、`userMessageId`、`assistantMessageId`，并在流式完成事件中返回权威 `assistantMessageId`。
- Redis 历史、前端内存消息、Bad Case、审批确认和重新生成全部使用同一消息 ID；重新生成保留被改写助手消息的 ID，不创建不可追踪的临时替身。
- 评测快照使用专用 JSON DTO，不直接序列化/反序列化 `DocumentChunk`、`RetrievalOutcome` 等内部对象；写入采用临时文件加原子替换，读取失败返回“快照损坏/版本不兼容”并保留错误原因，不伪装成“尚未运行”。
- 快照目录通过配置项注入，默认仍为 `data/eval`，支持 `EVAL_SNAPSHOT_DIR` 指定稳定的运行数据目录；每次完成保留当前快照和带 `runId` 的历史副本，页面默认读取最近一次完成快照。

### B. Agent 写操作安全契约

- 计划编译阶段建立 `stepOutputBindings`，所有写工具的 SKU、订单 ID、购物车行 ID、优惠券 ID、工单 ID 必须来自明确的前置步骤或用户明确提供。
- 列表输出用于写操作时必须带显式下标；`$0.skuId` 在多元素列表上拒绝执行并要求重规划/澄清，不再默认取第一项。
- 每个写步骤获得稳定 `operationId = sessionId + planId + stepId`；审批确认、重规划和网络重试都使用同一 operationId。服务端工具入口和业务服务共同保证幂等，重复执行只返回第一次结果。
- 每个写操作完成后读取权威结果或校验返回对象；购物车操作必须复查 SKU、规格、数量、价格和可购买状态，不能只依据模型文本。
- Agent 能力表明确区分“可读”“需确认写入”“不允许直接执行”。没有工具的能力不得让模型用自然语言假装完成。

### C. 图谱证据契约

- 每一条拒绝记录保存原始候选、规范化端点、关系、quote、阶段、原因码、批次、文档、是否可重试和脱敏详情。
- 汇总按批次从持久化记录聚合，分别展示抽取失败、写入失败、事实校验拒绝、批次跳过；不再把不同批次前 200 条混在一起计算比例。
- 管理端提供批次、文档、阶段、原因码、实体/关系筛选，展示候选原文与校验决策，并给出“可重试”和“只能修语料/提示词/词表”的处置建议。
- 图谱构建解释文档固定说明：AI 负责提出候选，规则和原文负责判真，Neo4j 只保存通过判定的事实。

## 一次性实施任务

### 任务 1：建立覆盖矩阵和复杂场景数据契约

**新增：**

- `frontend/scripts/fixtures/platform-feature-matrix.json`
- `frontend/scripts/fixtures/complex-agent-dialogues.json`
- `frontend/scripts/fixtures/failure-recovery-scenarios.json`
- `docs/待办/平台全功能与复杂Agent覆盖矩阵.md`

**修改：**

- `frontend/scripts/verify-all.mjs`
- `frontend/scripts/lib/verify-util.mjs`

矩阵覆盖 38 个买家/管理端页面、全部公开业务 API、全部 Agent 工具和每项写操作。复杂对话集不少于 48 条，其中不少于 24 条为 3 轮以上、不少于 20 条涉及 2 个以上工具、不少于 12 条包含确认后写操作、不少于 10 条包含失败恢复或重规划、不少于 8 条跨重启恢复。每条场景声明初始数据、消息序列、期望工具 DAG、参数引用、确认点、最终状态、允许的拒答/澄清和日志断言。

矩阵将能力标为 `UI_ONLY`、`API_ONLY`、`AGENT_READ`、`AGENT_WRITE_CONFIRM`、`UNAVAILABLE`，避免把不存在的 Agent 能力误认为测试未覆盖。

### 任务 2：修复消息 ID、点踩和全对话选择

**修改：**

- `backend/ai-service/src/main/java/.../memory/ChatHistoryStore.java`
- `backend/ai-service/src/main/java/.../service/AiAssistantServiceImpl.java`
- `backend/ai-service/src/main/java/.../controller/ChatSessionController.java`
- `frontend/src/types/models.ts`
- `frontend/src/views/AiAssistantView.vue`
- `frontend/src/components/ai/ChatMessageList.vue`
- `frontend/src/api/ai.ts`

服务端统一生成并返回权威消息 ID；历史加载、流式事件、重新生成、审批、点踩全部使用同一 ID。反馈面板改为整个当前会话的可滚动消息选择区，默认勾选当前问题和当前回答，仍限制为同一会话并由服务端校验。提交成功、刷新、撤销和越权访问都要有明确状态。

**测试：**

- `ChatHistoryMessageIdentityTest`
- `BadCaseStoreTest`
- `verify-chat-feedback.mjs`
- `verify-chat-ui.mjs` 扩展为完整会话选择、提交、刷新恢复、撤销和错误态。

### 任务 3：补齐 Agent 购物车与平台操作能力

**新增或修改：**

- `backend/ai-service/src/main/java/.../tool/CartUpdateTool.java`
- `backend/ai-service/src/main/java/.../tool/CartRemoveTool.java`
- `backend/ai-service/src/main/java/.../tool/CouponReceiveTool.java`
- `backend/ai-service/src/main/java/.../tool/TicketCreateTool.java`
- `backend/ai-service/src/main/java/.../tool/ProductTool.java`
- `backend/ai-service/src/main/java/.../tool/AddToCartTool.java`
- `backend/ai-service/src/main/java/.../client/OrderClient.java`
- `backend/ai-service/src/main/java/.../client/PromotionClient.java`
- `backend/ai-service/src/main/java/.../client/TicketClient.java`
- `backend/ai-service/src/main/java/.../config/AiAgentConfig.java`
- `backend/agent-core/src/main/java/.../core/AgentGraph.java`
- `backend/agent-core/src/main/java/.../llm/*` 中的计划契约提示词

所有写工具按既有确认策略接入；退款沿用售后预览/申请/审核链路，Agent 不绕过售后闸门直接调用支付退款。购物车增删改以购物车行 ID 或 SKU 的明确契约执行，返回权威购物车行。

双商品场景必须满足：两个不同 SKU、各 1 件、只生成一个确认批次、确认后每个步骤最多执行一次、最终购物车恰有两行或同 SKU 合并规则明确且数量正确。未解析的列表引用、重复 operationId、写步骤缺前置产出都必须阻断，而不是猜第一项。

### 任务 4：修复评测快照持久化与页面语义

**新增：**

- `backend/ai-service/src/main/java/.../eval/EvalSnapshotStore.java`
- `backend/ai-service/src/main/java/.../eval/EvalSnapshotDto.java`
- `backend/ai-service/src/test/java/.../eval/EvalSnapshotStoreTest.java`

**修改：**

- `ProductionRetrievalEvalService.java`
- `GroundingLiveEvalService.java`
- `GroundingEvalController.java`
- `frontend/src/views/KnowledgeEvalView.vue`
- `frontend/src/views/AnswerQualityView.vue`
- `frontend/src/views/admin/AdminEvaluationView.vue`

快照 DTO 只使用可稳定反序列化的字符串、数字、列表和 Map；兼容旧快照时逐字段迁移，不能用当前运行数据补历史字段。页面区分 `NEVER_RUN`、`RUNNING`、`COMPLETED`、`SNAPSHOT_CORRUPTED`、`VERSION_INCOMPATIBLE`。读取失败显示错误和 runId/文件路径摘要，并保留最后一个可用快照。

**测试：**

- 新旧快照反序列化；重启后读取；原子写入中断；损坏快照回退；页面刷新不触发模型；历史缺字段显示“未记录”。
- `verify-eval-ui.mjs`、`verify-answer-quality-ui.mjs`、`verify-eval-evidence.mjs` 扩展快照重启和错误语义断言。

### 任务 5：完成图谱诊断根因收口

**修改：**

- `backend/knowledge-service/src/main/java/.../graph/TripleValidator.java`
- `backend/knowledge-service/src/main/java/.../graph/GraphService.java`
- `backend/knowledge-service/src/main/java/.../entity/GraphBuildFailureEntity.java`
- `backend/knowledge-service/src/main/resources/db/migration/*` 或现有建表脚本
- `backend/ai-service/src/main/java/.../knowledge/KnowledgeGraphBuilder.java`
- `frontend/src/api/admin/knowledge.ts`
- `frontend/src/views/admin/AdminGraphFailuresView.vue`

校验器保留“只收原文有证据”的保守原则，但把原始候选、规范化前后类型、别名命中、quote 偏移和最终拒绝原因落盘。修正能够证明是词表/别名配置错误的合法事实；对确实没有原文锚定或 quote 改写的候选继续拒绝。诊断页按构建批次显示候选总数、接受数、拒绝数、抽取/写入失败数和各原因比例，详情可以从候选追到原文。

**测试：**

- `TripleValidatorTest` 补齐每个原因的原始字段和规范化字段断言；
- `GraphServiceFailureRecordTest` 验证批次聚合、分页、筛选和历史隔离；
- `verify-knowledge-ui.mjs`、`verify-admin-console.mjs` 增加诊断详情和统计口径断言；
- 新增图谱构建说明页或管理端说明区，明确 AI 抽取、规则校验、Neo4j 入库三层职责。

### 任务 6：实现全平台 API/UI/Agent 验收脚本

**新增：**

- `frontend/scripts/verify-platform-matrix.mjs`
- `frontend/scripts/verify-complex-agent-dialogues.mjs`
- `frontend/scripts/verify-agent-side-effects.mjs`
- `frontend/scripts/verify-failure-recovery.mjs`
- `frontend/scripts/verify-persistence-restart.mjs`
- `frontend/scripts/verify-permission-matrix.mjs`

脚本复用 `verify-util.mjs`，按顺序执行并为每个场景输出 caseId、requestId、工具 DAG、最终状态和失败阶段。禁止只读助手文本；购物车、订单、售后、优惠券、工单、收藏和评价场景都要在操作后重新读取权威接口。

覆盖至少包括：

- 两个不同商品分别检索、选规格、加购、查询、改数量、删除；
- 多轮“第一个/第二个/刚才那件/另一份”指代和用户中途纠正；
- 地址读取、用户确认、结算、支付状态和订单查询；
- 订单取消、物流、售后预览、退款申请、审核结果；
- 优惠券查询、领取、结算使用和失效；
- 工单提交、客服回复、关闭、重开与未读计数；
- 图谱问答、引用跳转、组合禁忌、无证据拒答；
- 工具返回空结果、业务失败、连接失败、超时、重复确认、确认取消、服务重启后继续；
- 普通用户、管理员、未登录用户的 UI/API/Agent 权限一致性。

### 任务 7：补齐确定性复杂对话回放与后端契约测试

**新增：**

- `backend/agent-core/src/test/resources/complex-dialogues/*.json`
- `backend/agent-core/src/test/java/.../AgentComplexDialogueContractTest.java`
- `backend/ai-service/src/test/java/.../tool/AgentCommerceToolContractTest.java`
- `backend/ai-service/src/test/java/.../service/AgentSideEffectVerificationTest.java`

使用固定计划/固定工具返回回放完整 DAG，不依赖模型随机性；同时保存经过脱敏的真实轨迹作为回放夹具。测试参数引用、列表下标、步骤依赖、确认令牌、重规划、幂等、取消、失败分类和最终副作用。

### 任务 8：真实模型专项与评测收尾

所有确定性测试、UI 测试和脚本通过后，人工明确触发且只触发一次：

1. 生产检索质量评测，保存一个带 runId 的完整快照；
2. 生产回答质量评测，保存一个带 runId 的完整快照；
3. 复杂 Agent 场景集按人工批准的用例运行一次，保存工具轨迹和最终状态；
4. 图谱全量重建一次，按批次检查接受/拒绝/失败/跳过，并抽查每类原因；
5. 重启 ai-service、knowledge-service 和前端，再次读取快照、历史、Bad Case 和图谱诊断，确认数据没有退化。

真实评测页面访问、刷新和脚本验收不得自动触发模型调用。真实评测失败时保留失败快照、错误阶段、requestId 和用量，不把失败样本从分母中静默删除。

## 验收门槛

### 后端

- `mvn -o test` 全部模块 BUILD SUCCESS，0 失败；
- 消息 ID、Bad Case、快照 DTO、图谱诊断、Agent 写操作和幂等测试全部通过；
- 快照重启读取不再出现 `DocumentChunk` 反序列化错误；
- 任何写工具重复提交不会产生重复副作用；
- 工具失败区分无数据、业务失败和下游不可用。

### 前端

- `pnpm type-check`、`pnpm build`、`pnpm lint` 通过；
- 38 个路由覆盖加载、空态、错误态、权限态和关键写操作；
- 点踩在完整会话选择消息后可以提交、刷新恢复、撤销；
- 两个评测页只读快照，快照损坏/版本不兼容有明确错误态；
- 图谱诊断可以按批次和原因查看候选详情与聚合统计。

### 端到端

- `node scripts/verify-all.mjs --slow` 通过，并纳入新增平台矩阵、复杂对话、失败恢复、持久化重启和权限脚本；
- 复杂对话场景 48 条全部有结果，所有写场景都有最终状态断言；
- 双商品场景最终购物车状态与用户意图逐项相等；
- 至少一条场景覆盖每个 Agent 工具，至少一条场景覆盖每个买家/管理端主写操作；
- 全量脚本的日志能按 caseId/requestId 回溯到服务边界和工具轨迹。

## 交付与收尾

实施完成后同步更新：

- `EnvoyMart/docs/待办/待办总表.md`
- `EnvoyMart/docs/待办/当前会话交接-评测链路与BadCase.md`
- `EnvoyMart/docs/项目总览.md`
- 根目录 `AGENTS.md` 中的当前验证数字和已知限制

不提交运行期快照、日志、截图、测试账号数据或本地密钥。两个仓库的提交、推送和删除临时文件仍需用户单独确认；本计划阶段不执行这些操作。
