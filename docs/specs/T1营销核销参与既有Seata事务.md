# T1 营销核销参与既有 Seata 事务

日期：2026-10-11。状态：**用户已授权实施，营销AT源码、undo_log迁移和Java21定向门禁已完成；真实三服务专项正在验收，不构成生产收口**。

17:49:20 本地 `mvn -o -pl promotion-service,order-service,product-service -am test` BUILD SUCCESS：common50/contract1/product67/order70（4跳过）/promotion19，0失败/错误。迁移前只读确认营销undo_log不存在，批准DDL创建后结构与商品库一致、InnoDB/7列/联合唯一键/0行；证据在已忽略 `.evidence/promotion-seata-undo-migration-20261011.txt`，Maven日志同目录 `promotion-seata-maven-20261011.log`。当前营销增加既有starter、公开高级参数、仅追加undo schema、回执GlobalLock和启动只读结构/实际ConnectionProxy核验。

## 已复现问题

真实实验发现两个共同链路问题：同一用户的第二笔失败订单会无条件退还第一笔已用券；该项已通过把退券绑定原核销订单号修复，五条调用路径同步调整，Java 21 订单/营销回归通过。

另一个失败样本证明营销没有加入现有全局事务：2026-10-11 17:06:21，订单读核销接口超时，在本次核销尚未提交时调用退券；新条件 UPDATE 正确跳过。随后营销迟到的核销在 17:06:21.782 提交，订单全局事务在 17:06:21.982 回滚。结果是没有订单但券留下 USED 和本次订单号。该独立实验用户、券及失败证据保留，不自动修改历史事实。

已核对依据：`promotion-service/pom.xml` 没有 Seata starter，`application.yml` 没有 Seata 配置；`redeem/unredeem` 只使用本地 `@Transactional`。order/product 使用现有 `spring-cloud-starter-alibaba-seata`、Seata 2.5.0、`envoymart_tx_group` 和 TC `127.0.0.1:8091`。真实日志已记录 order 的 Rollbacked 与 product AT 分支回滚；因此不是全局事务从未运行，而是营销不是参与方。

## 完整方案与契约

将营销加入**已有 Seata AT**，保持三服务使用同一 TC/group 与现有 Spring Cloud Alibaba BOM，不引入新的事务框架。订单作为 TM 发起全局事务，核销调用通过现有 Seata Feign 拦截器传播 XID，营销的 Jakarta Servlet 拦截器绑定 XID，本地事务使用 DataSourceProxy 写 undo_log 并注册 AT 分支。

核销提前提交、订单后来失败：TC 回滚营销分支，恢复用户券原状态。核销在全局事务已回滚后迟到：branch register 被 TC 拒绝，本地事务回滚，不能提交孤儿 USED。HTTP 超时不再靠无结果确认的“退一下”保证全局一致性。原订单号退券约束仍保留，它保护普通取消、关单等未处于 checkout 全局事务的路径，以及多个订单复用同一张用户券的归属。

营销本地 `coupon_receive` 仍是本地事务和本地成功回执，不为单库动作主动创建全局事务。它收到合法内部 XID 时才作为参与方；外部 API/网关必须保有身份和内部认证边界，不扩大未经授权的事务控制能力。

若 `SEATA_ENABLED=false`，这是明确的降级部署：只能验证普通本地回执，**不能宣称跨订单/商品/营销原子回滚或通过迟到核销验收**。启动日志报告当前模式和 TC 连接/分支能力；不得把无 Seata 当本专项 PASS。

## 结构与公开高级配置

只新增营销库 `undo_log`，DDL 在同目录 `T1营销Seata-拟议SQL.sql`，结构直接对齐已运行 product 的 `SHOW CREATE TABLE undo_log`：branch_id、xid、context、rollback_info、log_status、log_created、log_modified，联合唯一 `(xid,branch_id)`，InnoDB。不修改 coupon、user_coupon、promotion_operation_receipt 或其他旧表；不改已有券、订单或失败样本。

营销沿用已有公开高级环境配置：`SEATA_ENABLED`（默认 true）、`SEATA_SERVER`（默认 `127.0.0.1:8091`）；application-id 取 spring.application.name、tx-service-group 为 envoymart_tx_group。此前这两项只被 order/product 消费，此次扩展到营销，必须在 README/启动文档明确标注，不作为隐藏开关塞入配置。

