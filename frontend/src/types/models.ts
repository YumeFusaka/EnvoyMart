export interface ApiResponse<T> {
  code: number
  msg: string
  data: T
}

export interface UserProfile {
  id: string
  username: string
  nickname: string
  roleName: string
  avatar: string | null
  phone: string | null
  email: string | null
}

export interface LoginResponse {
  token: string
  user: UserProfile
}

/** 收货地址。省市区拆成三列而非一个字符串，按区域统计与运费规则都要用到 */
export interface UserAddress {
  id: number
  userId: string
  receiverName: string
  receiverPhone: string
  province: string
  city: string
  district: string
  detail: string
  /** 0 否 / 1 是。同一用户至多一条为 1，由后端在同一事务内先清后置保证 */
  isDefault: number
  tag: string | null
  createdAt: string
  updatedAt: string
}

export interface CartItem {
  id: number
  spuId: number
  skuId: number
  name: string
  /** 形如 "规格:400IU×90粒;包装:瓶装" */
  specText: string | null
  image: string | null
  /** 单位「分」 */
  price: number
  quantity: number
  stock: number
  subtotal: number
  /** 是否勾选参与结算 */
  selected: boolean
  /** 是否仍在售且有货。失效项留在车里而不是直接删掉，用户能看到发生了什么 */
  available: boolean
}

// ==================== 订单域 ====================

export interface OrderItem {
  id: number
  spuId: number
  skuId: number
  spuName: string
  /** 形如 "规格:400IU×90粒;包装:瓶装" */
  skuSpecText: string | null
  skuImage: string | null
  /** 单位「分」 */
  unitPrice: number
  quantity: number
  subtotal: number
}

/** 订单状态。与后端 OrderStatus 枚举一一对应 */
export type OrderStatus =
  | 'CREATED'
  | 'PAID'
  | 'SHIPPED'
  | 'RECEIVED'
  | 'COMPLETED'
  | 'CANCELLED'
  | 'CLOSED'
  | 'REFUNDING'
  | 'REFUNDED'

export interface Order {
  id: number
  orderNo: string
  status: OrderStatus
  /** 状态的中文说明。由服务端给出，前端不自己维护一份状态字典 —— 那样迟早会与后端不一致 */
  statusText: string

  /** 金额单位一律「分」 */
  totalAmount: number
  freightAmount: number
  discountAmount: number
  payAmount: number

  receiverName: string
  receiverPhone: string
  receiverProvince: string
  receiverCity: string
  receiverDistrict: string
  receiverDetail: string

  /** 支付截止时间。前端据此显示倒计时 */
  expireAt: string | null
  createdAt: string
  paidAt: string | null
  shippedAt: string | null
  receivedAt: string | null
  closedAt: string | null

  remark: string | null
  cancelReason: string | null

  items: OrderItem[]
}

export interface Refund {
  id: number
  refundNo: string
  orderId: number
  amount: number
  status: string
  reason: string
  createdAt: string
  refundedAt: string | null
}

// ==================== 评价与售后 ====================

export interface Review {
  id: number
  spuId: number
  skuId: number
  orderId: number
  /**
   * 展示用的脱敏昵称，例如 `a***e`。
   *
   * 服务端返回的是**脱敏后的**名字而不是 userId —— 评价区是公开面，
   * 而本项目里 userId 就是登录名。匿名评价时这里直接是 null，
   * 服务端就不下发，不指望前端不显示。
   */
  nickname: string | null
  rating: number
  content: string | null
  images: string[]
  anonymous: boolean
  status: string
  replyContent: string | null
  replyAt: string | null
  usefulCount: number
  createdAt: string
}

/**
 * 「我的评价」列表项。
 *
 * 与 {@link Review} 的差别：多一个 `product`（自己的评价列表里要认得出是哪个商品），
 * 少一个 `nickname`（看自己的评价时「别人怎么称呼我」没有意义）。
 */
