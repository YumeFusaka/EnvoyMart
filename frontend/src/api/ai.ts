import request, { baseURL } from '@/utils/axios'
import { useUserStore } from '@/stores'
import type { ChatResponse } from '@/types/models'

export interface ChatPayload {
  sessionId: string
  message: string
  contextOrderId?: number
  /**
   * 确认令牌：上一轮响应下发的 `approvalToken`，用户点确认时原样带回。
   *
   * 它不是「已确认」这个开关，而是一张写明**要执行哪几次调用**的签名凭证。
   * 前端只是搬运工——改一个字符都会验签失败，服务端一条也不执行。
   */
  approvalToken?: string
}

export async function chat(payload: ChatPayload) {
  const response = await request.post('/ai/chat', payload)
  return response.data.data as ChatResponse
}

// ==================== 会话管理（侧栏） ====================

/** 侧栏一行。`title` 是首条用户消息的截断，由服务端给出，前端不自己拼 */
export interface ChatSessionSummary {
  sessionId: string
  title: string
  createdAt: string
  updatedAt: string
  messageCount: number
}

/**
 * 服务端存下的一条消息。
 *
 * `response` 就是当轮 `ChatResponse` 的 JSON（结构完全相同，只是反序列化时
 * 不落到具体类型上）——恢复历史时把它摊回 `ChatMessage`，引用卡片、工具轨迹、
 * 用量明细就能原样重现，而不是只剩一段光秃秃的文字。
 */
export interface StoredChatMessage {
  id: string
  role: 'user' | 'assistant'
  content: string
  at: string
  response: ChatResponse | null
}

export async function fetchSessions() {
  const response = await request.get('/ai/sessions')
  return response.data.data as ChatSessionSummary[]
}

export async function fetchSessionMessages(sessionId: string) {
  const response = await request.get(`/ai/sessions/${encodeURIComponent(sessionId)}/messages`)
  return response.data.data as StoredChatMessage[]
}

export async function deleteSession(sessionId: string) {
  await request.delete(`/ai/sessions/${encodeURIComponent(sessionId)}`)
}

export interface StreamHandlers {
  onDelta: (text: string) => void
  onDone: (response: ChatResponse) => void
  onError: (message: string) => void
}

/**
 * SSE 流式对话。用 fetch 而非 EventSource——后者不支持 POST。
 * 事件：delta 增量文本 / done 完整结果 / error 异常。
 * <p>
 * `signal` 用于用户中途停止：abort 会让 `reader.read()` 抛 AbortError 向上传播，
 * 由调用方区分处理（停止是用户的主动选择，不是故障）。也支持组件卸载时取消，
 * 避免离开页面后流还在后台跑。
 */
export async function chatStream(
  payload: ChatPayload,
  handlers: StreamHandlers,
  signal?: AbortSignal
) {
  const userStore = useUserStore()
  const headers: Record<string, string> = { 'Content-Type': 'application/json' }
  if (userStore.token) {
    headers.Authorization = 'Bearer ' + userStore.token
  }

  const response = await fetch(`${baseURL}/ai/chat/stream`, {
    method: 'POST',
    headers,
    body: JSON.stringify(payload),
    signal
  })

  if (!response.ok || !response.body) {
    handlers.onError(`请求失败：HTTP ${response.status}`)
    return
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''
  // 服务端保证每个流都以 done 或 error 收尾。两者都没见到流就断了（网络中断、
  // 服务端崩溃），此刻界面上的半截回答会被静默当成完整回答 —— 必须显式喊出来。
  // 副作用是把「重复 done」也挡住了：只有第一次终态事件会传到界面
  let settled = false

  const dispatch = (event: { name: string; data: string }) => {
    if (settled) return
    if (event.name === 'delta') {
      handlers.onDelta(event.data)
    } else if (event.name === 'done') {
      settled = true
      handlers.onDone(JSON.parse(event.data) as ChatResponse)
    } else if (event.name === 'error') {
      settled = true
      handlers.onError(event.data)
    }
  }

  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })

    // SSE 以空行分隔事件
    const events = buffer.split('\n\n')
    buffer = events.pop() ?? ''
    for (const raw of events) {
      const event = parseEvent(raw)
      if (event) dispatch(event)
    }
  }

  // 尾包：服务端收尾时可能不补最后的空行，buffer 里还压着一个完整事件
  const tail = parseEvent(buffer)
  if (tail) dispatch(tail)

  if (!settled) {
    handlers.onError('连接中断，回答可能不完整')
  }
}

function parseEvent(raw: string): { name: string; data: string } | null {
  let name = 'message'
  const dataLines: string[] = []
  for (const line of raw.split('\n')) {
    if (line.startsWith('event:')) {
      name = line.slice(6).trim()
    } else if (line.startsWith('data:')) {
      dataLines.push(line.slice(5).replace(/^ /, ''))
    }
  }
  if (!dataLines.length) return null
  return { name, data: dataLines.join('\n') }
}
