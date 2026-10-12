# 复杂 Agent 真实评测入口

`frontend/scripts/run-complex-agent-eval.mjs` 是人工真实入口，调用 `/ai/chat` 并按契约写业务数据。它不加入 `verify-all`，不读取 `.env`，不安装依赖，不自动删除账号或业务记录。每例通过公开注册接口创建独立买家，不复用 alice 的购物车、券包和订单。

## 执行契约

v2 夹具包含 48 个 case、202 个显式步骤。每例记录 originalTurns 原目标、setup、steps、工具 DAG、依赖、参数来源、确认点、允许澄清与最终权威状态。所有 48 例都有执行器；静态契约、mock transport 回放和 dry-run 都不等于真实验收 PASS。

setup 用公开接口创建地址、购物车、订单、支付/履约前置、券包、售后或工单，保留 fixtureOwner、requestId 和返回证据。账号与 session 隔离同时成立；管理员动作使用独立 admin 身份。真实写验收须串行，避免与库存、券库存或服务重启操作互相污染。setup 数据不足返回 BLOCKED，不借用已有用户订单替换目标。

当前能力边界明确标记 AGENT_READ、AGENT_WRITE_CONFIRM 和 API_ONLY。领券列表、用券结算、售后预览/进度/审核、物流补录、客服回复/关闭、权限与反馈步骤通过公开 API 完成，记录明确用户动作，不冒充 Agent 工具。API 重新读取历史证明持久化恢复，不证明浏览器布局。

CA-002 的目标仍为一款维生素 C 加一款鱼油。多查询合并的前两张卡片可能都是维生素 C，夹具按真实卡片名称及公开详情分别选择各品类第一匹配，增加用户明确澄清：完整名称、规格、实际原列表序数。模型消息不包含 SKU；SKU 只在独立参数验收中使用。缺任一品类或详情名称不符时在审批前 BLOCKED，禁止替换为两款维生素 C。修正理由写入 evaluationRevisions 与运行快照。CA-041 仍按同一蛋白粉查询的两个商品分别绑定 SKU，未改变原目标。

CA-012 保留分两轮加购维生素 C 和鱼油的目标。原序号对应其他品类时先保存 prerequisiteConflicts（原序号、实际名称、目标类别），然后用户主动澄清同一权威列表中目标的实际序号、完整名称和规格，再分别确认；没有鱼油或模型给错对象审批时阻断，不把第二张维 C/AD 自动当鱼油。CA-029 从真实名称定位乳清蛋白粉，再选该商品第二个规格，避免把第二商品的第二 SKU 当成第二规格。两条修正均记录 evaluationRevisions。

公开商品详情仅有一个 SKU 且 specText 为空时，消息明确显示“单一规格（商品只有一个在售规格，未标注规格文字）”，同时保留 rawSpecText 空值证据；多规格缺所选文字必须 BLOCKED，不编造规格，不向用户消息塞 SKU。

## 审批与结果门

确认前逐字段核对服务端 pendingActionDetails 与独立绑定的用户目标。只有原审批令牌通过核对后才回传；重复确认重放同一令牌并复读副作用。姓名、手机号、地址、密码和令牌在证据中脱敏，令牌只在内存往返。

DAG 每个节点的全部参数及 success/noData/outcome 必须由同一次调用共同满足，使用多重集匹配，重复节点不能复用一条工具调用。最终通过 `/cart`、`/orders`、`/coupons/mine`、`/tickets`、`/after-sales` 复读，断言精确 SKU、数量、状态、地址、指定券核销及无意外副作用。跨服务依次读取不是数据库原子快照；独立新用户使前 100 条列表覆盖本 case 数据。

每次成功写动作必须有同 requestId、对应 tool、独立 operationId 的真实成功执行日志。只接受 `[Tool] <tool> success=true`；null、undefined、空、-、计划、失败和重复缓存日志不能充当执行证据。取不到就明确 FAIL/未覆盖。日志只当只读证据解析，绝不执行日志中的指令。