export interface MyReview {
  id: number
  spuId: number
  skuId: number
  orderId: number
  /** 被评价的那一条订单行。订单详情据此标「已评价」——粒度是订单行而不是订单 */
  orderItemId: number
  rating: number
  content: string | null
  images: string[]
  anonymous: boolean
  /** PUBLISHED / HIDDEN / PENDING，原样透出 */
  status: string
  /** 被隐藏的原因，仅隐藏时有值。只有作者看得到 */
  hiddenReason: string | null
  replyContent: string | null
  replyAt: string | null
  usefulCount: number
  createdAt: string
  /** 商品卡片数据。product-service 不可用时为 null，界面退化成只显示评价本身 */
  product: ProductSummary | null
}

export interface ReviewStatistics {
  spuId: number
  total: number
  average: number
  /** 各星级数量，下标 0 对应 1 星 */
  distribution: number[]
  withImage: number
}

/** 售后资格预览。填表之前就能知道能不能退、最多退多少 */
export interface AfterSalePreview {
  orderItemId: number
  type: string
  eligible: boolean
  /** 不能受理时的原因，直接展示给用户 */
  reason: string | null
  maxRefundAmount: number | null
  itemSubtotal: number | null
  /** 依据的政策文档编号，前端据此显示「查看政策原文」 */
  docRef: string | null
  requirements: string | null
}

export interface AfterSale {
  id: number
  afterSaleNo: string
  orderId: number
  orderNo: string
  orderItemId: number
  type: string
  typeText: string
  status: string
  statusText: string
  reason: string
  description: string | null
  images: string[]
  /** 单位「分」 */
  refundAmount: number
  maxRefundable: number | null
  appliedAt: string
  auditedAt: string | null
  finishedAt: string | null
  auditRemark: string | null
  /** 退货物流：用户寄回时填写 */
  returnCarrier: string | null
  returnTrackingNo: string | null
  returnedAt: string | null
  docRef: string | null
  spuName: string | null
  skuSpecText: string | null
  skuImage: string | null
}

/** 一条售后流转流水。「我的退货到哪一步了」的完整答案 */
export interface AfterSaleLog {
  fromStatus: string | null
  toStatus: string
  /** USER / SYSTEM / ADMIN */
  operatorType: string
  /** 用户侧接口会抹掉操作人 id，这里恒为 null；管理端才有值 */
  operatorId: string | null
  remark: string | null
  createdAt: string
}

/** 售后详情：单子本体 + 完整流水 */
export interface AfterSaleDetail {
  afterSale: AfterSale
  logs: AfterSaleLog[]
}

// ==================== 营销 ====================

export interface Coupon {
  id: number
  name: string
  /** FIXED 满减 / DISCOUNT 折扣 */
  type: string
  /** 「满 200 元减 30 元」这类人话，服务端拼好的 */
  ruleText: string
  amount: number | null
  discount: number | null
  /** 使用门槛（分），0 表示无门槛 */
  threshold: number
  scopeType: string
  totalCount: number
  receivedCount: number
  remainingCount: number
  validFrom: string | null
  validTo: string | null
  /** 当前用户是否已领过 */
  received: boolean
}

export interface UserCoupon {
  id: number
  couponId: number
  name: string
  type: string | null
  ruleText: string | null
  amount: number | null
  threshold: number | null
  /** UNUSED / USED / EXPIRED */
  status: string
  statusText: string
  orderNo: string | null
  receivedAt: string
  usedAt: string | null
  expireAt: string
  /** 传入订单金额时才有：这张券现在能不能用 */
  usable: boolean | null
  /** 不能用时的原因，如「差 5000 分可用」 */
  unusableReason: string | null
}

export interface Payment {
  id: number
  paymentNo: string
  orderId: number
  orderNo: string
  /** 单位「分」 */
  amount: number
  channel: string
  payType: string | null
  /** PENDING / SUCCESS / FAILED / CLOSED */
  status: string
  transactionNo: string | null
  paidAt: string | null
  expireAt: string | null
  createdAt: string
}

