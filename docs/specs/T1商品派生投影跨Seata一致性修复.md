# T1 商品派生投影跨 Seata 一致性修复

日期：2026-10-11至12。状态：投影及属性输入通知修复已部署，两表结构/AT代理检查通过，product100/100、0失败。新commit73已取得真实隔离/自动对齐证据；原capture因日志采集过滤保留BLOCKED，严格原SHA与持留窗日志补证、独立重判及同判定器compare PASS。新rollback/M4尚未执行，不能声明跨AT全门禁已验收。

真实反例为 `seata-phase1-c6b90b21-92d0-46ca-905e-e76934304962-commit.json`：XID尾6975，本地阶段一后21:58:13/16/18三次SQL库存494、ES494，而原TC直到21:58:20才收到GlobalCommit、21:58:22全局提交完成；开始库存495。Outbox屏障PASS，回执未决UNKNOWN/最终COMMITTED；总体FAIL必须保留。订单756随后通过公开cancel正常清理，SQL/ES495、原回执1，独立cleanup绑定原报告SHA，不据此改判原实验。

## 目标与契约

库存、商品、目录、评分、销量及启动重建全部走同一条持久任务链。业务本地事务只登记刷新意图；Seata AT阶段一、未知、回滚失败、TC不可达时不发布缓存或ES。全局回滚删掉该分支的任务，已有投影不变；全局提交后自动重读源并刷新。失败与进程重启不会丢失待办，不要求人工重启或下一次商品变更恢复。

不新增用户行为开关，沿用现有ES启用状态并记录 `CACHE_ONLY` / `CACHE_AND_ES`。不变更交易API、价格、销量或库存算法，不修改旧业务数据；两旧afterCommit直接刷新链路彻底替换。

## 持久状态与边界

新增商品库两张InnoDB表，并提供MySQL/H2同语义初始化及只读兼容核验：

- `product_projection_task`：自增任务id、spu_id、可空global_xid、created_at、completed_at、attempts、last_error_type、next_attempt_at、applied_task_id、发布模式。任务采用追加而非同SPU覆盖合并；业务事务内INSERT，与SKU/SPU同AT分支提交或回滚。任何未决任务不得被调度器改after-image。
- `product_projection_spu_guard`：永久spu_id主键、可空ES版本基数、已成功应用的最大任务id。只由消费者初始化/锁定，不参与业务写者锁链，不因删除商品而删除。任务删除、商品物理删除和实例重启都不使串行闸消失。

任务不是动作快照：旧任务处理时始终重读当前合法源。因此旧删除任务不会把后来恢复的投影删掉。对ES超时、数据库连接丢失后的迟到请求，使用持久元数据加单调任务id生成外部版本，重复采用 `external_gte`；初始化时绑定已有ES `_version`，不写魔术起始值。物理缺失SPU发布不可检索的持久删除标记，保版本闸，避免ES删除墓碑过期后旧请求复活商品。公共检索仍只显示在售商品。

## 消费时序与并发

1. 调度扫描仅取得候选id，不拿未证明的源构建索引。每任务在独立 `READ_COMMITTED`、方法级 `@GlobalLock` 事务内处理；禁止继承业务XID。
2. 锁任务行；通过已有决议端口核对来源XID与商品库undo。只接受已证明终态且undo0；本地任务来源为空时无需TC终态判断。
3. 取得永久SPU屏障行，按确定顺序锁SPU、全部相关SKU、属性值及投影所引用的类目/品牌/属性元数据。生产者只追加任务，不锁消费者屏障，避免SKU→屏障与屏障→SKU锁序互逆。
4. 当前读重查该SPU全部未完成任务及其来源。后来的GT删除/更新可能使源行消失，不能用“SELECT FOR UPDATE没有行”推断可发布；任何未知/失败/尚有undo来源都保留任务并跳过发布。
5. 在锁与已证明来源范围内重读源，完成严格缓存失效与带版本ES更新，成功才标记完成。外部失败可记录脱敏失败类型与下次尝试时间，但只允许修改已最终提交、已解全局锁的任务；未决失败仅日志，不污染AT后像。
6. 全实例同SPU共用数据库屏障，源行锁覆盖采样到外部提交期间；单调外部版本补齐超时/断连后迟到写的保护。多个任务可重复处理，永远重读当前源而非复用旧payload。

TC/undo查询仍经 `OperationReceiptDecisionProbe` 通用端口。将原订单/营销重复的有界线程、超时、取消、undo结算与轮询抽入common的框架无关实现，三个服务保留薄Seata/JDBC适配器；原构造器测试入口与回执状态契约保持兼容。SQL/外部调用有界，关闭时释放查询执行器，调用方中断继续保留中断标志。

## 缓存与启动

旧吞Redis/broker错误的 `evictProductCache` 已移除。消费者采用严格失效入口：本地失效、Redis失效与广播被接受有明确结果，失败保pending，不仅靠TTL/下次写补救。保留既有读降级语义，但任何cache-miss回源及回填也必须经相同稳定源屏障，不能另一路缓存阶段一快照。Redis失效与回填都推进永久版本闸，用十进制字符串长度/字典序原子比较，拒绝晚到旧版本；未知ID零版本不创建永久版本键，L1沿用既有失效纪元保护。

