# 医学严格证据闸门实施计划

> 当前状态（2026-10-12）：医学仍未达标。最新正式run `b201caa7-d6de-4abd-b87b-353c8f428d6d` 已完成36题、执行失败0，原18条多跳13/18；原0/65存在独立漏审，不能判通过。列表结构22/22、G17来源框架单方法1/1、图谱工具规范13/13专项已验证，当前阶段提交纳入这些修复，尚未部署真实复验。症状处置与版本裁定漏审仍待专项，全部专项通过后才运行最后全量；唯一当前账本为 [T1实施计划](../../待办/T1-平台全功能与复杂Agent一次性收口实施计划.md) 顶部S1–S6。下文旧run和门禁均为历史证据。

**Goal:** 对药品、营养品和医学相互作用问题实施实体/关系/证据片段一致校验，同时满足真实回答未支撑事实句0/N、强来源必要要点完整，以及原18条旧`multiHopHit`严格大于85%（至少16/18，即88.9%）。事实分母N随最终回答动态统计；85曾是历史轮次事实句读数，不是固定分母。不能删除用例、改金标或旧指标公式，也不能靠增加拒答达到多跳门槛。

**Architecture:** 新增纯确定性的 `MedicalEvidenceVerifier`，由用户问题和证据判断是否进入医学严格模式，并逐句检查引用切片是否同时覆盖实体、关系词和关键风险词；无法证明的句子直接剔除。生产 `Agent` 与离线/live `GroundingEvaluator` 共用同一判定，避免两套口径。

**Tech Stack:** Java 21、agent-core、JUnit 5、Spring Boot ai-service live eval、现有 `DocumentChunk`/`CitationVerifier`。

---

### Task 1: 固化四类失败并扩展评测样本上下文

**Files:**
- Modify: `backend/agent-core/src/main/java/yumefusaka/envoymart/agent/rag/GroundingEvaluator.java`
- Modify: `backend/agent-core/src/main/java/yumefusaka/envoymart/agent/rag/GroundingEvalRunner.java`
- Modify: `backend/ai-service/src/main/java/yumefusaka/envoymart/aiservice/eval/GroundingLiveEvalService.java`
- Create: `backend/agent-core/src/test/java/yumefusaka/envoymart/agent/rag/MedicalEvidenceVerifierTest.java`

- [x] `Sample` 增加可选 `userMessage`，保留原有 8/9/10 参数构造器。
- [x] 离线与 live 均传问题原文。
- [x] 固化错切片、全局否定、跨商品迁移、无图谱证据等四类真实失败。
- [x] 用回归测试及独立评审反例复核；新增反例包括拼接引用、实体迁移、粒/IU 剂量、方向反转、无引用与越界引用。

### Task 2: 实现医学实体/关系/证据一致校验

**Files:**
- Create: `backend/agent-core/src/main/java/yumefusaka/envoymart/agent/rag/MedicalEvidenceVerifier.java`
- Modify: `backend/agent-core/src/main/java/yumefusaka/envoymart/agent/rag/GroundingEvaluator.java`
- Modify: `backend/agent-core/src/main/java/yumefusaka/envoymart/agent/rag/CitationVerifier.java`

- [x] 用医学实体及用药上下文进入严格模式；订单风险不误触。
- [x] 一个引用切片同时覆盖句中实体、关系类别、方向与数字单位，禁止把多个切片拼成证据。
- [x] 局部切片不能证明整份资料不存在内容。
- [x] 明确图谱声明须由支持该句的图谱切片证明；普通医学关系不强制图谱来源。
- [x] 明确医学事实无有效引用即拒绝；纯咨询/遵医嘱提示保留。该启发式不构成完整语义或医学正确性证明。

### Task 3: 接入生产后置校验并保持回答可用

**Files:**
- Modify: `backend/agent-core/src/main/java/yumefusaka/envoymart/agent/core/Agent.java`
- Modify: `backend/agent-core/src/main/java/yumefusaka/envoymart/agent/rag/SemanticGroundingVerifier.java`
- Modify: `backend/agent-core/src/test/java/yumefusaka/envoymart/agent/rag/MedicalEvidenceVerifierTest.java`

- [x] 语义审核/引用修复后、普通引用校验前接闸门。
- [x] 拒绝句合并且 `unsupportedStripped` 同步；全剔除与模型空答固定拒答。
- [x] 日志记录拒绝数与原因类型，沿用 MDC requestId，不输出被拒原句或敏感 token。
- [x] 医学正文审核后才发布；工具进度实时保留；前端 `done.reply` 空字符串不恢复旧 delta。

