/**
 * 管理端模型。
 * <p>
 * 与商城主站模型**分开一份**，不复用 `models.ts`：两侧的可见性契约本就不同。
 * 商城侧的评价要脱敏匿名用户，管理端要保留 userId 以便追溯；
 * 商城侧的商品详情带完整 SKU 规格树，管理端的列表项是带库存合计的运营视图。
 * 共用一个类型会逼着其中一侧接收用不到的字段，或者更糟 —— 以为某个字段一定有。
 *
 * 金额单位一律「分」，与后端 Long 字段对应。展示走 `formatPrice`。
 */

import type {
  AfterSale,
  AttributeView,
  Logistics,
  Order as OrderResponse,
  SpecGroup,
} from '@/types/models'

/** 状态流水。订单与售后共用，后端也是同一个 `StatusLogView` */
export interface StatusLogView {
  fromStatus: string | null
  toStatus: string
  operatorType: string
  operatorId: string | null
  remark: string | null
  createdAt: string
}

// ==================== 商品域 ====================

export interface AdminSpuQuery {
  keyword?: string
  categoryId?: number
  brandId?: number
  /** 0 下架 / 1 上架 */
  status?: number
  sort?: string
  page?: number
  size?: number
}

export interface AdminSpuSummary {
  id: number
  spuCode: string
  name: string
  subtitle: string | null
  categoryId: number
  categoryName: string | null
  brandId: number | null
  brandName: string | null
  mainImage: string | null
  minPrice: number | null
  maxPrice: number | null
  skuCount: number
  totalStock: number
  sales: number
  status: number
  updatedAt: string
}

export interface AdminSku {
  id: number | null
  skuCode: string | null
  price: number
  originalPrice: number | null
  stock: number
  image: string | null
  status: number
  specValues: Record<string, string>
}

export interface AdminSpuDetail {
  id: number
  spuCode: string
  name: string
  subtitle: string | null
  categoryId: number
  brandId: number | null
  mainImage: string | null
  images: string[]
  detailHtml: string | null
  tags: string[]
  status: number
  sales: number
  ratingAvg: number | null
  reviewCount: number | null
  createdAt: string
  updatedAt: string
  /**
   * 读侧是 `SpecGroup`（带 specId / 值 id），写侧是 `SpecRequest`（只有名字与字符串值）——
   * 两边形状确实不同，不是偷懒没统一：编辑已有商品时要能按 id 判断该删哪个规格值，
   * 而新建时这些 id 根本不存在。
   */
  specs: SpecGroup[]
  skus: AdminSku[]
  attributes: AttributeView[]
}

/** 写侧的规格定义。值只有字符串：新值没有 id，交给服务端建 */
export interface SpecRequest {
  name: string
  values: string[]
}

export interface AttributeRequest {
  attributeId: number
  value: string
}

export interface SkuRequest {
  id?: number | null
  skuCode?: string | null
  price: number
  originalPrice?: number | null
  stock: number
  image?: string | null
  status?: number
  specValues: Record<string, string>
}

export interface SpuUpsertRequest {
  spuCode?: string
  name: string
  subtitle?: string
  categoryId: number
  brandId?: number | null
  mainImage?: string
  images: string[]
  detailHtml?: string
  tags: string[]
  status: number
  skus: SkuRequest[]
  specs: SpecRequest[]
  attributes: AttributeRequest[]
}

export interface AdminBrand {
  id: number
  name: string
  logo: string | null
  description: string | null
  status: number
}

export interface BrandUpsertRequest {
  name: string
  logo?: string
  description?: string
  status: number
}

export interface CategoryUpsertRequest {
  parentId: number
  name: string
  sort: number
  status: number
}

/**
 * 类目的参数模板项。
 * <p>
 * 模板挂在**类目**上而不是商品上：同一类商品的参数名是固定的（「净含量」「保质期」），
 * 商品只填值。所以 `id` 是「参数定义」的 id，商品编辑页提交的
 * `AttributeRequest.attributeId` 指的就是它。
 */
export interface AdminAttribute {
  id: number
  categoryId: number
  name: string
  /** 输入控件类型，如 text / number / select */
  inputType: string
  /** 单位，如「克」「天」。可空 */
  unit: string | null
  sort: number
}

/** 不含 `categoryId`：新建时来自路径，编辑时不可改 —— 改挂类目会让已填的值无处安放 */
export interface AttributeUpsertRequest {
  name: string
  inputType: string
  unit?: string
  sort: number
}

// ==================== 交易与履约 ====================

export interface AdminOrderQuery {
  keyword?: string
  userId?: string
  status?: string
  createdFrom?: string
  createdTo?: string
  page?: number
  size?: number
}