export interface LogisticsStep {
  status: string
  detail: string
  /** 节点所在地，可空 —— 客服补录时手上常常只有承运商给的一句话 */
  location?: string | null
  time: string
}

export interface Logistics {
  orderId: number
  orderNo: string
  carrier: string
  trackingNo: string
  steps: LogisticsStep[]
}

// ==================== 知识层 ====================

/**
 * 引用片段 —— 回答里 `[n]` 背后那条依据。
 *
 * 字段是照着「用户能不能自己核对」定的：写了什么（content）、出自哪一篇的哪一节
 * （position）、哪一版（version）缺一样都核不了。`chunkId` 与 `charOffset` 是
 * 「点引用跳原文」的锚点，服务端返回的数组顺序**就是**引用序号——`[n]` 对应
 * `knowledge[n - 1]`，两边不再各排一次。
 */
export interface KnowledgeSnippet {
  /** 切片标识，跳原文的锚点 */
  chunkId: string
  /** 所属文档编号，形如 KB-0005 */
  docId: string
  title: string
  /** 领域范围：nutrition / after_sale / logistics / promotion / ... */
  scope: string
  /** 文档类型：manual 说明书 / policy 平台规则 / regulation 监管规范 / spec / guide */
  source: string
  version: string
  /** 形如《维生素 D3 说明书》 > 第二章 用法用量 > 2.2 */
  position: string
  /** 命中位置在原文中的字符偏移（UTF-16 码元），供前端高亮 */
  charOffset: number | null
  /** 相关性分 [0,1]；null 表示该链路未提供相关性信号，不是「不相关」 */
  score: number | null
  /** 分数是否来自重排。两种分数的量纲不同，措辞也该不同 */
  reranked: boolean | null
  content: string
}

/**
 * 图谱上的一个实体。
 *
 * `name` 是**节点键**（跨服务稳定标识，商品形如 `spu7`，其余是规范化后的中文名），
 * `label` 是给人看的显示名。两者都要：用键去查下一跳，用标签去渲染 ——
 * 拿显示名去查会在「商品改名」之后查不到，拿键去渲染会让用户看见 `spu7`。
 */
export interface GraphNode {
  name: string
  label: string
  /** 实体类型：PRODUCT / INGREDIENT / NUTRIENT / DRUG / DRUG_CLASS / POPULATION */
  kind: string
}

/**
 * 图谱上的一条边，自带完整出处。
 *
 * 一条边**不是**一句渲染好的结论，而是「哪两个实体、什么关系、依据是文档里的哪一句」
 * 这三件事的组合。界面据此既能画出一条连线，也能在点它时把那句原文摆出来 ——
 * 这正是图谱结论与「模型自己编的」之间唯一的区别。
 */
export interface GraphEdge {
  head: GraphNode
  /** 关系枚举名：CONTAINS / PROVIDES / INTERACTS_WITH / CAUTION_FOR */
  relation: string
  /** 关系的后果，如「可能增加出血风险」。可能为空 */
  effect: string | null
  tail: GraphNode
  docId: string
  docTitle: string | null
  /** 引文落在哪一片；点击跳原文的锚点 */
  chunkId: string | null
  quoteStart: number
  quoteEnd: number
  /** 支持这条关系的**逐字引文** —— 溯源链的终点 */
  quote: string
  /**
   * 「对方」那一端。
   *
   * 查「华法林」时，边的两端可能都是它自己（`华法林 -禁忌-> 孕妇`），
   * 这时直接渲染 head/tail 会输出「华法林与华法林」；`counterpart` 说明的是
   * 这一端之外的另一端。只有相互作用查询会填，邻域查询为 null。
   */
  counterpart: GraphNode | null
  /** 关联路径，如 `["鱼油软胶囊", "深海鱼油", "华法林"]`。空数组表示直连 */
  chain: string[]
}

