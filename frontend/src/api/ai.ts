import request, { baseURL } from '@/utils/axios'
import { useUserStore } from '@/stores'
import type { ChatResponse } from '@/types/models'

export interface ChatPayload {
  sessionId: string
  message: string
  contextOrderId?: number
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
 */
export async function chatStream(payload: ChatPayload, handlers: StreamHandlers) {
  const userStore = useUserStore()
  const headers: Record<string, string> = { 'Content-Type': 'application/json' }
  if (userStore.token) {
    headers.Authorization = 'Bearer ' + userStore.token
  }

  const response = await fetch(`${baseURL}/ai/chat/stream`, {
    method: 'POST',
    headers,
    body: JSON.stringify(payload)
  })

  if (!response.ok || !response.body) {
    handlers.onError(`请求失败：HTTP ${response.status}`)
    return
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''

  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })

    // SSE 以空行分隔事件
    const events = buffer.split('\n\n')
    buffer = events.pop() ?? ''
    for (const raw of events) {
      const event = parseEvent(raw)
      if (!event) continue
      if (event.name === 'delta') {
        handlers.onDelta(event.data)
      } else if (event.name === 'done') {
        handlers.onDone(JSON.parse(event.data) as ChatResponse)
      } else if (event.name === 'error') {
        handlers.onError(event.data)
      }
    }
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