用户批准后，先创建营销 undo_log，再部署依赖与配置，最后串行重启营销、订单验证。已有两张成功回执表不重新迁移。不自动启动新的 TC/MQ/数据库，不修改环境文件、权限或 CI；密钥、内部令牌一律沿现有受信运行环境，不读出到文档或日志。

## Spring Boot 4 / 依赖兼容约束

- 引用与 order/product 完全相同的 `com.alibaba.cloud:spring-cloud-starter-alibaba-seata`，不写另一份版本号；缓存里现有依赖可供离线 Maven 验证。
- 当前已启动的 order/product 使用 Seata 2.5.0 与 SCA 2025.1.0.0，`SeataHandlerInterceptor` 实现 jakarta.servlet，适配现有 Boot 4。必须检查装配的是该 Jakarta 实现，而非老 javax.servlet 的 TransactionPropagationInterceptor。
- 不复刻 order 的启动 `Class.forName` 预热列表到营销；预热不是语义保证。没有新反射/AOT 注册要求，项目当前 JVM 模式没有 native-image/AOT 配置；只将实际发现的装配或兼容问题作为必要修改，不为未知风险新增反射配置。
- 营销核销/退券由既有本地事务覆盖 Mapper 写入，Seata 代理必须包住真正使用的数据源。验收依赖真实 XID、branch register、undo_log 和 AT rollback，不仅看启动“Seata enabled”。
- promotion 回执查询需与 order 一样使用 `@GlobalLock + SELECT FOR UPDATE`，覆盖营销写接口被合法内部全局事务调用的情况。没有 XID 的普通领券仍由本地 commit 判定。新增回执 JSON 的 undo 编码必须实际测试，不能凭 H2 成功推断 Seata JSON 可回滚。

## 文件计划

1. 修改 `backend/promotion-service/pom.xml`，加入现有 Seata starter；修改该服务 `application.yml`，公开高级配置与已有两服务对齐。
2. 修改 `backend/promotion-service/src/main/resources/schema.sql` 或 MySQL 平台初始化文件，新增兼容建表与启动只读结构核验。MySQL 已执行 DDL 后启动不得覆盖、自动删除或修改结构；同名不兼容表明确阻断。
3. 修改营销 `service/OperationReceiptService.java` 的回执重放事务读门；核销/退券的原 orderNo 条件不撤销。
4. 补注册 XID、分支失败、本地回滚关键回归；Java 21 离线门禁覆盖 promotion/order/product 与公共模块。同步 README、项目总览、T1 计划与面试材料的事实口径，简历仅项目经历。

## 验收标准与证据

| 条目 | 标准 | 证据 |
|---|---|---|
| M1 | 三服务加入同一 XID，营销实际注册 AT 分支 | 同 requestId/XID 的 order/product/promotion 日志、营销 undo_log 只读行、resourceId 指向真实 MySQL |
| M2 | 首单正常用券后，另一失败订单不退首单券 | 保留已有 FAIL；新独立用户 fixture 首单 USED+原 orderNo 在失败后不变；错单/重复/错用户/空号自动化回归 |
| M3 | 订单等待核销超时后，迟到核销不能留下孤儿券 | 独立有效 UNUSED 券行只读持锁，确证核销 UPDATE 等待；超时后全局回滚，释放锁，等待所有分支结束，券 UNUSED/orderNo null、订单/回执0、库存/购物车复原 |
| M4 | 先核销成功、后续 checkout 失败，会把营销分支回滚 | 同 XID undo 与日志，失败后券原状态、发行量不变；只靠应用补偿没有 AT 日志不算通过 |
| M5 | 无全局事务的普通 API/领券回执兼容 | 八写真实 API 捕获与重放、无操作头对照；营销跨真实重启回执同 JSON |
| M6 | 成功回执在全局未决时不能提前重放，回滚后无成功回执 | 独立可审 Seata TCP 代理持住**业务 body 的 typeCode=7 GlobalCommitRequest**，数据库证明本地阶段一已提交、原回执读取按全局锁保持未知；TC提交/超时回滚后对应原操作取回/不存在 |

