/**
 * 工具轨迹的展示层映射：工具名 → 中文、耗时 → 人读的单位、一串调用 → 一句话摘要。
 * <p>
 * 抽出来是因为有两个地方要看它：消息里的工具轨迹，和待确认的高危操作卡片。
 * 各存一份的话，服务端加个新工具就会一边显示中文、另一边显示 `coupon_query`。
 */

import type { ToolCall } from '@/types/models'

const TOOL_LABELS: Record<string, string> = {
  product_search: '商品检索',
  order_query: '订单查询',
  logistics_query: '物流查询',
  order_cancel: '订单取消',
  interaction_check: '成分相互作用核查',
}

/** 未知工具名原样显示 —— 服务端加了新工具而前端还没跟上时，看到 `coupon_query` 也比看到空白强 */
export function toolLabel(tool: string): string {
  return TOOL_LABELS[tool] ?? tool
}

/**
 * 高危操作描述的展示化：`order_cancel(orderId=12)` → `订单取消(orderId=12)`。
 * <p>
 * <b>只翻译括号前的工具名，参数原样保留。</b>参数是用户判断「要动的是哪一个对象」的唯一依据，
 * 翻译、截断或省略它都是在替用户改授权对象——一个写着「订单取消」却不说是哪一单的卡片，
 * 点了确认等于盲签。没有括号时整串按工具名处理。
 */
export function actionLabel(action: string): string {
  const at = action.indexOf('(')
  if (at < 0) {
    return toolLabel(action)
  }
  return toolLabel(action.slice(0, at)) + action.slice(at)
}

/**
 * 耗时的展示化。
 * <p>
 * 过千毫秒换算成秒：轨迹上真正要一眼分辨的是「几十毫秒」和「三秒半」，
 * `3500 ms` 得在脑子里做一次除法才排得出大小。
 * 不足 1 毫秒（本地缓存命中、参数校验就地拦下）不显示成 `0 ms`——
 * 那读起来像"没测"，而它是一次真实的、快到测不出粒度的调用。
 */
export function formatMs(ms: number): string {
  if (!Number.isFinite(ms) || ms <= 0) {
    return '<1 ms'
  }
  return ms < 1000 ? `${Math.round(ms)} ms` : `${(ms / 1000).toFixed(1)} s`
}

/**
 * 一次调用的结局。
 * <p>
 * <b>三态，不是二态。</b>「跑通了但什么都没查到」既不是成功——那会让绿标和旁边那句
 * 「没查到物流信息」自相矛盾；也不是失败——失败是这次没做成、该重试，
 * 无结果是这件事问到了、答案是「没有」。后端 `ToolResult.noData` 就是为这件事设的，
 * 压成两态等于把它一路建立的区分在最后一米丢掉。
 */
export type TraceOutcome = 'ok' | 'empty' | 'fail'

export function traceOutcome(call: ToolCall): TraceOutcome {
  if (!call.success) {
    return 'fail'
  }
  return call.noData ? 'empty' : 'ok'
}

const OUTCOME_LABELS: Record<TraceOutcome, string> = {
  ok: '成功',
  empty: '无结果',
  fail: '失败',
}

export function outcomeLabel(outcome: TraceOutcome): string {
  return OUTCOME_LABELS[outcome]
}

/**
 * 折叠状态下的那一行摘要。
 * <p>
 * 展开才看得到的东西，等于默认看不到。把「几次、共多久、有没有异常」提到 summary 上，
 * 收起时就能判断这一轮值不值得展开——异常次数尤其：它不该等着用户去逐条读返回文本。
 * 一切都正常时不写「0 次失败」，那只是噪音。
 */
export function traceSummary(calls: ToolCall[]): string {
  const failed = calls.filter((call) => traceOutcome(call) === 'fail').length
  const empty = calls.filter((call) => traceOutcome(call) === 'empty').length
  const total = formatMs(calls.reduce((sum, call) => sum + (call.latencyMs || 0), 0))
  const notes = [
    failed > 0 ? `${failed} 次失败` : '',
    empty > 0 ? `${empty} 次无结果` : '',
  ].filter(Boolean)
  return notes.length > 0 ? `共 ${total} · ${notes.join(' · ')}` : `共 ${total}`
}