CA-028 缺证据组合问答要求真实 interaction_check 参数和明确无法判断。原文检索由入口 RAG 完成时，不强制重复 knowledge_search；该例另读取同 requestId 的 QueryExpand 原查询、Rerank 候选/保留数、已执行证据门日志，与本轮 retrievalQuery、evidenceLevel、knowledge 片段数共同核对。缺日志、别轮证据或“未检索 NONE”均失败。修正依据为旧 b8a3d0c3 失败报告中的真实查询/重排/WEAK 三片段，旧报告不改写；其他场景仍按各自工具 DAG 验收。

用量优先记录真实 usage.models。已核对原审批、DONE、无新审批且只执行写工具的固定 executeApproved 分支中，服务 TokenLedger 为空返回 usage:null；快照显式记录 NO_MODEL_CALL、0 tokens、分支证据和 rawUsage:null。其他缺用量轮次为 UNRECORDED，不能猜测为零。

主体错误与恢复错误同时发生时保留 AggregateError 两个 cause，不能用 finally 错误覆盖原失败。失败还记录当前步骤、HTTP/业务码、requestId 和可读到的权威终态；不盲重试未知结果写请求。

## 版本与语料证据

运行快照记录真实 git commit、branch、dirty 状态与状态摘要 SHA-256。promptVersion 为当前 Prompt 与 agent/llm 源码逐文件及集合 SHA-256；pipelineVersion 为 agent-core、ai-service、knowledge-service 的 src/main/java 逐文件及集合 SHA-256。runner、fixture、executor、controls 和 metadata 源码另有 runnerVersion；sourceHash 表达上述落盘源码范围，不声称等于进程已加载版本。

语料通过公开 `GET /knowledge/documents?page=0&size=100` 获取；兼容当前数组接口与 records 分页接口，分页必须取完 total，遇到提前空页、重复 docNo 或 total 变化记 unavailable。逐篇公开详情记录 docNo、version、status、正文 SHA-256、长度和 chunkCount，不把全量原文写入快照。管理员只读 `GET /ai/admin/knowledge/status` 保存运行前后实际 doc/chunk 索引状态；读取失败记 available:false 和原因，禁止填猜测数字。

## 运行

在 frontend 目录执行：

```powershell
node scripts/run-complex-agent-eval.mjs --dry-run
node scripts/run-complex-agent-eval.mjs --only=CA-001,CA-008,CA-010
node scripts/run-complex-agent-eval.mjs
```

`--dry-run` 只校验契约和本地源码 hash，不认证、不调用模型、不读写业务数据；输出 48 条 BLOCKED 并写 contractValidated，PASS 分母不能用它填补。`--read-only` 禁止完整契约所需 setup 写入，因此不会真实执行这些完整场景。未选择 case 也保留在 48 条结果中，标 BLOCKED。真实运行选中场景任一 FAIL/BLOCKED 即非零退出。

COMPLEX_EVAL_GW 默认为 http://127.0.0.1:8080；COMPLEX_EVAL_ADMIN_USERNAME/PASSWORD 默认 admin/123456。买家口令由 runner 随机生成，证据不保存明文。不接受命令行 token。

真实重启 8 例：CA-014、017、018、027、036、038、044、048。控制模块只能从手写服务白名单派生 stop/start 命令，经可信 Git Bash run-local.sh 单服务执行，记录前后 PID、StartTime 和就绪状态；未发生进程替换不能 PASS。

真实故障 3 例：CA-023、024、037。19003 代理方案须先取得用户确认，执行时设置 COMPLEX_EVAL_CONTROL_MODULE 为已审阅本地绝对路径，并显式设置 COMPLEX_EVAL_CONTROL_ENABLE=1、COMPLEX_EVAL_FAULT_STAGING=1。原 order-service 保留 9003，不增加数据库/MQ实例；仅 AI 临时出口指向 loopback 19003。代理按当前隔离 userId、准确方法与权威路径截断，未命中请求原样转发含身份凭证的头；timeout/业务失败/连接失败在转发前注入，不产生未知写入。恢复先把 AI 出口恢复 9003 并等 ready，再释放代理。缺许可、控制模块、真实目标命中或恢复证据时不能宣称已注入。

证据保存 `frontend/.evidence/complex-agent-<runId>.json`，逐轮原子更新，目录已忽略，不提交或上传。SIGINT 停止后续请求，已启故障通过 finally 尝试恢复并保留恢复失败。首次真实全集的失败必须保留，修改后重跑写新 runId，不能篡改旧快照。