命令：`mvn -o -pl promotion-service,order-service,product-service -am test`；真实实验使用已批准独立账号及 `frontend/scripts/verify-write-receipt-seata.mjs`，无需模型判断结果。M6 需要另外批准 order 临时 `SEATA_SERVER=127.0.0.1:19091` 代理出口，**本规格批准营销事务接入不等同于批准该临时环境变更**。

## 存量、回滚与限制

独立样本9091已获本任务清理授权：只读核对用户、USED、原orderNo及对应订单0后，以id/user/orderNo/status和订单不存在为联合条件恢复本任务券；没有批改其他历史。原FAIL证据保留，修复前后取证分别在 `.evidence/seata-9091-cleanup-before-20261011.txt` 与 `seata-9091-cleanup-after-20261011.txt`。生产存量仍须按“user_coupon.order_no存在对应用户有效订单”只读审计，不能凭总数批量清理。

回滚保留营销 undo_log、回执表和业务数据，不 DROP。回滚前停止 Agent 新写意图、等待正在运行的全局事务全部决议，不能把带未完成 undo 的分支切回不受 Seata 管理的代码。撤代码/配置时明确降级保证，原 orderNo 约束继续保留；旧接口调用方缺 orderNo 必须拒绝，不提供吞字段的兼容捷径。

## 可执行验收入口与临时 19091 出口的单独审批

营销接入和临时出口是两个独立审批目标。营销接入尚未批准时，可审阅脚本并跑本地纯判定/协议自测；不执行营销 DDL、依赖/配置改动或真实券超时实验。临时出口尚未批准时，不启动 19091。

19091 实验的影响范围仅为 order 当前进程的 `SEATA_SERVER=127.0.0.1:19091`，由主代理串行控制重启；product、promotion 保持真实 TC 8091。透明代理仅绑定本次隔离用户 checkout 的真实 XID，在原 TC 8091 前缓留原 `GlobalCommitRequest` 字节；其他 XID、注册、分支、回滚和心跳保持原样。恢复方式：释放自有缓留帧，确认该 XID/undo 已决议，停止自有代理，再由主代理恢复 order `127.0.0.1:8091` 并真实重启、检查就绪；不修改环境文件、不停 TC、不删除实验数据。

协议依据为已缓存 `org/apache/seata/seata-all/2.5.0` 的 `javap`：`ProtocolConstants` 规定 magic DADA、版本1、头16字节和单帧最大8388608；transport 0/1 是请求/响应，3/4是无业务body心跳；`SeataSerializerV1` 写入业务 short typeCode；`AbstractGlobalEndRequestCodec` 顺序写入 short XID长度、UTF-8 XID、short extra长度、extra字节。只识别 codec=1、compressor=0 的独立 body type7 提交；未知codec/压缩/merged透明传输但记录未识别，不借此宣称覆盖。缓留最多32个同XID重试/8MiB，显式释放后解除绑定，迟到重试不再缓留；连接丢失按各原帧的原连接记录，其他连接重试仍可释放。

### 回执阶段一窗口（不使用优惠券）

`CheckoutTool` 当前契约不支持优惠券参数，不能让模型伪造该能力。`verify-write-receipt-phase1.mjs` 使用独立用户、唯一SKU/购物车以及本地可信收货输入，真实模型仅签发一次唯一 `cart_checkout`；核对签名数据中的工具、原op与收货字段，不执行模型载荷中的任何命令。确认及恢复使用同token，`retry=false` 的恢复通过既有 AI 受信 Feign 调用内部只读回执 GET；无需读取或提供内部token。

