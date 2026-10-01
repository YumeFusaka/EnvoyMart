import request from '@/utils/axios'
import type { Logistics, PageResult } from '@/types/models'
import type {
  AdminOrderDetail,
  AdminOrderQuery,
  AdminOrderSummary,
  AdminShipRequest,
  AdminTraceRequest,
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

/**
 * 补录一条物流节点。
 *
 * 返回整条轨迹而不是刚写入的那一步：客服补录完要能立刻看到这一步落在时间轴的
 * 哪个位置，时间给错了（比如少打一位年份）当场就发现，而不是等用户来问。
 */
export async function addOrderTrace(id: number, payload: AdminTraceRequest) {
  const response = await request.post(`/orders/admin/orders/${id}/traces`, payload)
  return response.data.data as Logistics
}

/** 商家备注。改的是 `admin_remark` 字段，用户侧看不到，与用户备注 `remark` 是两列 */
export async function remarkOrder(id: number, remark: string) {
  const response = await request.put(`/orders/admin/orders/${id}/remark`, { remark })
  return response.data.data as AdminOrderSummary
}
