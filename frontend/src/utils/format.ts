/**
 * 展示层格式化工具。
 * <p>
 * 时间统一走这里，而不是在各页面 `createdAt.replace('T', ' ')` ——
 * 那种切片法在缺值时不会报错，只会静默显示空白。
 */
export function formatDate(value: string | null | undefined): string {
  if (!value) {
    return '—'
  }
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return value
  }
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}

/**
 * 分 → 元输入框里的文本。
 * <p>
 * 空值给空串而不是 `"0.00"`：运营打开一个还没定价的规格，看到预填的 0
 * 会以为「零元也是合法的」，直接保存就把商品挂成了免费。
 */
export function toYuan(cents: number | null | undefined): string {
  return cents === null || cents === undefined ? '' : (cents / 100).toFixed(2)
}

/**
 * 元文本 → 分。非法输入返回 {@code null}，由调用方决定是拒绝还是按「没填」处理。
 * <p>
 * 用 {@code Math.round} 而不是直接乘：`19.9 * 100` 在浮点下是 `1989.9999…`，
 * 截断会得到 1989 分，一分钱就这样凭空消失了。
 */
export function parseYuan(text: string | null | undefined): number | null {
  const trimmed = (text ?? '').trim()
  if (!trimmed) {
    return null
  }
  if (!/^\d+(\.\d{1,2})?$/.test(trimmed)) {
    return null
  }
  return Math.round(Number(trimmed) * 100)
}

/** 时钟时间，形如 `14:32`。消息头上的时间用它 */
export function formatClock(value: string | null | undefined): string {
  if (!value) return ''
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return ''
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${pad(date.getHours())}:${pad(date.getMinutes())}`
}

/**
 * 会话列表用的时间：今天/昨天给时钟，更早给日期。
 * 侧栏一行宽度有限，写完整的年月日时分会把标题挤没。
 */
export function formatChatStamp(value: string | null | undefined): string {
  if (!value) return ''
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return ''
  const now = new Date()
  const startOfToday = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime()
  const stamp = date.getTime()
  if (stamp >= startOfToday) return formatClock(value)
  if (stamp >= startOfToday - 24 * 3600 * 1000) return '昨天'
  if (date.getFullYear() === now.getFullYear()) {
    return `${date.getMonth() + 1}月${date.getDate()}日`
  }
  return `${date.getFullYear()}年${date.getMonth() + 1}月${date.getDate()}日`
}

export function formatDateTime(value: string | null | undefined): string {
  if (!value) {
    return '—'
  }
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return value
  }
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${formatDate(value)}:${pad(date.getSeconds())}`
}
