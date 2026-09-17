import request from '@/utils/axios'
import type { ApiResponse } from '@/types/models'

export interface PaymentResponse {
  id: number
  orderId: number
  orderNo: string
  amount: number
  status: string
  transactionNo?: string
  paidAt?: string
}

/**
 * 创建支付单。
 *
 * 请求体里只有 orderId。归属（userId）由网关从 JWT 注入，金额与订单号由订单服务裁决
 * ——**身份、金额、订单号都不该由客户端决定**。此前后两者来自请求体，等于让调用方决定
 * "这单多少钱"，实测能把 198 元的订单建成 0.01 元的支付单。
 * 返回值里的 amount / orderNo 才是服务端认定的值，页面应当以它为准。
 */
export function createPayment(payload: { orderId: number }) {
  return request.post<ApiResponse<PaymentResponse>>('/payments', payload)
}

export function getPayment(orderId: number) {
  return request.get<ApiResponse<PaymentResponse>>(`/payments/${orderId}`)
}
