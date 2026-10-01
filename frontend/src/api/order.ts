import request from '@/utils/axios'
import type {
  Logistics,
  Order,
  OrderPreview,
  OrderTab,
  OrderTabCount,
  PageResult,
} from '@/types/models'

export interface CheckoutPayload {
  receiverName: string
  receiverPhone: string
  receiverProvince: string
  receiverCity: string
  receiverDistrict: string
  receiverDetail: string
  remark?: string
  /** 使用的优惠券（用户券 id），选填。传的是用户券而不是模板 */
  userCouponId?: number
}

/**
 * 下单。结算的是购物车里**已勾选**的条目。
 * <p>
 * 收货信息由前端从地址簿里选一条后展开传入，而不是传 addressId 让服务端回查 ——
 * 订单要存的是下单那一刻的快照，地址簿之后被改被删都与它无关。
 */
export async function checkout(payload: CheckoutPayload) {
  const response = await request.post('/orders/checkout', payload)
  return response.data.data as Order
}

/**
 * 我的订单，按状态页签分页。
 *
 * 筛选在服务端：页签的成员状态（如「退款/取消」= CANCELLED + CLOSED + REFUNDING + REFUNDED）
 * 只在后端 OrderTab 里定义一次。前端各存一份的话，筛选与角标计数就成了同一件事的两种说法。
 */
export async function listOrders(tab: OrderTab = 'ALL', page = 0, size = 10) {
  const response = await request.get('/orders', { params: { tab, page, size } })
  return response.data.data as PageResult<Order>
}

/** 各页签的数量。翻页时它不变，所以与列表分开取 —— 但两边同一套页签定义 */
export async function orderSummary() {
  const response = await request.get('/orders/summary')
  return response.data.data as OrderTabCount[]
}

/**
 * 结算页试算：金额、券的可用性与抵扣。
 *
 * 商品明细不传 —— 服务端拿购物车里已勾选的条目，与真正下单时装配的是同一段代码。
 * 前端把商品传上去「省一次查询」，就是「预览说券能用、提交却被拒」的复现路径。
 */
export async function previewOrder(userCouponId?: number | null) {
  const response = await request.post('/orders/preview', { userCouponId: userCouponId ?? null })
  return response.data.data as OrderPreview
}

export async function getOrder(id: number) {
  const response = await request.get(`/orders/${id}`)
  return response.data.data as Order
}

/**
 * 取消订单。仅未支付订单可取消，已支付的要走退款流程（后端会如实拒绝）。
 */
export async function cancelOrder(id: number) {
  const response = await request.post(`/orders/${id}/cancel`)
  return response.data.data as Order
}

/**
 * 确认收货。
 * <p>
 * 它是售后与评价的**前置条件**：订单不到「已收货」，政策引擎与评价校验都不会放行。
 */
export async function confirmReceipt(id: number) {
  const response = await request.post(`/orders/${id}/receive`)
  return response.data.data as Order
}

export async function getLogistics(id: number) {
  const response = await request.get(`/orders/${id}/logistics`)
  return response.data.data as Logistics
}

/** 把结构化的收货地址拼成一行展示文本 */
export function formatAddress(order: Order): string {
  return [order.receiverProvince, order.receiverCity, order.receiverDistrict, order.receiverDetail]
    .filter(Boolean)
    .join(' ')
}