/** 一种被查的物质（商品展开后的成分/营养素） */
export interface Substance {
  rootName: string
  rootLabel: string
  name: string
  label: string
  kind: string
  /** 从 root 走到它的路径，如 `["鱼油软胶囊", "深海鱼油"]` */
  chain: string[]
}

/** 「我手上这几样能不能一起吃」的报告 */
export interface InteractionReport {
  /**
   * 这一次查询**是否真的做成了**。
   *
   * false 表示图谱没查成 —— 界面必须说「未检查」，绝不能说「无冲突」。
   * 两者在数据结构上都是「一堆空列表」，对用户却是相反的两句话。
   */
  available: boolean
  /** 不可用时的原因，供界面如实转述 */
  note: string | null
  items: InteractionItem[]
}

export interface InteractionItem {
  /** 用户自己的写法，原样回显 */
  input: string
  label: string
  /** 图谱里有没有收录它。false 时 substances/risks 必然为空 */
  found: boolean
  substances: Substance[]
  /** 已知的相互作用与人群禁忌，每条都带原文引文 */
  risks: GraphEdge[]
}

/** 文档列表项，不含正文 */
export interface DocumentSummary {
  docNo: string
  title: string
  source: string
  scope: string
  version: string
  /** 逗号分隔的标签串（服务端原样存储，不在这里拆） */
  tags: string | null
  /** 0 停用 / 1 启用 */
  status: number
  chunkCount: number
  contentLength: number
  updatedAt: string
}

/** 切片索引项，只够在原文里定位 */
export interface ChunkRef {
  chunkId: string
  chunkIndex: number
  position: string
  charOffset: number | null
  /** 该片在原文中的结束位置（不含）。最后一片取正文长度 */
  charEnd: number | null
}

/** 文档详情，带全文 —— 「点引用跳原文」的目的地 */
export interface DocumentDetail {
  docNo: string
  title: string
  source: string
  scope: string
  version: string
  tags: string | null
  status: number
  /** 正文全文，charOffset 就是它上面的下标 */
  content: string
  updatedAt: string
  chunks: ChunkRef[]
}

export interface ToolCall {
  tool: string
  input: string
  output: string
  success: boolean
  /** 跑通了但什么都没查到 —— 与失败是两种结局，见后端 `ToolResult.noData` */
  noData: boolean
  /** 墙钟耗时（毫秒） */
  latencyMs: number
  /**
   * 这次调用确立的业务事实，形如 `{ 订单状态: '已支付', 应付金额: '¥128.00' }`。
   *
   * 下发给界面是为了让「核对过了」看得见：回答里的金额与这一栏一致，用户不用相信，
   * 扫一眼就能对上。为空表示这次工具没有可机器比对的事实（知识检索、物流轨迹本来就没有）。
   */
  facts?: Record<string, string> | null
}