### Task 4: 定向验证与真实评测（历史阶段）

**Files:**
- Modify: `backend/agent-core/src/test/java/yumefusaka/envoymart/agent/rag/GroundingQualityTest.java`
- Modify: `EnvoyMart/docs/待办/T1-平台全功能与复杂Agent一次性收口实施计划.md`

- [x] 相邻全量 `mvn -o -pl agent-core,ai-service -am test`：common 49、contract 1、agent-core 407（6 跳过）、ai 219（1 跳过），0 失败。
- [x] 前端 `pnpm type-check`、`pnpm build`、目标文件 eslint；医学正文浏览器回归 6/6。
- [x] 最新 class 重启，真实 runId `4bdf3e1f-2b0d-40dd-bfdf-b2fe314cf05d`，pipeline `agent-live-v4-medical-evidence`。
- [x] 36/36、0 调用失败；0/76 未支撑，引用 100%、证据门 97.22%（过拒 1/36）、多跳 18/18、越界 0。
- [x] 旧夹具原始失败答案未删除/改写；2026-10-10 判定基线为 11/44、错误引用 6/33。2026-10-11 按统一证据门逐引用核对后为 **13/45、错误引用 8/34**：G14「超量可能引起血钙升高…[3]」的切片分数 0.1943、G18「长期大剂量叶酸可能掩盖 B12 缺乏…[2]」的切片分数 0.1824 均低于 0.20，新增两个未支撑和错误引用，G18 新增一个事实分母。逐条事实分母/未支撑/引用检查/错误引用锁定在 `GroundingQualityTest`；图谱未收录不等于安全和纯遵医嘱提醒均保留，不计医学事实。

## 结论边界

零未支撑句仅适用于该次样本、该判定器和最终交付文本。词表、关系类别与同义转述仍有漏检/误拒边界，证据内容权威性也不能由引用匹配证明。0/76 不能证明总体医学风险低于 0.001；即使假设独立同分布且检测完备，零失败的单侧 95% 上界约 3.87%，需至少 2995 个独立样本零失败才达到 0.1% 上界，且仍须独立留出集与专业审核。

### Task 5：真实样本暴露的省略主体与关系条件

**历史真实复核（2026-10-11 21:41）：** 53项定向与独立复审通过后，run `7d12c0a4-3cf4-4d47-9b00-c4eee8c256dc` 完成36/36、执行失败0，当时判定器0/70、引用100%、多跳15/18、过拒1；随后发现省略主体短结论漏审，因此该读数不是最终达标证据，原报告保留。

- [x] 将关系存在、否定与省略谓语纳入生产审核和评测的共同事实识别；孤立的“会相互影响”“会影响”等不得从问题或引用原文猜主体并放行。真实RED63项7失败与新增边界RED69项5失败保留，定向与全core验证通过。
- [x] 完整双主体正向、空主体、未知主体、否定反转及同段已核验主体继承形成确定性正反回归；证据源为上述原始run与`MedicalEvidenceVerifierTest`、`GroundingQualityTest`。泛化双主体要求按分句绑定，具体单主体风险保持正向。
- [ ] 对G-13、KG-03核对铁与左旋多巴的间隔条件来自同一关系证据，不能借铁与钙的间隔；对KG-02核对原始生成与剔句后正文，避免只剩无主体短结论。
- [ ] KG-05若本品语料没有明确出血关系，保留拒答和该次过拒报告；不得把其他油类的关系迁移给磷虾油。复核夹具依据与图谱引用链后明确真实缺口。
- [x] 最近成功且可用的相互作用检查中，部分对象未收录时，最终正文必须明确该对象与其他检查对象的具体关系不能由本次图谱判断；已核验的文字事实保留，最近失败或无效结构不借旧结果生成“未收录”，相近名称必须待用户确认。验收源为`AgentPartialInteractionBoundaryTest`和原KG-05结构化工具结果，核心出口在`Agent.enforceUnknownInteractionRefusal`，附加边界在模型及医学审核后发布。5项先红2项，真实javac绿色5/5；中途泛型实现编译错误及验证夹具available分类错误的原日志保留，不改产品语义迁就失败夹具。
- [x] KG-05的原“本品出血风险”锚点与KB-0049原文矛盾，按需求失败修订rubric，保留caseId、问题、36条总分母和18条复杂图谱家族及所有原报告；新判据须同时证明本品关系未决、两个对象、明确无法据图谱判断和未收录不等于安全，不能仅调整期望拒答字段让旧答案变绿。原出血归因保留为医学迁移结论反向回归。4项先红3项后绿色；pipeline版本升为`agent-live-v5-medical-evidence`，prompt版本未伪称变更。
- [ ] 源码变更后部署最新产物，原报告不覆盖；重判冻结答案并完成新run，输出动态事实分母、完整性和拒答边界。

