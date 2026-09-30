import request from '@/utils/axios'
import type { AfterSale, AfterSaleDetail, AfterSalePreview } from '@/types/models'

export const AFTER_SALE_TYPES = [
  { value: 'REFUND_ONLY', label: '仅退款', hint: '不寄回商品，适合未收到货或协商一致' },
  { value: 'RETURN_REFUND', label: '退货退款', hint: '需要把商品寄回' },
  { value: 'EXCHANGE', label: '换货', hint: '同款同规格更换' },
] as const

/**
 * 预览售后资格。
 * <p>
 * 让用户在填表**之前**就知道能不能退、最多退多少，而不是提交完才被拒 ——
 * 后者会让人觉得是在碰运气。
 */
export async function previewAfterSale(orderItemId: number, type: string, qualityIssue = false) {
  const response = await request.get('/after-sales/preview', {
    params: { orderItemId, type, qualityIssue },
  })
  return response.data.data as AfterSalePreview
}

export async function applyAfterSale(payload: {
  orderItemId: number
  type: string
  reason: string
  description?: string
  images?: string[]
  qualityIssue?: boolean
}) {
  const response = await request.post('/after-sales', payload)
  return response.data.data as AfterSale
}

export async function listAfterSales() {
  const response = await request.get('/after-sales')
  return response.data.data as AfterSale[]
}

/** 详情含完整流转流水：单子到哪一步了、谁操作的，一次拿全 */
export async function getAfterSale(id: number) {
  const response = await request.get(`/after-sales/${id}`)
  return response.data.data as AfterSaleDetail
}

export async function cancelAfterSale(id: number) {
  const response = await request.post(`/after-sales/${id}/cancel`)
  return response.data.data as AfterSale
}

/** 审核通过后，用户把退货包裹寄回时登记物流 */
export async function shipBackAfterSale(id: number, payload: { carrier: string; trackingNo: string }) {
  const response = await request.post(`/after-sales/${id}/ship-back`, payload)
  return response.data.data as AfterSale
}

/** 售后原因。做成固定选项而不是纯自由文本：分类统计与政策匹配都依赖它 */
export const AFTER_SALE_REASONS = [
  '商品与描述不符',
  '质量问题',
  '包装破损',
  '收到错误商品',
  '不想要了',
  '其他',
] as const
