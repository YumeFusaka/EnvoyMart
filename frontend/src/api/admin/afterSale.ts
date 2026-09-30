import request from '@/utils/axios'
import type { AfterSale, PageResult } from '@/types/models'
import type { AdminAfterSaleDetail, AdminAfterSaleQuery } from '@/types/admin'

export async function listAfterSales(query: AdminAfterSaleQuery) {
  const response = await request.get('/after-sales/admin/after-sales', { params: query })
  return response.data.data as PageResult<AfterSale>
}

export async function getAfterSaleDetail(id: number) {
  const response = await request.get(`/after-sales/admin/after-sales/${id}`)
  return response.data.data as AdminAfterSaleDetail
}

/**
 * 审核。`approved` 走 **query 参数**而不是请求体（与后端 `@RequestParam` 一致）——
 * 这是个二值裁决，包成对象只是为了让 POST 有个 body 好看。
 */
export async function auditAfterSale(id: number, approved: boolean, remark?: string) {
  const response = await request.post(`/after-sales/admin/after-sales/${id}/audit`, null, {
    params: { approved, remark },
  })
  return response.data.data as AfterSale
}

/** 确认收到退货。只有「已通过且需寄回」的售后才走得通，其余由后端如实拒绝 */
export async function confirmReceived(id: number) {
  const response = await request.post(`/after-sales/admin/after-sales/${id}/received`)
  return response.data.data as AfterSale
}

/** 重试退款。给「审核已过但打款失败」的单子一个出口，而不是让它永远卡在中间态 */
export async function retryRefund(id: number) {
  const response = await request.post(`/after-sales/admin/after-sales/${id}/retry-refund`)
  return response.data.data as AfterSale
}
