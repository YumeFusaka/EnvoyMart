import request from '@/utils/axios'
import type { Review, ReviewStatistics } from '@/types/models'

/**
 * 发表评价。粒度是**订单行**，所以传的是 `orderItemId` 而不是商品 id ——
 * 商品信息由服务端从订单行反查，调用方无法决定自己在评哪个商品。
 */
export async function createReview(payload: {
  orderId: number
  orderItemId: number
  rating: number
  content?: string
  images?: string[]
  anonymous?: boolean
}) {
  const response = await request.post('/reviews', payload)
  return response.data.data as Review
}

export async function listReviews(spuId: number) {
  const response = await request.get(`/reviews/spu/${spuId}`)
  return response.data.data as Review[]
}

export async function getReviewStatistics(spuId: number) {
  const response = await request.get(`/reviews/spu/${spuId}/statistics`)
  return response.data.data as ReviewStatistics
}

export async function markReviewUseful(id: number) {
  await request.post(`/reviews/${id}/useful`)
}

/** 评分文案。5 档固定说法，避免各处自己拼 */
export function ratingText(rating: number): string {
  return ['', '很差', '较差', '一般', '满意', '非常满意'][rating] ?? ''
}

/** 评分分布的最大值，用于算柱状图宽度 */
export function maxBucket(distribution: number[]): number {
  return Math.max(1, ...distribution)
}