export interface AdminOrderSummary {
  id: number
  orderNo: string
  userId: string
  status: string
  statusText: string
  totalAmount: number
  discountAmount: number
  payAmount: number
  receiverName: string
  receiverPhone: string
  itemCount: number
  totalQuantity: number
  firstItemName: string | null
  trackingNo: string | null
  carrierName: string | null
  createdAt: string
  paidAt: string | null
  shippedAt: string | null
  adminRemark: string | null
}

export interface AdminOrderDetail {
  order: OrderResponse
  adminRemark: string | null
  statusLogs: StatusLogView[]
  delivery: Logistics | null
}

export interface AdminShipRequest {
  carrierCode: string
  carrierName: string
  trackingNo: string
}

/**
 * 补录一条物流节点。
 *
 * `description` 留空由服务端按状态给默认文案 —— 让客服只需选一个状态；
 * 一句话都编不出来的节点，多半也不该录进去。
 */
export interface AdminTraceRequest {
  status: string
  description?: string
  location?: string
  /** ISO-8601。不填就是"现在"，补录历史节点时才给 */
  happenAt?: string | null
}

export interface AdminAfterSaleQuery {
  keyword?: string
  userId?: string
  status?: string
  type?: string
  appliedFrom?: string
  appliedTo?: string
  page?: number
  size?: number
}

export interface AdminAfterSaleDetail {
  afterSale: AfterSale
  logs: StatusLogView[]
}

// ==================== 评价域 ====================

export interface AdminReviewQuery {
  spuId?: number
  skuId?: number
  orderId?: number
  userId?: string
  rating?: number
  status?: string
  keyword?: string
  hasReply?: boolean
  createdFrom?: string
  createdTo?: string
  page?: number
  size?: number
}

/**
 * 管理端评价视图。与商城侧的 `Review` 关键差别：
 * 匿名评价**同样带 userId** —— 匿名是对外脱敏，不是对平台隐身，
 * 否则「这条差评是谁写的」在管理台上就断了线。
 */
export interface AdminReviewSummary {
  id: number
  spuId: number
  skuId: number | null
  orderId: number
  orderItemId: number
  userId: string
  anonymous: boolean
  rating: number
  content: string | null
  imageCount: number
  status: string
  replyContent: string | null
  replyAt: string | null
  replyBy: string | null
  hiddenReason: string | null
  hiddenBy: string | null
  hiddenAt: string | null
  usefulCount: number
  createdAt: string
}

export interface AdminReviewDetail {
  review: AdminReviewSummary
  images: string[]
}

// ==================== 用户域 ====================

export interface AdminUserQuery {
  keyword?: string
  /** 可逗号分隔多角色，后端按 OR 处理 */
  role?: string
  /** 0 禁用 / 1 正常 */
  status?: number
  page?: number
  size?: number
}

export interface AdminUserSummary {
  id: string
  username: string
  nickname: string | null
  avatar: string | null
  phone: string | null
  email: string | null
  roleName: string
  status: number
  createdAt: string
  disabledReason: string | null
  disabledBy: string | null
  disabledAt: string | null
}

export interface AdminUserDetail {
  user: AdminUserSummary
  addressCount: number
}

// ==================== 客服工单 ====================

export type TicketStatus = 'OPEN' | 'PROCESSING' | 'RESOLVED' | 'CLOSED'
export type TicketSenderType = 'USER' | 'ADMIN' | 'SYSTEM'

export interface AdminTicketQuery {
  keyword?: string
  userId?: string
  status?: string
  category?: string
  /** true 只看待客服回复的（球权在用户侧）；false 看未关闭的全部 */
  awaitingAdmin?: boolean
  createdFrom?: string
  createdTo?: string
  page?: number
  size?: number
}

export interface AdminTicketSummary {
  id: number
  ticketNo: string
  userId: string
  category: string
  categoryText: string
  title: string
  status: TicketStatus
  statusText: string
  orderId: number | null
  orderNo: string | null
  /** 球权：最后一条人工消息是谁发的。系统消息不抢球权 */
  lastReplyBy: TicketSenderType | null
  createdAt: string
  updatedAt: string
  resolvedAt: string | null
  closedAt: string | null
}

export interface TicketMessageView {
  id: number
  senderType: TicketSenderType
  senderId: string | null
  content: string
  createdAt: string
}

export interface AdminTicketDetail {
  ticket: AdminTicketSummary
  closeReason: string | null
  messages: TicketMessageView[]
}

export interface AdminTicketReplyRequest {
  content: string
}

export interface AdminTicketResolveRequest {
  content?: string
}

export interface AdminTicketCloseRequest {
  reason: string
}

export type { OrderResponse, AfterSale, Logistics }
