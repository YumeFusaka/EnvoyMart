import request from '@/utils/axios'
import type { PageResult } from '@/types/models'
import type {
  AdminSpuDetail,
  AdminSpuQuery,
  AdminSpuSummary,
  SpuUpsertRequest,
} from '@/types/admin'

/**
 * 管理端商品接口。
 * <p>
 * 路径前缀 `/products/admin`，网关按 `/admin` 路径**段**判据（`/admin(?:/|$)`）拦非管理员，
 * 下游每个控制器再挂 `@RequireAdmin` —— 前端这一层不承担鉴权职责，
 * 隐藏入口只是不把走不通的路摆出来。
 */

/** 分页拉取。页码是对外 **0 基**，与后端 `zeroBasedPage()` 对齐 */
export async function listSpus(query: AdminSpuQuery) {
  const response = await request.get('/products/admin/spus', { params: query })
  return response.data.data as PageResult<AdminSpuSummary>
}

export async function getSpu(id: number) {
  const response = await request.get(`/products/admin/spus/${id}`)
  return response.data.data as AdminSpuDetail
}

export async function createSpu(payload: SpuUpsertRequest) {
  const response = await request.post('/products/admin/spus', payload)
  return response.data.data as number
}

export async function updateSpu(id: number, payload: SpuUpsertRequest) {
  await request.put(`/products/admin/spus/${id}`, payload)
}

/** 上架 / 下架。上下架是独立动作而不是编辑表单里的一个字段，见后端 `changeStatus` */
export async function changeSpuStatus(id: number, status: number) {
  await request.put(`/products/admin/spus/${id}/status`, null, { params: { status } })
}

export async function deleteSpu(id: number) {
  await request.delete(`/products/admin/spus/${id}`)
}

export async function adjustStock(skuId: number, stock: number, remark?: string) {
  await request.put(`/products/admin/skus/${skuId}/stock`, { stock, remark })
}
