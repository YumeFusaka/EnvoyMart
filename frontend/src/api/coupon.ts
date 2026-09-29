import request from '@/utils/axios'
import type { Coupon, UserCoupon } from '@/types/models'

/** 领券中心：当前可领的券，带「是否已领过」标记 */
export async function listAvailableCoupons() {
  const response = await request.get('/coupons/available')
  return response.data.data as Coupon[]
}

export async function receiveCoupon(couponId: number) {
  const response = await request.post(`/coupons/${couponId}/receive`)
  return response.data.data as UserCoupon
}

/**
 * 我的券。
 *
 * @param orderAmount 传订单金额（分）时，会算出每张券「现在能不能用、差多少」。
 *                    结算页用它，让用户一眼看出哪张用不了
 */
export async function listMyCoupons(status?: string, orderAmount?: number) {
  const response = await request.get('/coupons/mine', { params: { status, orderAmount } })
  return response.data.data as UserCoupon[]
}
