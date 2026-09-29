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
  /** 匿名评价时服务端就不返回它 */
  userId: string | null
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
  docRef: string | null
  spuName: string | null
  skuSpecText: string | null
  skuImage: string | null
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

/** 切片详情 —— 引用回跳的唯一入口 */
export interface ChunkDetail extends ChunkRef {
  content: string
  docNo: string
  title: string
  source: string
  scope: string
  version: string
  status: number
  /** 所属文档全文，就地高亮用，省掉第二次请求 */
  documentContent: string
}

export interface ToolCall {
  tool: string
  input: string
  output: string
}

export interface ChatResponse {
  sessionId: string
  reply: string
  knowledge: KnowledgeSnippet[]
  toolCalls: ToolCall[]
  /** 与商品列表页同一个类型 —— 之前这里写的是另一套字段，卡片全是空的 */
  recommendedProducts: ProductSummary[]
  /** 等待用户确认的高危工具 */
  pendingActions: string[] | null
}

export interface ChatMessage {
  id: string
  role: 'user' | 'assistant'
  content: string
  knowledge?: KnowledgeSnippet[]
  toolCalls?: ToolCall[]
  recommendedProducts?: ProductSummary[]
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
  name: string
  level: number
  sort: number
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