export interface ChatResponse {
  sessionId: string
  reply: string
  knowledge: KnowledgeSnippet[]
  toolCalls: ToolCall[]
  /** 与商品列表页同一个类型 —— 之前这里写的是另一套字段，卡片全是空的 */
  recommendedProducts: ProductSummary[]
  /**
   * 等待用户确认的高危操作，形如 `order_cancel(orderId=12)`。
   * <p>
   * **非空表示本轮对话被中断**：`reply` 是确认提示而不是回答，也没有工具真正执行过。
   * 前端据此渲染确认卡片。
   */
  pendingActions: string[] | null
  /**
   * 确认令牌，与 `pendingActions` 同时非空、同时为空。
   *
   * 用户确认时把它原样带回，服务端据此执行签名里的那批调用。**这一项是给人看的、
   * 那一项是给机器执行的**：卡片文案改得再漂亮也不影响真正执行什么，反过来也一样。
   */
  approvalToken: string | null
  /**
   * 本轮证据门的判定，决定 `knowledge` 该被说成什么。
   *
   * - `SUFFICIENT`：相关度达标，可以称「依据」
   * - `WEAK`：检索到了但相关度不足，只能说「参考」，不能当结论依据
   * - `NONE`：什么都没召回，`knowledge` 为空
   *
   * 判定规则（重排分与余弦相似度两把尺子、图谱依据豁免）在后端，前端不重算。
   */
  evidenceLevel: EvidenceLevel | null
  /**
   * 本轮检索**实际使用的查询句**，仅在发生指代消解改写、且与用户原话不同时下发。
   *
   * 追问句「那它呢」原样去检索什么都召不回。有这一句，用户才能看懂这批资料是拿什么
   * 检回来的——「依据 0 条」到底是库里没有，还是那句追问没被读懂，两者在这里分得开。
   */
  retrievalQuery?: string | null
  /**
   * 讲了一条事实却没交代出处、已被后端从 `reply` 里剔除的句子。
   *
   * 剔除了却仍然下发：用户该看到回答里少了什么、为什么少。**有内容不等于都被剔了**——
   * 整篇没有一处有效引用时后端只报告不剔除（那更像「这一轮不在答知识问题」），
   * 此时这些句子仍在 `reply` 里，靠 `unsupportedStripped` 区分。
   */
  unsupportedClaims: string[] | null
  /** 上面那些句子是否真的已被移出 `reply` */
  unsupportedStripped: boolean
  /**
   * 整篇回答没有任何依据——没有知识库引用，也没有工具执行记录，
   * 内容由模型凭自身知识生成。
   *
   * 与 `unsupportedClaims` 是两级粒度：后者点名「哪几句」，是一条精准的修订；
   * 它说的是「这一整段」，是一句免责声明。两者互斥——有引用时按句报，
   * 一个引用也没有、也没有工具执行时才整篇报。
   *
   * 判定涉及「本轮有没有执行过工具」，前端只看得到回答正文，所以由后端算好下发。
   */
  ungrounded: boolean
  /**
   * 与工具当场返回的事实对不上、已被后端从 `reply` 里剔除的说明。
   *
   * 与 `unsupportedClaims` 是两种病：那些是「没有出处」，这些是「有出处但说错了」——
   * 工具明明返回「应付金额 ¥128.00」，回答里写成别的数。订单类问题走工具而不走知识库，
   * 这两句在引用上完全站得住，只有拿工具的返回值去对才看得出来。
   *
   * **它没有「仍留在上面」的分支**：事实只有一种，对不上就一定是错的，所以一定会被剔除。
   */
  factMismatches: string[] | null
  /** 上面那些说明是否真的已被移出 `reply`（当前恒为 `true`，保留字段以与 `unsupportedStripped` 对称） */
  factStripped: boolean
  /**
   * 本轮证据之间被发现的矛盾。后端不让模型挑一个讲，而是要求它列出来，
   * 这里拿到的是结构化结果，`refs` 可直接跳回原文核对。
   */
  conflicts: KnowledgeConflict[] | null
  /** 本轮的模型用量与估算花费；`null` 表示没有任何模型调用发生 */
  usage: ChatUsage | null
}

export type EvidenceLevel = 'SUFFICIENT' | 'WEAK' | 'NONE'

/**
 * 本轮证据之间被发现的矛盾。
 *
 * 同一件事在两份文档里有不同说法时，后端不让模型挑一个讲，而是要求它把矛盾列出来
 * ——不这么做的结果是一句斩钉截铁的话，而系统手里其实握着一个尚未解决的冲突。
 *
 * `refs` 是回答里 `[n]` 的 n，可直接跳回那条原文核对。**可能为空**：
 * 模型没写清是哪几条在矛盾时就不猜——猜错的跳转会把用户带到一条无关的原文面前，
 * 而他以为自己核对过了。
 */