本地综合门禁：22:23:45 `mvn -o -pl agent-core,ai-service -am test` BUILD SUCCESS，common58、contract1、core543（6跳过）、AI308（1跳过），0失败/0错误，日志`logs/logs-local/t1-medical-core-ai-gate-v37.log`。生产源码已经v36 clean真实javac；v37仅对齐验证夹具的available/partial分类后完整重跑。该本地结果不替代新版真实回答或独立最终评审。

### Task 6：独立复审的数量范围与验收接线

- [ ] 中文数字剂量/间隔与数字形式共用数量解析，错误的一百粒/三小时不能引用1粒/2小时放行；两与二、十百、点小数和范围形成等价正向/错值反向。药名三七片及年龄刻度不误当剂量。数量规范化仅用于内部比较，原句键、原文与偏移不改。Java21 JShell已复现旧版空事实/空拒绝，Maven RED65项7失败保留。
- [ ] 儿童等限定来源不能支持未保留人群范围的剂量结论；同段继承必须来自先前已核验范围，不能从问题或来源猜回答人群。原复审样本在审核及评测均错误通过，需固定回归。
- [ ] KG-05的结构化边界证明与图谱召回分开：`Sample.interactionBoundaryEvidence`默认关闭，由live适配器只读最近一次成功、可用、结构完整且覆盖本场景`interactionInputs`的检查产生；存在本场景未收录对象才授予。仅召回graphChunk、早先成功而最近失败/不可用、其它对象均不授予。旧`graphEvidence`仍表示召回证据，不能借它冒充检查执行。`GroundingBoundaryEvidenceWiringTest`实际经过live组装，最终测试必须reactor `-am`使用当前core，不能以旧安装jar的反向通过为当前证明。
- [ ] G-13编号回写留下空“出处：/来源：”包装，修复共同`CitationVerifier.repairCitations`入口，只删空包装、保留真实文字及事实/角标。2项回归先红1项，原日志保留。
- [ ] 上述修复由独立评审复核后部署；新真实评测、跨重启和T1联合门禁完成前不宣称医学专项最终交付。

### Task 7：v5真实失败的有限修复与逐句完整性验收

**状态与授权边界（2026-10-12）：** 隔离实现限定在`frontend/.evidence/maintenance-t1/medical-worktree`，构建走统一互斥入口；根仓库合入、部署、服务启停与真实模型调用由主代理统一调度。v9五文件增量已合入，独立复核及本地门禁通过，真实36条全集与原18条多跳仍待§7.6结算。文档沿用既有计划，不新增重复spec，不自动提交或删除文件。

**真实基线：** `frontend/.evidence/grounding-v5-aa93e53f-1b42-4f38-8d4f-8204ff23b5f0.json`，pipeline为`agent-live-v5-medical-evidence`，36/36完成、执行失败0，当前判定器0/62、多跳10/18。独立复核发现过度剔除、医学事实漏审、同名标题引用错片与跨产品风险迁移，故该轮未通过最终验收。判定器的引用100%只说明当前判定规则通过，不能证明切片级语义正确。报告为平顶JSON，样本入口是`cases`，不是`data.live`。

**目标与范围：** 修复已证实的共同解析、来源与引用根因，使有直接证据的完整答案能保留，错误关系与跨产品结论能阻断。有限定向集为G06、G13、G17、KG02、KG05、KG08、KG09、KG10、KG12，以及同名标题引用问题G03/G10；最终保留36条全集及18条复杂图谱家族。冻结离线答案、evidence与mustMention不改写；不通过调低0.20证据门、增加药名特判、固定事实分母、删失败用例或整体拒答将指标改绿。不扩展医学知识词典，不增隐藏行为配置，不改数据库结构。