启动和显式全量重建仅登记当前SPU与既有屏障元数据中的目标，消费仍走相同屏障与重读链路；不直接 `saveAll` 绕开全局决议。ES关闭时只完成缓存发布，并明确模式；再次启用时启动任务补齐索引，包括已删除目标。

本地管理写提交后尽力立即唤醒已持久任务，仍经相同worker消费、独立事务、源屏障与版本闸；第一次失败即停止即时批次，保留待办给定时扫描，不能把已提交业务响应改为假失败。有XID不采用本地提交唤醒。启动全量只登记、不同步等待ES，避免外部故障按商品数量串行阻塞服务就绪。商品服务已显式启用定时调度。

## 文件与实施步骤

1. 回归RED：旧 `StockServiceImpl` 本地提交触发ES；`StockProjectionRegistrationTest` 保留真实RED与GREEN。核心测试落在 `ProductProjectionPersistenceTest` / `ProductProjectionTransactionTest` / `ProductProjectionSourcesTest` / `ProductProjectionSnapshotTest` / `ProductProjectionWorkerTest` / `ProductProjectionConcurrencyTest` / `ProductProjectionElasticsearchContractTest` / `ProductProjectionCacheBarrierTest`。H2只证明本地持久/回滚及行锁串行，不能冒充真实AT运行。
2. 公共有界决议实现与三个薄适配器，保持订单/营销既有probe测试入口；单独common及两旧probe回归。
3. 新entity/mapper、永久屏障、任务请求者、独立消费事务与定时扫描、启动登记；MySQL/H2 schema及只读核验。失败日志含task/spu/xid、模式、阶段、耗时、待办状态，不含用户令牌或业务JSON。
4. `StockServiceImpl`、`ProductDerivedRefresh`、`ProductAdminServiceImpl`、`CatalogAdminServiceImpl`、`ProductAggregateService` 全部改事务内登记；库存重复回补不生成新任务。`ProductSyncService.syncOne/syncAll`只登记，受保护发布器承担构建/版本写；移除旧本地afterCommit刷新。缓存严格失效与miss发布同屏障，补外部失败版本保护。
5. 本地Java21定向门禁及真JDBC/Seata解析代理证明；根代理统一协调迁移、冻结、product部署与新真实三组capture。每条验收兑现后再同步README/项目总览/T1主计划。

## 验收与证据

| 标准 | 证据来源 |
|---|---|
| 本地阶段一只登记任务，不发布 | 核心RED→GREEN；同XID商品undo包含任务表，持住原GlobalCommit后三次SQL任务pending、缓存/ES旧值、实际扫描等待日志 |
| TC提交后自动发布且任务完成 | 原帧释放后SKU/SPU/ES一致、task完成/发布版本、原操作COMMITTED、无手工sync/重启 |
| TC回滚删除任务且旧投影不变 | 新独立rollback及M4真实TC/三RM终态、task0/undo0、SQL库存/购物车/券复原、cleanup前ES旧值 |
| 删除缺行与同SPU多任务安全 | 真JDBC并发锁序测试：永久屏障不随SPU删除、旧删除任务重读新源、后续未决来源不发布、旧外部版本不能覆盖新投影 |
| 外部失败及重启不丢 | ES/Redis失败任务pending、失败类型脱敏；新进程自动重试同任务并最终完成，重复不改变业务库存/销量 |
| cache-miss不灌阶段一/晚旧值 | Source未决或TC不可达时无缓存写；较旧版本延迟回填被拒，完成后读到当前合法值 |
| 启动重建没有逃逸路径 | startup候选真实处理也留下同一任务/屏障日志，未决源保旧值并等待，禁止启动直接saveAll |

命令：Java21 `mvn -o -pl product-service,order-service,promotion-service -am clean test`；公共probe定向测试；`node --test scripts/lib/write-receipt-phase1.test.mjs scripts/lib/seata-index-evidence.test.mjs scripts/lib/seata-outbox-evidence.test.mjs`。真JDBC使用已授权本地MySQL及真实Seata代理/解析器，不用H2成功冒充AT运行。

真实capture扩展保留独立的任务SQL、来源XID、ES `_version`、三阶段库存、扫描模式/等待/成功日志，所有after采样在cleanup/cancel之前。原c6FAIL和77参数绑定BLOCKED不覆盖；新证据新runId。回滚代码前必须停止新写并结算所有任务与全局事务，保新增表和已审计数据，不能带pending任务切回旧afterCommit实现。

部署迁移须在全部商品实例停写时完成，不能混跑旧直接发布代码与新外部版本闸；旧无版本写可以覆盖ES或绕过Redis闸。本次由根代理冻结后统一部署，不靠长期人工维持一致。调度器等ApplicationReadyEvent后才消费，保证ApplicationRunner只读结构/实际AT代理核验先完成。

## 已取得与未取得的证据

