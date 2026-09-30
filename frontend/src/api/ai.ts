import request, { baseURL } from '@/utils/axios'
import { useUserStore } from '@/stores'
import type { ChatResponse } from '@/types/models'

export interface ChatPayload {
  sessionId: string
  message: string
  contextOrderId?: number
  /**
   * 用户已确认高危操作。
   * <p>
   * 置位后服务端会跳过执行图里的拦截，**放行本轮计划中所有高危步骤**——
   * 而计划是服务端重新推导的，所以这个位只能在用户明确确认的那一刻置位，
   * 不能因为「上一条消息提到过确认」就默认带上。
   */
  approved?: boolean
}

export async function chat(payload: ChatPayload) {
  const response = await request.post('/ai/chat', payload)
  return response.data.data as ChatResponse
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
