import request from '@/utils/axios'
import type { MyReview, PageResult, Review, ReviewStatistics } from '@/types/models'

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

/**
 * 按商品列评价。
 *
 * 分页与筛选都在服务端做：评价条数是会长的，拉全量回来在浏览器里筛，
 * 商品越热门越慢，而且星级分布与筛选结果会来自两个不同的快照。
 */
export async function listReviews(
  spuId: number,
  params: { rating?: number; hasImage?: boolean; page?: number; size?: number } = {},
) {
  const response = await request.get(`/reviews/spu/${spuId}`, { params })
  return response.data.data as PageResult<Review>
}

export async function getReviewStatistics(spuId: number) {
  const response = await request.get(`/reviews/spu/${spuId}/statistics`)
  return response.data.data as ReviewStatistics
}

/**
 * 我发表过的评价，**含被隐藏的**：用户有权知道自己写的东西现在是什么状态。
 * 从「我的评价」里抹掉，他只会以为是自己没发表成功，然后再发一遍。
 */
export async function listMyReviews(
  params: { orderId?: number; page?: number; size?: number } = {},
) {
  const response = await request.get('/reviews/mine', { params })
  return response.data.data as PageResult<MyReview>
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
