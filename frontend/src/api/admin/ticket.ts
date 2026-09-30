import request from '@/utils/axios'
import type { PageResult } from '@/types/models'
import type { AdminTicketDetail, AdminTicketQuery, AdminTicketSummary } from '@/types/admin'

export async function listTickets(query: AdminTicketQuery) {
  const response = await request.get('/tickets/admin/tickets', { params: query })
  return response.data.data as PageResult<AdminTicketSummary>
}

export async function getTicketDetail(id: number) {
  const response = await request.get(`/tickets/admin/tickets/${id}`)
  return response.data.data as AdminTicketDetail
}

/**
 * 回复。**首次回复即接手**：后端会把 OPEN 顺带推进到 PROCESSING。
 * <p>
 * 前端不先要求点一次「接手」再发言 —— 那是两步操作一个意图，
 * 而没接手的工单在列表上仍显示「待处理」，等于此刻的界面在撒谎。
 */
export async function replyTicket(id: number, content: string) {
  const response = await request.post(`/tickets/admin/tickets/${id}/reply`, { content })
  return response.data.data as AdminTicketDetail
}

/** 标记解决。说明可选 —— 「已解决」这个动作本身已经表达了结论 */
export async function resolveTicket(id: number, content?: string) {
  const response = await request.post(`/tickets/admin/tickets/${id}/resolve`, { content })
  return response.data.data as AdminTicketDetail
}

/** 关闭。原因**必填**，且会作为一条带操作人的消息落进会话，用户看得到是谁、为什么关的 */
export async function closeTicket(id: number, reason: string) {
  const response = await request.post(`/tickets/admin/tickets/${id}/close`, { reason })
  return response.data.data as AdminTicketDetail
}
