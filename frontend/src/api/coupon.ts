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
 * 我的券（券包）。
 *
 * 不算「在某单上能不能用」—— 那件事要订单行明细，走结算页试算（`previewOrder`）。
 * 曾经这里收一个订单金额就地算，但它只有总额、没有商品明细，
 * 限类目的券于是「预览说能用、提交被拒」。
 *
 * @param status UNUSED / USED / EXPIRED；不传则全部
 */
export async function listMyCoupons(status?: string) {
  const response = await request.get('/coupons/mine', { params: { status } })
  return response.data.data as UserCoupon[]
}
