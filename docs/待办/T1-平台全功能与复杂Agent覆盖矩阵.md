# T1 平台全功能与复杂 Agent 覆盖矩阵

## 基线

基线由 `frontend/scripts/fixtures/platform-feature-matrix.json` 固定，包含 38 个真实叶子页面、15 个 Agent 工具和 10 条失败恢复契约。2026-10-11 本次源码核对得到149个网关可访问 HTTP API（包含新增审批恢复接口）；这个数由控制器和网关排除清单动态复核，不固定为长期分母。公开 API 在这里包括买家、管理员和匿名接口，不等于匿名白名单；网关 `INTERNAL_ONLY_PREFIXES` 排除的内部接口不计入。矩阵版本为 `t1-2026-10-10`；运行证据另保存源码版本、请求 ID 和时间。

`verify-platform-matrix.mjs` 用已有 TypeScript AST 读取唯一来源 `src/router/index.ts`，排除布局与重定向，逐项核对页面集合；控制器静态 HTTP 注解与网关内部排除集合共同生成 API 全集，新增、遗漏、错误 method 和虚构 path 都会失败。`/knowledge/:docNo` 是真实页面，诊断记录详情在现有页面展开，没有 `/admin/knowledge/graph-diagnostics/:id` 页面。

能力状态含义：`UI_ONLY` 只由页面承载，`API_ONLY` 只由接口/评测页承载，`AGENT_READ` 为只读工具，`AGENT_WRITE_CONFIRM` 为必须服务端审批的写工具，`UNAVAILABLE` 明确不可用，`OUT_OF_SCOPE` 表示本批不实现的 Multi-Agent 能力。

## 复杂对话契约

`complex-agent-dialogues.json` 固定 48 条场景：24 条至少三轮，20 条使用两个及以上工具，12 条包含确认后写操作，10 条覆盖失败恢复或重规划，8 条覆盖重启恢复。每条记录 `caseId`、消息序列、工具 DAG、是否写操作、恢复标记和重启标记。确定性回放必须额外输出参数来源、operationId、确认状态、最终权威状态和失败阶段。

## 验收不变量

1. 任何写操作必须有服务端确认令牌、稳定 `operationId` 和最终状态复查。
2. 列表引用必须显式下标；多元素列表上的无下标引用不得默认取第一项。
3. 重复确认、网络重试和重规划不得产生第二次副作用。
4. 评测页只读快照；快照损坏和版本不兼容必须展示为错误状态。
5. 图谱拒绝记录必须可由批次、文档、阶段、原因码追溯到候选和原文 quote。
6. Multi-Agent 不出现在工具表、MCP instructions 和验收脚本中。

## 验收证据

后端 `AgentComplexDagReplayTest` 真实执行15个图/审批/工具桩场景，`AgentGraphPlanReferenceTest` 锁定嵌套参数与修复边界；前端执行器测试使用模拟传输跑48条契约，均不等同模型真跑。原六个T1矩阵脚本只有静态结构检查，不能提供运行期覆盖证明。

真实复杂场景由人工入口 `run-complex-agent-eval.mjs` 执行，快照留在已忽略的`.evidence`，包括setup、caseId/requestId、DAG匹配、权威状态、操作日志及失败原因。`verify-persistence-restart.mjs --capture/--compare` 对进程身份和实际持久化数据比较，默认静态模式必须显示重启未覆盖。完整验收仍以T1实施计划的当前状态为准，不能按声明总数报通过率。

## 页面与 API 逐项证据契约

矩阵 `routeEvidence` 为每个页面声明 `loading/empty/error/permission/write` 五类契约；`apiContracts` 为每个 HTTP method/path 声明源码位置、主写标记、预期行为、权威结果读取入口和已有脚本。`READ_ONLY_POST` 标识结算预览，不能把 HTTP POST 一律当业务写；四个重建/评测入口为 `MANUAL_MODEL_RUN`，不得由自动状态回归触发。

- `SCRIPTED`：已经定位具体脚本断言（`assertions.script/anchor/line`），仍须关联本次执行结果，不等于 PASS。
- `UNCOVERED`：尚缺逐项断言或本次证据；列出的脚本只是补齐候选，不能通过 URL 出现在文件里推断已测。
- `MANUAL`：须使用人工触发的真实专项快照核对 runId、请求、分母和用量。
- `NOT_APPLICABLE`：只适用于明确没有该业务状态的静态页或只读页，保留具体理由；不可把失败路径改成不适用。