1. `cd frontend`，运行 `node scripts/verify-write-receipt-phase1.mjs --prepare`。每个commit/rollback模式独立prepare；私有凭据/签名仅落 `.evidence` 已忽略文件，公开证据仅存指纹、op、模式和状态。
2. 获临时出口批准后，主代理设置 `SEATA_PHASE1_ENABLE=1`，运行 `node scripts/verify-write-receipt-phase1.mjs --capture --mode=commit --fixture=<绝对private路径> --activate-evidence=<绝对就绪证据路径>`。脚本监听19091并输出 `WAITING_ORDER_READY`；主代理将order临时出口切到19091、真实重启就绪后写入证据 `{runId,ready:true,pid,seataServer:"127.0.0.1:19091"}`。文件必须本次runId匹配；等待最大5分钟，未兑现则BLOCKED/非0。
3. 脚本仅对实验SKU `SELECT FOR UPDATE` 持锁，confirm原token后从同requestId order `Begin new global transaction [XID]` 取得真实XID并arm，然后释放SKU锁。捕获同XID原提交帧才开始结算阶段一证据：订单完整回执1、订单1、order/product同XID undo；原token只读recover必须UNKNOWN、同messageId/turnId/op且审批日志零模型。仅持锁、仅SQL可见成功行、仅UI未知均不足以通过。
4. commit模式缓留上限20秒，观测后立即释放原帧；必须有真实全局提交状态、undo结算、原回执重放COMMITTED、库存只扣1、购物车清空。rollback模式使用另一个独立fixture、`--mode=rollback`，缓留上限70秒，等待真实TM/TC超时回滚；要求订单/回执0、库存购物车恢复、同XID全局和AT分支回滚、recover为NOT_FOUND。未观察到自然回滚不得用手工改数据代替；自动释放后仍未覆盖则BLOCKED/非0。
5. capture还必须提供 `--restore-evidence=<绝对恢复证据路径>`。脚本正常/异常路径释放自有行锁/原提交帧，等待已发确认请求结算，输出 `WAITING_ORDER_RESTORE` 并维持透明代理；主代理恢复order8091、真实重启就绪后写 `{runId,ready:true,pid,seataServer:"127.0.0.1:8091"}`，脚本才停止监听并最多2秒排空。5分钟未兑现恢复就绪明确FAIL/非0并报告当前配置风险；失败/未覆盖不会假PASS。当前真实capture逐项留在 `.evidence`，不把自测当运行证明。

### 有券超时与迟到营销分支

新增可执行 `node scripts/verify-write-receipt-seata.mjs --prepare-unused`，通过公开业务API准备新用户、有效UNUSED券、购物车，不生成首笔已核销订单。营销实际AT接入且获真实实验授权后设置 `SEATA_VERIFY_ENABLE=1`，运行 `node scripts/verify-write-receipt-seata-late-coupon.mjs --capture --fixture=<绝对private路径>`。

入口预检真实营销undo表；表缺失即BLOCKED，不发checkout。实验只持本次券行读锁，必须取得UPDATE实际等待与同requestId checkout XID，保持到原checkout失败/传输超时后释放。结算要求UNUSED/空orderNo、订单回执0、库存购物车恢复，并有同XID全局回滚、商品AT回滚以及营销迟到注册拒绝或营销AT回滚日志；缺任一证据都是BLOCKED/非0。原9091孤儿样本不改；旧USED首单保护入口保留它自己的BLOCKED事实，不拿它代替该UNUSED超时实验。

本地自测命令：`node --test scripts/lib/seata-commit-proxy.test.mjs scripts/lib/write-receipt-phase1.test.mjs scripts/lib/write-receipt-seata.test.mjs`。只验证协议路由和证据判定，不创建19091、不执行SQL、不触发真实模型或重启。真实专项和最终联合重启仍由主代理完成；本规格和自测不构成生产验收完成声明。

M4使用独立UNUSED券前提和 `verify-write-receipt-seata-coupon-rollback.mjs --capture --fixture=<绝对路径> --activate-evidence=<绝对路径> --restore-evidence=<绝对路径>`（同 `SEATA_PHASE1_ENABLE=1` 控制）。只对本次SKU读锁取得真实XID后arm；原提交帧缓留时须证实order/product/promotion三库同XID undo、完整成功回执和本订单号绑定的USED券，之后必须自然TC回滚并恢复券/库存/购物车/订单/回执。营销预先核销的事实和它的AT回滚日志缺一不可，普通应用退券不得代替；order恢复8091就绪后才关闭代理。

M2另用 `verify-write-receipt-first-coupon.mjs --prepare` 准备独立首单已核销券，之后 `SEATA_VERIFY_ENABLE=1 node scripts/verify-write-receipt-first-coupon.mjs --capture --fixture=<绝对新private路径>`。不调用模型、不持券锁、不要求已用券进入UPDATE等待：第二操作必须409、首单仍有效、券保持USED和原订单号，第二操作订单/回执0、库存/购物车复原，且同XID真实全局/商品AT回滚与三库undo结算齐全。新报告单独通过 `--compare=<公开证据路径>` 重新判定，不改变commit/rollback/late/couponrollback四项索引；原USED实验的BLOCKED报告保留，不以重写报告消除错误实验前提。