- 公共有界probe抽取：原订单/营销probe兼容测试通过，日志 `seata-shared-probe-compatible-green-20261011.log`，未改变原回执契约。
- 库存注册RED→GREEN及幂等/真实Seata拦截器：Java21 `-pl product-service -am -Dtest=StockProjectionRegistrationTest,StockRestoreIdempotencyTest,StockSeataInterceptorInvocationTest -Dsurefire.failIfNoSpecifiedTests=false test`，22:57:18共5项0失败。新增投影测试在此之后添加，不能引用该日志声称新增全部通过。
- `verify-product-projection-versions.mjs` 用生产Lua原文与独立ES索引真实验证4/4，最新报告 `product-projection-versions-fffa5f65-8a1c-4850-a125-747107bc2058.json`；覆盖超过2^53的相邻bigint、跨位数失效、ES拒迟到旧版本、永久墓碑拒复活。独立key与索引已清理。另有实际Spring Data Template→client请求契约证明version=103/ExternalGte、读取ES元_version91；生产HTTP请求仍需新capture验证。
- 新fresh与M4强制 `projectionContract=durable-source-v1`，开始前永久版本闸未就绪或该商品有未完成任务即BLOCKED，不发checkout。during须来源任务唯一且attempts0/completedNULL、ES版本不变；commit须任务完成及永久闸/ES实际版本精确一致；rollback来源任务0及ES版本不变。相邻Node判定21/21通过，旧报告不改。
- 23:52:32定向v3实际退出0：product42（新增投影24项）、order旧probe3、promotion旧probe2全部通过；order另一真实库存并发用例1跳过，不计通过。23:54:13全量common58/contract1/product95均0失败/0错误/0跳过，日志 `product-projection-targeted-v3-20261011.log` / `product-projection-full-green-20261011.log`。两个测试夹具编译FAIL原日志保留，修的是Long参数与checked异常声明，不改产品迁就测试。
- 10月12日同根输入遗漏已固定：旧 `updateAttribute` 仅改名登记，但 `inputType` 改变ES属性拆分、`unit` 改变缓存详情。成功更新后一律按实际引用SPU登记持久刷新，目标去重；create没有既有取值、delete有引用拒绝及无引用无副本变化三边界保持。00:24:47 Catalog14项仅同名unit/inputType两项真实RED（字段已更新、刷新调用0）；00:29:56新代码14/14 GREEN；00:33:03 product100/100、0失败/0错误/0跳过。日志 `product-attribute-source-red-20261012.log` / `product-attribute-source-green-20261012.log` / `product-attribute-full-green-20261012.log`。测试未改共享模块或控制服务，运行进程部署由根代理随后完成。
- 根代理已完成真实两表DDL、结构/InnoDB与实际AT连接代理检查，九服务就绪。启动54任务出现ES超时保留待办及后续完成日志；是否全部自动恢复以根代理只读结算为准，不靠人工sync补绿。
- 尚未覆盖：真实GT未决下的AT源锁/决议发布屏障、MySQL多worker场景、生产HTTP版本参数、完整ES/Redis故障恢复、真实commit/rollback/M4与最终联合重启。以上由根代理冻结后新run逐项结算。

## 新commit73真实兑约（10月12日）

runId `73c33686-c4fa-4cf8-9e41-95478af0a008`，原XID尾6978、SKU29/SPU20、task109、商品PID462496。before SQL/ES495、ES_version210；原GlobalCommit持留16:46:18.110Z至16:46:32.149Z，三次SQL494而ES495/version210不变，task109始终attempts0/completedNULL。TC提交后SQL/ES494、ES_version245=base136+lastApplied109，task109 CACHE_AND_ES完成；原token未决UNKNOWN、最终COMMITTED、零模型，原Outbox屏障及一次发送PASS。

原capture的唯一BLOCKED来自 `phase1LogEvidence` 旧AT分支正则过滤掉 `[商品投影]`；原JSON不改，SHA256 `9c197ad801607008a58772a9bdb03cdc7ac4e8564c6799cead1aaa2033cc8ce0`。完整源日志在原持留窗内实际有8条同PID/task/XID等待，记录源日志读取时间与不可变快照SHA，单独保存 `seata-projection-waiting-73c33686-c4fa-4cf8-9e41-95478af0a008.json` 和独立重判。`seata-projection-commit-73c33686-c4fa-4cf8-9e41-95478af0a008-index.json` 显式引用原capture及expectedCaptureDigest、projectionSupplement；最终同判定器compare PASS，不把复制品当新capture。

比较器强校原路径/SHA/runId/startedAt/XID/原帧id/digest、原商品PID/task、源日志SHA/读取时间及严格held→released时间窗。原FAIL、错绑定、窗外提交后waiting均拒绿；采集器和补证邻组24/24通过。后续capture从最终日志回收等待记录，判定仍严格限定原持留窗，不以最终完成替代未决真实执行。

订单757随后使用自有token公开cancel，独立cleanup绑定原SHA；订单CANCELLED，唯一RESTORE1701 qty1/494→495，SQL/SPU/ES495、pending0。原after采样冻结在cleanup前，没有手工sync或商品重启补绿。无券rollback与有券M4仍需独立新fixture/新run。