**涉及文件与职责：**
- `backend/agent-core/src/main/java/yumefusaka/envoymart/agent/rag/MedicalDoseAssertions.java`：数量角色、实体边界、版本话语与人群绑定。
- `backend/agent-core/src/main/java/yumefusaka/envoymart/agent/rag/MedicalQuantities.java`：继续只做内部数量规范化，不改原文或偏移。
- `backend/agent-core/src/main/java/yumefusaka/envoymart/agent/rag/MedicalEvidenceVerifier.java`：医学断言识别及同片支持，统计与生产审核共用断言对象。
- `backend/agent-core/src/main/java/yumefusaka/envoymart/agent/rag/SemanticGroundingVerifier.java`：总结/比较包含临床事实时不能按CONVERSATIONAL绕过共同审核。
- `backend/agent-core/src/main/java/yumefusaka/envoymart/agent/rag/CitationVerifier.java`：同标题保留多切片候选，按句子证据定位引用。
- `backend/agent-core/src/main/java/yumefusaka/envoymart/agent/core/Agent.java`：审核阶段诊断及最终回答完整性结算，沿用现有最终发布入口。
- `backend/agent-core/src/main/java/yumefusaka/envoymart/agent/rag/GroundingEvaluator.java`、`backend/ai-service/src/main/java/yumefusaka/envoymart/aiservice/eval/GroundingLiveEvalService.java`：共用判定与eval阶段证据关联，不能以工具成功布尔值直接提升证据充分性。
- `backend/agent-core/src/main/java/yumefusaka/envoymart/agent/rag/MedicalCompletenessContract.java`：有限评测必要要点独立账本，保留旧`mustMention/multiHopHit`；生产Agent不按caseId补答案。KG05根据最近可核验检查区分未知对象与双收录关系未决；G13缺至少2小时间隔时记`MISSING_RETRIEVAL_CONTEXT`。
- `backend/knowledge-service/src/main/java/yumefusaka/envoymart/knowledgeservice/graph/GraphService.java`：风险边与当前产品/成分来源作用域一致性；复用现有文档关联，不复制判据到多个调用方。
- 回归落在既有`MedicalEvidenceVerifierTest`、`GroundingMedicalAccountingTest`、`GroundingQualityTest`、`CitationVerifierTest`、`GroundingEvaluatorTitleCitationTest`、`GraphServiceRecallTest`、`GroundingLiveEvalServiceTest`及对应模块已有测试目录。

#### 7.1 补齐阶段证据，明确不可追溯范围

- [x] 固定上述失败报告、requestId与切片内容作为输入证据。旧日志只记录拒绝数量和粗原因，Redis检查点没有模型原答案；当前报告仅有最终answer，不能声称已取得原始草稿或证明某句曾由模型生成。
- [x] eval关联`caseId/requestId`，记录审核阶段、句hash、引用编号、chunk/source标识、拒因类别、剔前后事实数与完整性缺项；默认日志不落原句、模型完整输入或敏感信息。稳定句标识只用于诊断；保留原句键/偏移及出现序号，不合并重复句。v6真实阶段已关联；哈希日志不能还原完整草稿。
- [x] v6阶段证据已定位：G06第二版本、G13间隔、G14数字事实、KG10时间事实通过语义审核后在医学审核被删；KG07在关系核对被删。旧粗拒因把含数字句的多种失败混记为数量错误，v9分开记录主体、人口、角色、时间约束和关系对象/极性。重建正向种子明确区别于原raw，不能据最终缺项声称模型未生成。

#### 7.2 数量角色、实体边界与临床断言共用解析

- [x] 将“间隔/错开”及“在服用X两小时后再食用Y”统一为具有参与实体和先后约束的时间关系；不能落入一般DOSE角色。来源“2小时以上”可支持“至少2小时”，不能支持“恰好2小时即可”或反转先后；省略下界与另一参与方均有反向回归。
- [x] 版本话语“一版是/另一版是”作为话语前缀处理；实体提取在名词与谓语边界停止。已有药类词表派生共享后缀与药/药物等价；人口前缀及起始时间状语不作为药名，未知药名不因人口限定而丢失。三七片、年龄与孕前三/四个月边界沿用既有回归，不新增药名。
- [x] 有限数量/关系结构与公共事实识别供统计和严格审核共用；“吸收变差”“生物利用度可明显下降”及后置否定已固定。吸收/生物利用度与药效/疗效分别核验，前者不能推出后者。
- [x] G13真实最终句“也就是说，同时服用可能让左旋多巴的吸收变差、药效打折扣。”进入事实统计；无引用与无疗效证据拒绝，纯遵医嘱/咨询不计事实。
- [x] 已固定上述正反种子并保留真实RED输出；这些种子只证明共同根因，不冒充旧raw草稿。新增KG08真实并列药类原句及未知药负向，94项最终定向0失败，证据见下方。

