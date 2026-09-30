import request from '@/utils/axios'
import type { PageResult } from '@/types/models'
import type { AdminReviewDetail, AdminReviewQuery, AdminReviewSummary } from '@/types/admin'

export async function listReviews(query: AdminReviewQuery) {
  const response = await request.get('/reviews/admin/reviews', { params: query })
  return response.data.data as PageResult<AdminReviewSummary>
}

export async function getReviewDetail(id: number) {
  const response = await request.get(`/reviews/admin/reviews/${id}`)
  return response.data.data as AdminReviewDetail
}

/**
 * 隐藏 / 恢复。
 * <p>
 * 隐藏**必须填原因**（后端强制），恢复时后端会连同隐藏痕迹一起清空 ——
 * 前端这里不做「恢复也要填原因」的对称设计：隐藏是行使权力，要有依据；
 * 恢复是撤回自己的处置，没有需要留痕的新决定。
 */
export async function changeReviewStatus(id: number, status: string, reason?: string) {
  const response = await request.put(`/reviews/admin/reviews/${id}/status`, { status, reason })
  return response.data.data as AdminReviewSummary
}

/** 商家回复。传空串即撤回回复 */
export async function replyReview(id: number, content: string) {
  const response = await request.put(`/reviews/admin/reviews/${id}/reply`, { content })
  return response.data.data as AdminReviewSummary
}