## 真实验收进度（投影修复实施中）

- 营销进程303200在17:55:37记录实际AT代理和undo结构通过；独立9095核销实际注册营销AT分支（XID尾6952）。这证明营销参与方真实执行，不能只靠启动配置推断。
- 阶段一提交5227：XID尾6949，原55字节type7提交帧SHA256/id11已缓留；MySQL成功回执1和order/product同XID undo可见，原token只读recover为UNKNOWN/IN_FLIGHT、零模型；显式释放后undo结算，原token恢复COMMITTED/replayed=true、零模型，库存仅扣1、购物车清空，order305340恢复8091后关闭代理。原报告因验收事件kind覆盖bug保留BLOCKED；独立 `.reassessed.json` 仅纠正已存原事件标签并绑定原报告SHA256，判定PASS，不补造事务事实。该bug已实际红绿回归固定。
- 迟到核销40c71b17：独立UNUSED券9096/XID尾6956，真实UPDATE锁等待、原checkout超时全局回滚后，迟到营销分支注册被TC明确拒绝并本地撤销；券UNUSED/空orderNo、订单回执0、库存购物车复原，同XID TC全局终态与商品AT回滚齐，PASS。原9095实验由于4.5秒观察窗口早于核销抵达保留BLOCKED，观察改15秒后使用新fixture复验；原实验成功ownorder749通过公开取消API清理，券恢复，无批改历史。
- 无券阶段一b5d51a51/XID尾6959真实失败：阶段一事件516提前发送，发件箱attempts由0变1，AT校验发现脏后像，TC停留TimeoutRollbackFailed；原FAIL报告永久保留。之后订单751在来源缺失、类级GlobalLock没有被Seata方法扫描器识别的旧进程中被超时关闭，产生库存回补1688、关闭流水2226和已发送补偿事件517。不能把后续维护恢复写成原实验通过。
- 主代理与独立评审已核对精确维护方案：库存原undo495→494叠加两条既有回补，重基为497→496；订单只调整最新UPDATE的after.status为实际CLOSED，原INSERT/before及其余字段不变；事件516仅attempts1→0，保已发送/消费事实。两份原blob、关闭2226完整行及所有同订单号Outbox/日志已归档并绑定SHA256。19:48维护停机下精确回填751.global_xid与39.global_xid/GLOBAL_PENDING，两条更新事务内匹配数各1；业务库存仍496，undo/TC尚未修改。
- 方法级事务锁真实Seata解析器/Adapter/Spring连接前及提交旗标回归36/36通过，新order/product/promotion三RM已部署。20:14精确恢复两份undo及事件516的attempts，匹配数各1；官方Console入口实报IllegalArgumentException，已定位现TC的FAIL_ROLLBACK_STATUS错误包含TimeoutRollbacked、遗漏TimeoutRollbackFailed，原BLOCKED保留。经主代理独立审阅源码与哈希的一次性V2工具，调用Console内部同一条官方DefaultCoordinator.doGlobalRollback(session,true)，不修改TC状态、允许列表或store；20:19:26两分支PhaseTwo_Rollbacked、TC全局回滚成功、全局会话Finished。20:20:01完整15项只读结算PASS：库存497，原订单/回执/明细/创建状态/事件/扣减流水/两undo0，购物车恢复，原1687/1688/已发送517保留。
- 回滚后的独立补偿首次被精确CAS拒绝，因为另一次已授权八写capture在同SKU产生合法1690扣减、1691取消回补、1692有效订单753消费，净库存变化-1；没有放宽CAS或取消合法业务凑库存，原拒绝及结算FAIL保留。另建会计扩展绑定三条原流水完整bytes/hash、752CANCELLED/753RECEIVED来源和该次53项capture的SHA，原恢复manifest及undo不变。20:33执行同唯一MANUAL键纠正非法1688一件，exactCAS496→495、审计流水496→495和已归档完整SHA孤儿2226删除事务内匹配各1；after完整15项PASS。重复执行日志仍1、库存495、无任何新增写入。该维护恢复不能覆盖原b5自然回滚FAIL。
- 20:34独立维护索引取证显示SQL SKU/SPU汇总495、ES496，原因是上述人工维护SQL没有投递业务索引事件。只允许维护基线的一次product重启对齐；之后新M2/commit/rollback/M4不得人工sync或重启补绿，必须证明自动链路结算。
- 一次维护product重启后的SQL/ES基线均495。新M2独立用户/券9098/首单754，第二操作XID尾6972、checkout409，首单前后CREATED、券前后USED且原订单号不变，库存前后494、购物车不变、第二操作订单/回执0、同XID三库undo0及TC/商品AT回滚终态齐全。新capture与离线compare均PASS，公开原报告SHA256为502326c2fddc06296e51662b2dce2ba73982e42e35afb3e67fbd76f3c68f6652。随后使用自有原token通过公开cancel首单，独立cleanup报告PASS并绑定原SHA，消除15分钟自动关单对后续同SKU实验的干扰，不修改原PASS报告或合法753。
- M2取消后的一次只读ES请求4秒超时保留为观测BLOCKED；后续只读读取SQL/ES均495，未再人工同步或重启。此时点只能证明正常取消后对齐，不能倒推此前第二操作AT回滚前的索引状态；M2PASS不包含该索引证明。新commit/rollback/M4必须在任何cleanup/cancel/resync前采样ES与SQL并独立判定。
- 新commit/rollback/M4全部需要新隔离用户和原报告，增加未决跨至少两个调度周期三次Outbox SQL采样、实际屏障等待日志、消费者事实，以及ES与SKU/SPU库存before/during/after。未决ES保持原提交库存，最终仅靠系统自动链路对齐；不使用人工sync补绿。纯只读compare独立重新计算这些标准，旧5227缺新屏障/索引证据只能BLOCKED，不能拿历史重判PASS充当新全门禁。
- M4已核销营销阶段一AT回滚、新无券commit/rollback、最终联合重启仍待结算。不得据此声称M1–M6全部通过或平台可生产交付。

