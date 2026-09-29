/**
 * 知识层的展示层转换。
 *
 * 与后端 ToolOutputFormattingTest 守的是同一条规矩：**给用户看的文本必须经过转换**。
 * 枚举名（`manual`）、原始分值（0.7312）这类东西直接落到界面上，用户看到的是
 * 一串内部标识 —— 而它们本来都是有中文说法的。
 */

/** 文档类型 → 中文。决定这条依据属于厂商说明还是平台规则，用户要能分辨 */
const SOURCE_LABELS: Record<string, string> = {
  manual: '厂商说明书',
  policy: '平台规则',
  regulation: '监管规范',
  spec: '参数规格',
  guideline: '权威指南',
  // 图谱依据不是文档里的某一句话，而是「由若干句话推出的关系」。
  // 标成「厂商说明书」会让用户以为自己看到的是原文，找不到那句话时以为系统出错了
  graph: '图谱推导',
}

/** 领域范围 → 中文，兼作知识库的分类导航 */
const SCOPE_LABELS: Record<string, string> = {
  nutrition: '营养健康',
  after_sale: '售后服务',
  logistics: '物流配送',
  payment: '支付开票',
  promotion: '促销活动',
  food_safety: '食品安全',
}

export const SOURCE_OPTIONS = Object.entries(SOURCE_LABELS).map(([value, label]) => ({
  value,
  label,
}))
export const SCOPE_OPTIONS = Object.entries(SCOPE_LABELS).map(([value, label]) => ({
  value,
  label,
}))

/** 未知枚举值原样返回：宁可显示 `manual_v2`，也不要显示一个空白 */
export function sourceLabel(value: string | null | undefined): string {
  if (!value) return ''
  return SOURCE_LABELS[value] ?? value
}

export function scopeLabel(value: string | null | undefined): string {
  if (!value) return ''
  return SCOPE_LABELS[value] ?? value
}

/**
 * 相关性措辞。
 *
 * <b>两种分数的量纲不同，不能共用一个标题</b>：重排分是 cross-encoder 的判别输出，
 * 余弦相似度是向量夹角。同一个 0.6 在两种尺子上含义并不相同，所以措辞里带上来源。
 * 分值为 null 时明确说「未提供」——它表示这条链路没给相关性信号，
 * 把它显示成 0 或「低」都是在拿工程缺陷当成对文档的判断。
 */
export function relevanceText(score: number | null, reranked: boolean | null): string {
  if (score === null || score === undefined) return '相关度未提供'
  const value = score.toFixed(2)
  return reranked === false ? `向量相似度 ${value}` : `重排相关度 ${value}`
}

/** 回答正文的一段：普通文本，或一个引用角标 */
export type Segment = { type: 'text'; text: string } | { type: 'cite'; index: number }

/**
 * 把回答正文按 `[n]` 切成可渲染的段落。
 *
 * 提示词要求模型「凡结论来自依据，必须在句末标注来源编号」，所以正文里的 `[1]`
 * 是**唯一**能把一句话与它的依据对上的信息 —— 点它要能跳过去。
 *
 * <b>为什么切段而不是 v-html</b>：正文是模型生成的，直接塞进 `innerHTML` 等于
 * 把模型输出当 HTML 执行。切段之后每个片段都走 Vue 的文本插值，标签不可能变成元素。
 * 这也顺手解决了「正则替换回字符串再 v-html」那条路必然要面对的转义问题。
 *
 * 编号越界（`[7]` 但只有 3 条依据）时不当作引用，按原文渲染 ——
 * 提示词明令禁止编造编号，但它真的编了的时候，界面不该给出一个点不动的角标。
 */
export function splitCitations(text: string, available: number): Segment[] {
  const segments: Segment[] = []
  const pattern = /\[(\d{1,2})\]/g
  let cursor = 0
  let match: RegExpExecArray | null

  while ((match = pattern.exec(text)) !== null) {
    const index = Number(match[1])
    if (index < 1 || index > available) continue

    if (match.index > cursor) {
      segments.push({ type: 'text', text: text.slice(cursor, match.index) })
    }
    segments.push({ type: 'cite', index })
    cursor = match.index + match[0].length
  }

  if (cursor < text.length) {
    segments.push({ type: 'text', text: text.slice(cursor) })
  }
  return segments
}
