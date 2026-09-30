import request from '@/utils/axios'
import type { Payment, Refund } from '@/types/models'

/**
 * 创建支付单。金额由服务端从订单取（实付 = 总额 + 运费 - 优惠），
 * **前端传的任何金额都不被采信**。
 */
export async function createPayment(payload: { orderId: number; channel?: string; payType?: string }) {
  const response = await request.post('/payments', payload)
  return response.data.data as Payment
}

export async function getPayment(orderId: number) {
  const response = await request.get(`/payments/${orderId}`)
  return response.data.data as Payment
}

/**
 * 模拟支付成功（演示用）。
 * <p>
 * 后端扮演「渠道」发一次回调，走的是与真实渠道**完全相同**的处理路径
 * （含验签、终态检查、幂等、MQ 投递），所以演示过的链路也就是真实链路。
 * 该接口由服务端开关控制，默认关闭。
 */
export async function mockPay(orderId: number) {
  const response = await request.post(`/payments/${orderId}/mock-pay`)
  return response.data.data as Payment
}

// 这里原先有一个 refund()，调后端 POST /payments/refunds。
// 后端那个入口已删除：它绕过了退款唯一的闸门（售后流程的时间窗、比例上限、商家审核），
// 任何登录用户都能对自己的已支付订单单方面全额退款。要退款的入口只有一个——
// 提交售后申请，等商家审核通过后由订单服务发起。前端本来也没有视图调用它。

export async function listRefunds(orderId: number) {
  const response = await request.get(`/payments/refunds/${orderId}`)
  return response.data.data as Refund[]
}

export const PAY_CHANNELS = [
  { value: 'MOCK', label: '模拟支付', hint: '演示用，直接置为成功' },
  { value: 'ALIPAY', label: '支付宝', hint: '未对接真实渠道' },
  { value: 'WECHAT', label: '微信支付', hint: '未对接真实渠道' },
] as const