#### 7.3 同名标题多切片引用定位

- [x] `numberTitles`保留同标题全部候选，正文唯一支持才定位，原数字槽位保留；无法定位时记录候选、偏移与原因，歧义标题不被当成已证明的出处。
- [ ] G03/G10新真实答案必须分别定位到退款条件/会员权益的真实原片，不能落到同标题第一片。确定性定位已通过，但新36的实际槽位仍待验证。
- [x] 同标题、多片顺序、包装、null槽、数字引用及无支持均有确定性回归；两次归一不覆盖已定位数字槽位。

#### 7.4 图谱风险作用域与存量边

- [x] KG05独立完整性按最近成功可用两对象检查结算；双found不能借旧未知结论，风险与未决均绑定同句当前产品/药物。旧rubric及旧18条多跳指标保留，最近失败不借早先成功。
- [x] `recall/interactions`共同来源核验只认当前SUBJECT归属或原文明示通用双端关系；本品/配方/过敏限定不因同成分传播，旧CONTAINS不能覆盖当前归属。
- [x] 存量边逐次批读当前文档/关联/切片，缺源、旧quote、停用或作用域未明均记跳过原因。G13只在同doc/chunk与原quote跨度精确吻合、完整句仍在当前原片时恢复连续2.4句，不拼邻接2.3钙条件；失败保留原片段且完整性记缺上下文。
- [x] KB0041跨DHA迁移反向、KB0049当前归属正向、通用关系正向、另归属/缺源/跨行/直问成分等反向均通过；实际新答案的Omega-3归因仍由新run逐句核对，不在此宣称已通过。

#### 7.5 剔句后回答完整性与最终验收

- [x] 已实现独立`answerComplete/missingRequired`及有限必要要点账本；G06两版本、G13至少2小时、人群/时间及KG05当前检查均有确定性正反。0未支撑但只剩提示不能算完整；新真实正文的覆盖仍待下方36/18。
- [ ] 对每个有限case列出证据中可回答的必要要点及最终覆盖情况。完全剔除或只剩咨询提醒可以安全失败，但不能同时算作回答完整、证据充分或该答通过；诊断输出缺项，不自动重试模型来掩盖失败。无支持资料的问题保持合理拒答，不制造事实补齐答案。
- [x] 真实来源与明确标注的重建种子已固定失败回归，RED输出保留；统一解析及来源修复完成有限回归、模块验证与独立复核。部署与真实调用由主代理调度。失败按需求/前提/实现/验证分类回流，不靠改期望值迁就实现。
- [x] 主代理已授权并执行根绝对wrapper的`-IsolatedMedical`互斥窗口；最终`-o -fae -pl agent-core,ai-service,knowledge-service -am clean test`真实Exit0，0失败/0错误，跳过项及全部失败日志保留。
- [ ] 部署最新产物后，由既有真实评测入口执行36条全集，保留新runId与原失败报告，逐条人工复核有限case；输出动态事实分母、未支撑数、有效引用、完整性、过拒及18条复杂家族覆盖。0/N不是单独通过条件；任一漏审、错误切片、跨产品迁移或有强证据却丢必答要点均未过门禁。
- [x] 项目冻结离线账本原答案/evidence/mustMention未改。v8历史口径为未支撑17/48、引用37/错12、旧多跳4/6；v9经独立复核批准的当前重判为未支撑18/49、引用37/错12、旧多跳4/6，仅G06新增未有结构化支持的图谱未收录声明。完整性独立记账，不改变旧多跳公式；新真实36/18及跨重启/T1联合门禁不由离线重放替代。