38页四类状态已关联一次完整浏览器报告。公共 API 首轮 runId `aada54b9-0537-49c8-92dd-9d8cdac7192e` 为84 PASS、10 FAIL、51 BLOCKED、4人工待验，未从149分母移除失败。手工库存流水共用管理员编号的根因修复后，第二轮 `ddf4165c-42de-469b-94e4-a4f1a0119772` 全部12业务组和收尾通过：126 API PASS、2验收DTO假设FAIL、16 BLOCKED、5人工待验。两项DTO形状纠正由只读真实run `acb10bf7-985f-4178-b8b6-cdb93f34e7ef` 2/2兑现；售后preview基于第二轮原始HTTP捕获做字段回放。当前129/149 API已有逐接口运行证据，原失败报告保留。

AI公开API独立run `d00b57a2-7c63-418f-89b5-97b576138a83` 执行一次chat、一次stream，12接口直接PASS；两项验收前提失败分别是把消息DISMISSED与审批账本CANCELLED混同，以及默认成功码去测试应拒404。按原真实响应、源码公共契约做确定性回放后14项均有证据，原失败报告保留、不额外调用模型。当前公共API逐项证据为143/149，合并索引在忽略目录 `.evidence/public-api-verified-union.json`。

有效签名支付回调 `c7203dac-8c3a-43d9-9da4-842678a5ca69`、退款失败后原售后重试 `ba6252c3-59c3-4ee7-8985-8921e64a0071` 与四个模型入口均已加入合并索引；逐endpoint原checks/requestIds复核149/149，不等于模型质量或全平台门禁已通过。

模型页面窗口 `2a150338-af8b-4147-8b1c-d8950c5e0f33` 真实保存自有停用KB-0055并触发增量删除，随后全量重建53篇/425片，图谱405实际写入、失败0/跳过0。检索POST请求`14750e5eec2d458f`成功启动，但额外只读轮询撞网关429，原页面FAIL保留；按页面真实请求时序节流、仅GET有界恢复后，窗口`5edadd76-9c0e-4161-a5ed-847f67be65e2`读取原检索run，无第二次检索POST，随后真实点击回答评测。检索run`4e22c372-cdb5-4317-b43d-e789f1409b17`完成70条、HitRate@3=98.57%；回答run`33d7d153-021f-4e71-9ab7-97f05b971c16`完成36条、执行失败0、0/66未支撑、引用100%、拒答97.22%（过拒1），多跳17/18，不能沿用历史18/18。唯一未命中G-14涉及维生素D上限，最终答案缺明确2000IU且保留悬空「这个剂量」，虽有相关证据仍是质量缺口。完整same-run权威DTO与四POST/GET映射单独保存在`.evidence/manual-model-final-snapshots-50d4aa78-5469-4f7b-ae21-d191ae46167c.json`，未重跑模型或改历史报告；用量数字仅对合法非负safeint字段保留，令牌和凭据仍脱敏。

非模型页面主写25项已有真实点击与权威GET：主批 `4201e26e-52bf-49d2-b3c5-c4b432f87f56` 22 PASS、3验收前提FAIL；规格选择及管理员脱敏身份前提纠正后 `f5c86a0b-71b7-4d72-9164-37a50b058dbb` 两项PASS；评价管理独立补验 `19687343-ab8f-4efa-88a1-5a592efa09c6` PASS。中间补验的支付夹具字段误用FAIL与原报告全部保留。合并索引 `.evidence/platform-writes-verified-union.json` 引用原run、原逐点击请求及GET，不改原报告；加上三个模型页面后只读compare逐项复核28 PASS、新写/模型0。`reviews`、`my-coupons` 页面仅读取和跳转，分别以 `MyReviewsView.vue`、`MyCouponView.vue` 源码证明主写NOT_APPLICABLE；评价写入归订单详情，领券归领券中心。严格矩阵门禁13/13通过、未覆盖页面状态0/API0；这只是逐项运行证据门禁，不替代最终代码下医学质量和联合重启门禁。

