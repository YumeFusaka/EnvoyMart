import request from '@/utils/axios'
import type { Logistics, Order } from '@/types/models'

export interface CheckoutPayload {
  receiverName: string
  receiverPhone: string
  receiverProvince: string
  receiverCity: string
  receiverDistrict: string
  receiverDetail: string
  remark?: string
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

export async function listOrders() {
  const response = await request.get('/orders')
  return response.data.data as Order[]
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