**本轮本地证据（均在ignored隔离目录）：** 初始`medical-task7-red.log`实际Exit1（core10项8失败）、`medical-task7-graph-red.log`实际Exit1（图谱2项2失败）；有限GREEN5实际Exit0（core103、knowledge14、AI7）。KG08新增原句`medical-task7-KG08-red.log`实际Exit1，随后三份GREEN失败日志分别固定未知药漏识、人口前缀残留及起始状语回归，不改负向/旧正向期望；`medical-task7-KG08-green4.log`09:05:43实际Exit0，94/94、0失败/0错误。最终`medical-task7-module-final.log`09:10:06实际Exit0/BUILD SUCCESS：common58、contract1、core584（6跳过）、knowledge68、AI313（1跳过），0失败/0错误。独立复审曾发现当前产品绑定、旧组成边授权、稳定句诊断及未知人口前缀问题，修复与回归已落盘；最终只读复审由主代理`closure_review`继续，未在复审完成前勾其联合收口项。源pipeline为`agent-live-v6-medical-completeness`，主prompt仍`grounding-prompt-v4`；部署/真实执行保持未勾。

**最终v8补充（历史阶段，旧证据不覆盖）：** `closure_review`复审指定同句反例“磷虾油与阿司匹林，本次无法判断钙与铁的关系。”仍会误授完整。`medical-task7-KG05-object-red.log`09:28:15实际Exit1（1项1失败），仅eval契约按关系宾语的前置/后置位置绑定当前两对象，不改生产Agent与旧指标；`medical-task7-KG05-object-green.log`09:29:36实际Exit0，Accounting12/12，指定原反例、原前置正向及谓词后当前对象正向通过。当前源最终`medical-task7-module-final-v8.log`09:34:22实际Exit0/BUILD SUCCESS：common58、contract1、core584（6跳过）、knowledge68、AI313（1跳过），0失败/0错误。候选17文件与根基线安全比对落`medical-task7-final-candidates-v8.json`；当时的v7候选及旧module-final不作为该v8源交付证据。P1最终复审关闭仍由主代理确认，真实36/18、合入部署及跨重启未执行。

**失败、并发与回滚：** 解析或作用域不可证明时采用既有不足证据结果，保留请求关联与原因；不靠模型或工具“成功”恢复充分性。诊断状态限定本请求，不能跨用户继承；并发查询复用只读不可变结果。代码修改按数量/断言、引用、图谱边界分组审阅，回滚仅针对本任务获授权的diff，不回滚用户与其他代理改动。当前没有数据库迁移、依赖安装、后台服务或文件删除；诊断产物沿用ignored路径，清理仍遵守用户确认规则。

#### 7.6 当前证据与真实验收约束

- **历史冻结输入的当前重判：** 事实句/未支撑/引用检查/错误引用为 **49/18/37/12**；原v8口径48/17/37/12保留。唯一变化是G06“平台知识图谱中未收录「铁叶酸片」这一商品 [interaction_check]”：原fixture只有`toolEvidence=true`，没有结构化工具回执，三个引用切片均不证明图谱未收录。结论是现有冻结输入不能核验该存在性声明，不能据此反推当时工具未执行。原答案、evidence、金标及指标公式未改；固定期望经独立复核核准，完整对照为`medical-task7-v9-historical-compare-final.json`。
- **本地门禁：** v9有限回归113/113，隔离模块common58/contract1/core598/knowledge68/AI313，0失败、0错误、7跳过；证据为`medical-task7-v9-finite-green-approved.log`、`medical-task7-v9-module-final.log`及五文件清单`medical-task7-final-candidates-v9.json`，均位于上述ignored隔离目录。根合入后的clean package为common58/contract1/core602/knowledge68/AI313，0失败、0错误、7跳过；两组core计数属于不同验证树，不混作同一次运行。
- **修复边界：** 版本话语、同句同主体人口范围及INTERVAL词法共用解析；抗凝对象与极性分开核验。具体药物处方分类、监测要求、未收录声明不能借纯遵医嘱提醒豁免。监测保留原合用条件与双方，图谱负向限定本次明确检查对象，本品归属只能承接回答中明确写出的来源商品，明确转题后清除。未知药、儿童迁移、逆作用、反先后、弱来源及跨商品反例必须继续拒绝，不从问题或引用编号补主体。
- **当前真实运行：** v7已部署，run `e351356a-7ce1-4c8d-8f6f-78ae151dffd2` 正在执行原36条全集；尚无最终结算，不能宣称医学达标。原18条旧`multiHopHit`必须严格大于85%，至少16/18；同时逐句检查动态0/N、强来源必要要点完整、切片归属与合理拒答。任何一项不满足均未通过，不能修改分母、金标、旧公式或增加拒答刷绿；历史原报告和失败日志保留。
