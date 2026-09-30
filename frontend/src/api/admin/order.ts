import request from '@/utils/axios'
import type { PageResult } from '@/types/models'
import type {
  AdminOrderDetail,
  AdminOrderQuery,
  AdminOrderSummary,
  AdminShipRequest,
} from '@/types/admin'

export async function listOrders(query: AdminOrderQuery) {
  const response = await request.get('/orders/admin/orders', { params: query })
  return response.data.data as PageResult<AdminOrderSummary>
}

export async function getOrderDetail(id: number) {
  const response = await request.get(`/orders/admin/orders/${id}`)
  return response.data.data as AdminOrderDetail
}

/**
 * 发货。承运商编码与名称都由前端传入而不是从固定字典查 ——
 * 本地字典只能覆盖已接入的几家，加一家就要发一次版。
 */
export async function shipOrder(id: number, payload: AdminShipRequest) {
  const response = await request.post(`/orders/admin/orders/${id}/ship`, payload)
  return response.data.data as AdminOrderSummary
}

/** 商家备注。改的是 `admin_remark` 字段，用户侧看不到，与用户备注 `remark` 是两列 */
export async function remarkOrder(id: number, remark: string) {
  const response = await request.put(`/orders/admin/orders/${id}/remark`, { remark })
  return response.data.data as AdminOrderSummary
}