助手页最终捕获`37305168-1d3b-4525-b255-65e3e530e228`：独立账号唯一模型请求产生待审批，确认按钮沿原turn/message/token指纹提交，稳定operationId回执COMMITTED，权威工单209只一张且原文一致，历史消息真实持久化。原`0298e1cb`及`a1d48889`两次采集FAIL保留，两账号均仅待审批未执行工单：第一个前提错误是要求SSE fetch必有客户端requestId，实际上服务端done带稳定ID；第二个是Chrome CDP将SSE中文重编码，即便response.body也会乱码，而页面原Fetch解码正确。短生命周期HTTP服务器加真实Chrome回归证实原请求不变、原Fetch及透明Response.clone中文正确，最终capture通过clone读取原响应，未mock或替换产品行为。

```powershell
node scripts/verify-platform-matrix.mjs
node scripts/verify-platform-matrix.mjs --require-evidence
node --test scripts/tests/platform-matrix.test.mjs
node scripts/verify-platform-writes-ui.mjs --compare=.evidence/platform-writes-verified-union.json
```

第一条只验证契约结构，输出 `CONTRACT_VALID` 和未覆盖数量；第二条有未覆盖项或缺少逐项 `lastEvidence`（runId、报告路径、结果及证据边界）就非零退出，`SCRIPTED` 不能代替本次通过报告。静态契约测试固定真实页面与 API 全集、网关排除、嵌套路由、注解形状以及证据状态边界。

## 浏览器状态补齐

`verify-platform-states-ui.mjs` 运行真实 Vue Router 和真实页面组件，拦截所有业务传输，不调用模型或业务写 API。按页面明确主读取接口，延迟应答观察加载指示，恢复应答确认加载结束；空集合/未运行/详情404验证空态；业务503验证页面错误与重试恢复；匿名、普通买家、管理员逐页验证直接导航。登录/注册通过真实表单提交验证必填、提交中与业务失败，404按静态组件边界记录不适用。

报告逐条保存 route/state/role、PASS/FAIL/不适用原因、实际拦截请求和 requestId，失败截图与 JSON 留在 `.evidence/platform-states-<runId>/`。此报告只证明页面状态与前端路由权限，网关鉴权、真实模型和业务写副作用仍由对应已有脚本、复杂场景快照和 API 权威读回证明。不能把拦截渲染记成真实业务调用。

2026-10-11完整报告 runId `0656cb9a-5738-4334-8c36-fb2e17469ea1`：152条结果，149 PASS、0 FAIL、3不适用。3项均为静态404页的加载/空数据/业务错误态；该页匿名可达和真实404渲染另有PASS。发现的收银台、个人地址和管理员评测加载缺口，以及助手会话、反馈审核、评测运行、新建商品词表失败恢复缺口已修复；定向28/28与完整报告均兑现。`pnpm type-check`、`pnpm build-only`及改动文件 ESLint 均通过；这不替代后续真实API写验收。

公共 API 的重点缺口是：地址增改删与默认切换；知识文档写入/停用/绑定与切片读取；类目属性改删、品牌CRUD、图片上传与SKU库存调整；订单号取消、支付回调，以及图谱召回等只读端点。已有脚本中部分请求仅用于准备夹具、部分管理员按钮只拦截接线、部分场景会SKIP，不能据此标记主写已执行。补齐时使用独立账号和无业务引用的测试数据，每个动作复读对应权威API；需要删除文件、迁移或模型重建的步骤继续遵循任务授权边界。

## 隔离公共 API 真跑入口

`verify-public-api-contracts.mjs` 以12个顺序业务组执行，所有调用必须命中当前源码方法/路径矩阵：身份权限、地址、类目品牌属性、商品库存与图片、购物车收藏、订单支付、物流收货、评价、优惠券、售后、工单、公共知识与诊断读取。买家、第二买家和角色测试账号均新注册，管理员只修改这些账号及本轮新建的数据；购物车、地址、类目、品牌、商品的删除仅针对本轮自有且确认无引用的资源，不删除文件或用户存量数据。

写操作必须有具体断言与同次权威读回；200调用、403拒绝、准备夹具、任意object和脚本总数都不能自动升级为PASS。报告逐 method/path 保存状态、requestId、响应摘要、断言和最小fixture；每组结束即落盘，中断仍保留前组结果。SSE读取初始同用户球权后取消连接；图片采用本地生成一像素PNG，经本地网关上传后读回原字节，文件保留在运行数据目录。

