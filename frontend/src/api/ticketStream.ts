import { baseURL } from '@/utils/axios'
import { useUserStore } from '@/stores'

/**
 * 「等你回应」的推送载荷。与后端 {@code TicketAwaitingPayload} 一一对应。
 */
export interface TicketAwaitingEvent {
  awaitingMe: number
  ticketIds: number[]
}

/**
 * 订阅工单的「等你回应」推送。
 *
 * <p><b>用 fetch + ReadableStream 而不是 EventSource</b>：网关只认
 * `Authorization: Bearer` 头，而 `EventSource` 无法设置自定义请求头 —— 用它就只能
 * 把 token 塞进 URL（会进日志、进浏览器历史），或者给网关开一条按查询参数认证的口子
 * （等于给所有接口松一道绑）。`/ai/chat/stream` 早就因为同样的原因走了 fetch，
 * 这里沿用同一条路。
 *
 * <p>重连策略：<b>由调用方决定何时重订，本函数只负责这一次连接</b>。服务端把连接
 * 寿命定在 30 分钟（见 {@code TicketStreamHub.STREAM_TIMEOUT_MILLIS}），到点服务端
 * 正常收尾、这个循环结束，调用方据此重新订阅即可 —— 把重试放在这里会让组件卸载时
 * 留下的循环无人能停。
 *
 * @param onAwaiting 收到一帧推送（含初始快照）
 * @param signal     取消信号：组件卸载 / 离开工单页时 abort，连接随之关闭
 */
export async function subscribeTicketAwaiting(
  onAwaiting: (payload: TicketAwaitingEvent) => void,
  signal: AbortSignal
) {
  const userStore = useUserStore()
  const headers: Record<string, string> = { Accept: 'text/event-stream' }
  if (userStore.token) {
    headers.Authorization = 'Bearer ' + userStore.token
  }

  const response = await fetch(`${baseURL}/tickets/stream`, { headers, signal })
  if (!response.ok || !response.body) {
    throw new Error(`工单推送订阅失败：HTTP ${response.status}`)
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''

  // 与 api/ai.ts 同一套 SSE 解析：空行分隔事件，data 行以换行拼接
  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })

    const events = buffer.split('\n\n')
    buffer = events.pop() ?? ''
    for (const raw of events) {
      const event = parseEvent(raw)
      // 心跳是注释帧（以 `:` 开头），parseEvent 返回 null，天然被忽略
      if (event && event.name === 'awaiting') {
        try {
          onAwaiting(JSON.parse(event.data) as TicketAwaitingEvent)
        } catch {
          /* 单帧解析失败不掀掉整条流：角落标下一帧就会纠正回来 */
        }
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