### 20:43之后新增事实

- 原77 fresh在checkout前BLOCKED：order内部回执GET缺显式Spring参数名，剥离反射参数元数据后真实MockMvc复现400。order/promotion两个Controller已统一补显式路径及查询参数名，三Feign调用方已核对；Java21两服务全量common58/contract1/order101（4跳过）/promotion26，0失败。真实原77 token只读recover得到NOT_FOUND、内部GET200；原营销approval token只读recover得到COMMITTED/replayed、GET200。原BLOCKED不改。
- 新c6 commit/XID尾6975真实证明投影阶段一泄漏：三次SQL494、ES494发生在TC实际接收GlobalCommit之前，原Outbox屏障PASS但总体FAIL。原token未决UNKNOWN、最终COMMITTED。公开cancel订单756正常清理后SQL/ES495、RESTORE一次；cleanup不改变原FAIL结论。
- 新commit/rollback/M4暂停，商品持久投影共同根因修复已授权实施，唯一详细规格为 `T1商品派生投影跨Seata一致性修复.md`。业务任务同AT提交/回滚，稳定源消费者核验决议/undo及全部源锁后发布；永久版本闸、ES墓碑、Redis原子版本与cache-miss屏障覆盖迟到请求和重启恢复。
- 原b5固定维护源码已迁入ignored `.evidence/maintenance-b5/scripts/`，相对imports/ignored检查及rebase测试通过，不把现场维护身份提交公开仓库。
- 商品源码23:54全量common58/contract1/product95、0失败；新增投影24项、两旧probe兼容门、生产Lua与独立ES真实版本实验4/4通过。启动共享ready门同时覆盖worker和cache-miss，管理update/delete先锁全部既有源，与消费者同序。10月12日两表与投影新服务已由根代理真实部署、结构/AT代理检查通过；属性同名改单位/输入类型漏通知已补，00:33 product100/100通过，运行商品进程还待部署该小分支。真实AT新run尚未结算，当前不得声称Seata四项门禁全部通过。

本地新增守卫的全局锁上下文回归在17:54:05通过：common50/contract1/promotion20，0失败；启动线程只读检查临时绑定GlobalLock，finally恢复原标志，无伪造XID。TC取证只读限定 `docker logs --since 15m --tail 2000 envoymart-seata`，只保留同XID明确全局终态；异XID、只有分支日志或进行中状态不计PASS。