```powershell
node scripts/verify-public-api-contracts.mjs --contract-only
node --test scripts/lib/public-api-evidence.test.mjs
node scripts/verify-public-api-contracts.mjs
```

前两条不访问服务、不调用模型；真实入口需由主验收在全部服务与MQ就绪后串行执行。模型评测/图谱重建、实际模型会话与审批反馈、支付有效签名回调、失败退款重试均须对应人工专项或既有真实运行证据，不通过默认脚本制造。知识文档验证使用本轮新建、立即停用的自有说明书；更新、绑定和切片只调用知识HTTP接口，不启动模型，收尾保留停用记录供历史溯源，索引对齐由主验收人工执行。`--evidence=.evidence/<文件>.json` 只接收逐接口的runId/requestIds/PASS/报告路径并核对原始报告；也接受同次全站脚本输出中的逐断言PASS，脚本ok或SKIP不算；旧PASS不能覆盖本轮FAIL。未覆盖、人工待验、组失败都会使进程非零退出，分母始终来自当前矩阵。

`verify-ai-public-api-live.mjs` 是独立人工模型入口，不能无意随静态回归重复调用。只注册本轮账号，使用本轮真实响应的messageId和approvalToken；stream原批次确认后查询COMMITTED回执不重复创建工单；另一批原审批取消不执行工具；反馈完整消息保存、REVOKED、管理员审核、追加集标注和移出均复读权威结果。结束只删除这两个自有会话，原失败样本和审核记录仍保留。报告的每个method/path都需明确断言，不把普通会话历史或浏览器拦截当服务执行。

自有夹具与公开读取的身份分离：公共API脚本以`ownedDocNo`、原POST请求ID、精确标题和状态证明管理写归属；`readDocNo`只用于公开读取，不能获得写权限。旧报告中的公共读取字段不作为归属来源，例如第二轮真正创建的是KB-0055，后续公共读取KB-0001不能授权修改平台政策。

页面夹具capture须显式`--cleanup-authorized`：新类目和品牌默认停用，商城步骤仅短窗启用自有类目；每步和统一finally均恢复停用，商品下架后核对权威状态，并验证匿名树/品牌没有自有ID。用户授权后，原报告精确证明创建的品牌20/22/24仅停用、保留字段，证据`6017ec32-6476-415b-b4dc-503486caab9f`。新最小页面运行`bdd50fd0-3a40-42f0-b47e-dd4eaea23639`真实catalog/shop两PASS后注入受控异常，统一finally仍完成类目45/品牌25停用与SPU55下架，cleanupFailures为空、公开可见NONE；匿名商城加载完成12张正常商品卡片、无验收文本，截图在忽略证据目录。保留商品、订单和文档溯源数据，不删除历史。模型页面只在显式`--model-window`运行，默认capture不自动启动评测或重建。

公开ES搜索第一页即使零命中也会记录热搜，不能作为无副作用的可见性检查。授权后，审计人工词`t1ui_`按源码真实Redis键`envoymart:hot-keywords`精确移除，证据`267e3586-d61f-4d82-89f5-e9355ffd92d4`记录分数1→0，其余78词逐分数原值保留，未知归属人工词不处理、自然词不减分、榜单不FLUSH。共享`fixture-hot-keywords.mjs`已接入页面/API runner：仅本轮UUID人工词ES第一页查询记录请求ID、原分数与成功增量，收尾仅归还本轮delta，已有分数保留，零基线且全为本轮时才ZREM；额外真实请求`8b431478-2c6e-4646-807f-c6d7bd819bdf`证明0→1、受控异常finally→0。模型专用窗口只注册自有账号及读取原自有文档/快照，不创建普通类目和SPU。

最小收口顺序：先执行38页状态脚本，修真实失败路径；再把通过的 route/state 关联其报告，将对应项改为有断言入口；已有业务脚本的主写断言关联运行汇总；剩余 API 按领域补少量确定性 API 读写契约，所有写都复读权威结果；最后运行严格矩阵门禁。页面只读状态回归不会与当前故障注入互相写库。
