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
 * 归属（userId）由网关从 JWT 注入，请求体里不带——**身份和金额都不该由客户端决定**。
 * 金额目前仍来自请求体（后端未从订单校验），这是已知限制，见 docs/项目总览.md。
 */
export function createPayment(payload: { orderId: number; orderNo: string; amount: number }) {
  return request.post<ApiResponse<PaymentResponse>>('/payments', payload)
}

export function getPayment(orderId: number) {
  return request.get<ApiResponse<PaymentResponse>>(`/payments/${orderId}`)
}
