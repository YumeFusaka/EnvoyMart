/**
 * 工具轨迹的展示层映射：工具名 → 中文、耗时 → 人读的单位、一串调用 → 一句话摘要。
 * <p>
 * 抽出来是因为有两个地方要看它：消息里的工具轨迹，和待确认的高危操作卡片。
 * 各存一份的话，服务端加个新工具就会一边显示中文、另一边显示 `coupon_query`。
 */

import type { ChatUsage, ToolCall } from '@/types/models'

/**
 * 工具名 → 中文标签。
 * <p>
 * <b>这是一份必须与后端工具表保持一致的全量清单。</b>漏登记的后果不是显示空白，
 * 而是用户在高危操作确认卡片上看到 `cart_checkout(...)` 这样的原始代码——
 * 而那张卡片正是他判断「要点确认的是哪一件事」的唯一依据。
 * 后端新增工具时，这里必须同步；漏了也不会报错，只会静默退化。
 */
const TOOL_LABELS: Record<string, string> = {
  product_search: '商品检索',
  order_query: '订单查询',
  logistics_query: '物流查询',
  order_cancel: '订单取消',
  interaction_check: '成分相互作用核查',
  address_list: '收货地址查询',
  cart_query: '购物车查询',
  cart_add: '加入购物车',
  cart_checkout: '提交订单',
  after_sale_apply: '申请售后',
  knowledge_search: '知识库检索',
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

/**
 * 这次调用确立的事实，摊成可直接渲染的 `[标签, 取值]` 列表。
 * <p>
 * 排序而不按对象键序：`Map.of` 出来的顺序在 Java 侧本来就是随机的，
 * 两次同样的查询摆出两个不同次序，看起来像数据变了。
 */
export function factEntries(call: ToolCall): [string, string][] {
  const facts = call.facts
  if (!facts) {
    return []
  }
  return Object.entries(facts)
    .filter(([label, value]) => label && value)
    .sort(([a], [b]) => a.localeCompare(b, 'zh'))
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

/**
 * token 数的展示化：`12480` → `12.5k`。
 * <p>
 * 四位以上的数字在对话流里会抢走对正文的注意力，而这里要传达的只是量级。
 * <b>不足一千的原样显示</b>——那正是「这一问很便宜」的信息，压成 `0.4k` 反而看不出便宜。
 */
export function formatTokens(count: number): string {
  if (!Number.isFinite(count) || count <= 0) {
    return '0'
  }
  return count < 1000 ? String(count) : `${(count / 1000).toFixed(1)}k`
}

export function formatCost(cny: number | null | undefined): string {
  if (cny === null || cny === undefined || !Number.isFinite(cny)) {
    return '—'
  }
  // 一次问答通常在 0.001–0.05 元之间，两位小数会全部显示成 ¥0.00
  if (cny > 0 && cny < 0.01) {
    return `¥${cny.toFixed(4)}`
  }
  return `¥${cny.toFixed(2)}`
}

/**
 * 折叠状态下的用量摘要，形如 `12.5k tokens · ≈¥0.021`。
 * <p>
 * 金额一律带「≈」：它是按配置的单价估出来的，不是账单——真实计费还有阶梯价、
 * 缓存命中折扣这些差异。没有「≈」的金额会被当成事实引用。
 * <p>
 * 没有任何模型配单价时只显示 token：显示 `¥0.00` 会被读成「这一轮免费」，
 * 而真相是「不知道多少钱」。缺了哪些模型的单价由展开后的明细去说。
 */
export function usageSummary(usage: ChatUsage): string {
  const tokens = `${formatTokens(usage.totalTokens)} tokens`
  return usage.costCny === null ? tokens : `${tokens} · ≈${formatCost(usage.costCny)}`
}

/** 参数名 → 中文标签：结构化卡片上把 `orderId` 显示成「订单号」 */
const ARG_LABELS: Record<string, string> = {
  orderId: '订单内部 ID',
  orderNo: '订单编号',
  spuId: '商品编号',
  skuId: '规格编号',
  productName: '商品',
  specification: '规格与价格',
  quantity: '数量',
  addressId: '收货地址',
  type: '类型',
  reason: '原因',
  qualityIssue: '质量问题',
  orderItemId: '订单行号',
  keyword: '关键词',
  receiverName: '收件人',
  receiverPhone: '联系电话',
  receiverProvince: '省份',
  receiverCity: '城市',
  receiverDistrict: '区县',
  receiverDetail: '详细地址',
  query: '检索词',
}

export function argLabel(key: string): string {
  return ARG_LABELS[key] ?? key
}

/**
 * 结构化参数摊成 [[标签, 取值]]，供确认卡片逐行渲染。
 *
 * 键名走中文映射、值保持原文——**取值不做任何翻译或截断**：它是用户核对
 * 「要动的是哪一个对象」的依据，改了取值就等于替用户改了授权对象。
 */
export function argEntries(arguments_: Record<string, unknown> | undefined): [string, string][] {
  if (!arguments_) {
    return []
  }
  return Object.entries(arguments_)
    .filter(([, value]) => value !== null && value !== undefined && value !== '')
    .map(([key, value]) => [argLabel(key), Array.isArray(value) ? value.join('、') : String(value)] as [string, string])
}