export interface KnowledgeConflict {
  refs: number[]
  detail: string
}

export interface ChatMessage {
  id: string
  role: 'user' | 'assistant'
  content: string
  /** 消息时间（ISO）。实时消息由前端打点、历史消息由服务端带回，只用于展示 */
  at?: string
  knowledge?: KnowledgeSnippet[]
  toolCalls?: ToolCall[]
  recommendedProducts?: ProductSummary[]
  /** 待确认的高危操作。确认或取消后清空，卡片随之消失 */
  pendingActions?: string[]
  /** 确认这张卡片要带回服务端的令牌，与 `pendingActions` 同生共死 */
  approvalToken?: string
  /** 证据门判定，随 `knowledge` 一起透传给引用区，决定标题措辞 */
  evidenceLevel?: EvidenceLevel
  /** 本轮检索实际使用的查询句（发生过指代消解改写时才有），透传给引用区标明来路 */
  retrievalQuery?: string
  /**
   * 讲了一条事实却没交代出处、已被后端从正文里剔除的句子。
   *
   * 剔除了却仍然下发：用户该看到回答里少了什么、为什么少。**有内容不等于都被剔了**
   * ——整篇没有一处有效引用时后端只报告不剔除，此时这些句子仍在 `content` 里。
   */
  unsupportedClaims?: string[]
  /**
   * 上面那些句子是否已被移出 `content`。
   *
   * 必须与列表一起看：**「已经替你拿掉了」和「还留在上面，你自己判断」是两件事**，
   * 用同一句话去描述会把后者说成前者。
   */
  unsupportedStripped?: boolean
  /**
   * 整篇回答没有任何依据——没有知识库引用，也没有工具执行记录。
   *
   * 与 `unsupportedClaims` 互斥：那是「哪几句有问题」，这是「这一整段都没有平台依据」。
   * 界面上是两句话，不能合并。
   */
  ungrounded?: boolean
  /**
   * 与工具当场返回的事实对不上、已被后端从正文里剔除的说明。
   *
   * 与 `unsupportedClaims` 分开存：那边是「没有出处」，这边是「与订单实际数据冲突」。
   * 后者用户是照着去付款、去对账的，说得轻了等于没提醒。
   */
  factMismatches?: string[]
  /** 上面那些说明是否已被移出 `content`；当前后端恒为剔除 */
  factStripped?: boolean
  /** 本轮证据之间被发现的矛盾，由模型判定、后端结构化 */
  conflicts?: KnowledgeConflict[]
  /**
   * 本轮的模型用量与估算花费。
   *
   * 为 `null` 表示这一轮一次模型调用都没发生（或后端没开统计），此时整块不渲染——
   * 显示「0 tokens」比不显示更糟，它看起来像统计坏了。
   */
  usage?: ChatUsage | null
}

/**
 * 一轮对话的模型用量。
 *
 * **这是整轮的总和**，不是最后一次调用：计划、ReAct 的每一圈、收口合成、
 * 记忆沉淀、检索的向量化与重排都算在内。Agent 的每一次「多想一步」都是一次真实计费的
 * 调用，只报其中一段会得到一个看着精确、实际偏小几倍的数。
 */
export interface ChatUsage {
  promptTokens: number
  completionTokens: number
  totalTokens: number
  /**
   * 估算金额（元）。为 `null` 表示本轮用到的模型一个都没配单价——
   * 那不是「免费」，是「不知道」，界面上不能显示成 ¥0.0000。
   */
  costCny: number | null
  /** 用到但没配单价的模型名。非空时金额是不完整的，必须说明 */
  unpricedModels: string[]
  models: UsageByModel[]
}

export interface UsageByModel {
  model: string
  promptTokens: number
  completionTokens: number
}

// ==================== 商品域 ====================
// 与后端 product-service 的新模型对应。金额一律是「分」，展示时除以 100

