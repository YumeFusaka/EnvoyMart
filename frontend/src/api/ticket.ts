import request from '@/utils/axios'
import type {
  PageResult,
  Ticket,
  TicketCategory,
  TicketDetail,
  TicketSummary,
} from '@/types/models'

/**
 * 发起工单。第一条消息（用户的问题描述）随工单一起提交 ——
 * 分两步「先建单再补内容」会留下一批只有标题的空工单，而客服点开它什么都看不到。
 */
export async function createTicket(payload: {
  category: TicketCategory
  title: string
  content: string
  /** 关联订单，可空：不是所有工单都关于某张订单。带 id 而不是 orderNo —— 服务端要校验它属于当前用户 */
  orderId?: number
}) {
  const response = await request.post('/tickets', payload)
  return response.data.data as TicketDetail
}

export async function listMyTickets(
  params: { status?: string; page?: number; size?: number } = {},
) {
  const response = await request.get('/tickets', { params })
  return response.data.data as PageResult<Ticket>
}

export async function getTicketDetail(id: number) {
  const response = await request.get(`/tickets/${id}`)
  return response.data.data as TicketDetail
}

/** 追加说明。工单已关闭时服务端 409 —— 关闭是终态，新问题请新开工单 */
export async function appendTicketMessage(id: number, content: string) {
  const response = await request.post(`/tickets/${id}/messages`, { content })
  return response.data.data as TicketDetail
}

/** 关闭自己的工单：已解决的 = 确认解决，其余 = 自行撤销。用户不需要填原因 */
export async function closeTicket(id: number) {
  const response = await request.post(`/tickets/${id}/close`)
  return response.data.data as TicketDetail
}

/** 重开：只对「已解决」的工单开放，可选附一句「哪个问题还在」 */
export async function reopenTicket(id: number, content?: string) {
  const response = await request.post(`/tickets/${id}/reopen`, content ? { content } : {})
  return response.data.data as TicketDetail
}

/** 计数摘要：筛选页签与顶栏角标共用 */
export async function getTicketSummary() {
  const response = await request.get('/tickets/summary')
  return response.data.data as TicketSummary
}

/**
 * 可选的分类。**展示用的中文名一律以服务端下发的 `categoryText` 为准**，
 * 这份列表只服务于「新建工单」这个表单 —— 表单要在提交前把四个选项摆出来，
 * 而服务端对非法分类是按 400 拒绝的（不会静默落到「其他」）。
 * <p>
 * 下方 `label` 与后端 `TicketCategory.text()` 是同一份文案，验收脚本会逐个分类建单，
 * 比对返回的 `categoryText` 与本表是否一致 —— 文案分叉会当场变红。
 */
export const TICKET_CATEGORY_OPTIONS: {
  value: TicketCategory
  label: string
  hint: string
}[] = [
  { value: 'ORDER', label: '订单问题', hint: '不发货、少发漏发、订单状态异常' },
  { value: 'REFUND', label: '退款售后', hint: '退款进度、售后被拒' },
  { value: 'PRODUCT', label: '商品咨询', hint: '成分、适用人群、保质期' },
  { value: 'OTHER', label: '其他', hint: '上面都不像的问题' },
]

export function ticketCategoryLabel(value: TicketCategory | string): string {
  return TICKET_CATEGORY_OPTIONS.find((item) => item.value === value)?.label ?? String(value)
}

/**
 * 状态标签的配色。文案不在这里 —— 用服务端给的 `statusText`。
 * <p>
 * `OPEN` 用警示色、`PROCESSING` 用主色、`RESOLVED` 用成功色：这三档的差别
 * 正是用户扫一眼列表时想知道的「轮到谁了」。
 */
export function ticketStatusTagType(status: string): 'warning' | 'primary' | 'success' | 'info' {
  if (status === 'OPEN') return 'warning'
  if (status === 'PROCESSING') return 'primary'
  if (status === 'RESOLVED') return 'success'
  return 'info'
}

/**
 * 球权文案。用户看的不是「最后谁回复了」，而是「现在该谁了」。
 * <p>
 * 服务端的 `lastReplyBy` 是原始事实（最后一条人工消息的发送方），
 * 翻译成用户视角要靠工单**状态**一起判：已关闭就不谈球权了。
 */
export function ticketTurnText(ticket: { status: string; lastReplyBy: string | null }): string {
  if (ticket.status === 'CLOSED') return '已结束'
  if (ticket.status === 'RESOLVED') return '等你确认'
  return ticket.lastReplyBy === 'ADMIN' ? '客服已回复' : '等客服回复'
}