export interface PageResult<T> {
  records: T[]
  /** 符合条件的总条数，不是当前页条数 */
  total: number
  page: number
  size: number
  hasNext: boolean
}

export interface CategoryNode {
  id: number
  /**
   * 挂在哪个类目下，0 表示一级类目。
   *
   * 不要用「树上的父」代替它：父类目被停用时，子节点会被挂到根上展示，
   * 这时候它在树里看着是一级类目，真实的挂载点却还在那个停用的父类目下。
   * 编辑类目时提交这一项，才不会因为改了个名字而把整棵子树挪到根。
   */
  parentId: number
  name: string
  level: number
  sort: number
  /**
   * 1 启用 / 0 停用。
   * <p>
   * 公开树里恒为 1（停用的类目根本不在结果里），管理树才有区分 ——
   * 管理台必须看得到停用的类目，否则停用之后就再没有入口把它改回来。
   */
  status: number
  children: CategoryNode[]
}

export interface BrandView {
  id: number
  name: string
  logo: string | null
}

/**
 * 列表项，粒度是 SPU。
 * <p>
 * 价格是**区间**而不是单值：一个 SPU 下有多个 SKU，各卖各的价。
 * 只回一个价格就得在服务端随便挑一个，那是在替用户做决定。
 */
export interface ProductSummary {
  id: number
  name: string
  subtitle: string | null
  categoryId: number
  categoryName: string | null
  brandId: number | null
  brandName: string | null
  mainImage: string | null
  minPrice: number
  maxPrice: number
  sales: number
  ratingAvg: number | null
  reviewCount: number | null
  totalStock: number
  tags: string[]
}

/**
 * 搜索联想的一个候选。
 *
 * 三种类型共用一个列表，是因为它们在用户眼里是一回事——「我要找的东西」。
 * 分成三个下拉分组反而要多看一层结构。
 */
export interface SuggestItem {
  text: string
  type: 'PRODUCT' | 'BRAND' | 'CATEGORY'
  /** 商品 / 品牌 / 类目的 id。点到它就能直接跳过去，不必再搜一次 */
  id: number
}

/**
 * 收藏夹里的一条。
 *
 * 比商品卡多两个字段，都不是装饰：
 * - `favoritedAt` —— 收藏夹按时间倒序，没有它就解释不了这个顺序；
 * - `available` —— **下架不等于没收藏过**。下架商品在列表与详情里都查不到，
 *   如果收藏夹也把它藏起来，用户会以为「我的收藏丢了」。所以下架商品照样显示，
 *   只是标出来、点不进去。
 */
export interface FavoriteItem {
  spuId: number
  favoritedAt: string
  /** 是否仍在售 */
  available: boolean
  /** 商品卡片数据。商品被物理删除时为 null，此时只剩这条收藏记录 */
  product: ProductSummary | null
}

/** 规格项及其全部可选值（"容量"：[90粒, 180粒]） */
export interface SpecGroup {
  specId: number
  name: string
  values: { id: number; value: string }[]
}

export interface SkuView {
  id: number
  skuCode: string
  price: number
  originalPrice: number | null
  stock: number
  image: string | null
  /** 该 SKU 在每个规格项上取的值 id。用它与用户选中的组合比对，定位到唯一 SKU */
  specValueIds: number[]
  /** 形如 "容量:90粒;包装:瓶装" */
  specText: string
}

export interface AttributeView {
  attributeId: number
  name: string
  value: string
  unit: string | null
}

export interface ProductDetail {
  id: number
  spuCode: string
  name: string
  subtitle: string | null
  categoryId: number
  categoryName: string | null
  brandId: number | null
  brandName: string | null
  mainImage: string | null
  images: string[]
  detailHtml: string | null
  status: number
  tags: string[]
  sales: number
  ratingAvg: number | null
  reviewCount: number | null
  specs: SpecGroup[]
  skus: SkuView[]
  attributes: AttributeView[]
}
