import { createHash } from 'node:crypto'

// JSON 对象键的顺序不是持久化契约，数组顺序则是历史消息与评测明细的契约。
export function canonical(value) {
  if (Array.isArray(value)) return value.map(canonical)
  if (value && typeof value === 'object') {
    return Object.fromEntries(Object.keys(value).sort().map((key) => [key, canonical(value[key])]))
  }
  return value
}

export function digest(value) {
  return createHash('sha256').update(JSON.stringify(canonical(value))).digest('hex')
}

export function validReport(value, kind) {
  const count = kind === 'grounding' ? 36 : 70
  const metrics = kind === 'grounding' ? value?.metrics : value?.overall
  return value?.status === 'COMPLETED' && Boolean(value.runId) &&
    value.cases?.length === count && metrics?.caseCount === count &&
    (kind !== 'grounding' || (value.totalCases === count && value.completedCases === count && value.failedCases === 0))
}

export function reportEvidence(value) {
  return {
    runId: value.runId, status: value.status, cases: value.cases.length,
    metrics: value.metrics ?? value.overall, digest: digest(value),
  }
}

export function messagesEvidence(messages) {
  return messages.map((message) => ({
    id: message.id, role: message.role, turnId: message.turnId ?? null,
    requestId: message.response?.requestId ?? null,
    toolCount: message.response?.toolCalls?.length ?? 0,
    evidenceCount: message.response?.knowledge?.length ?? 0,
    digest: digest(message),
  }))
}

export function restoredMessages(expected, actual) {
  const byId = new Map(actual.map((message) => [message.id, message]))
  const filtered = actual.filter((message) => expected.some((old) => old.id === message.id))
  return new Set(actual.map((message) => message.id)).size === actual.length &&
    expected.every((message) => byId.has(message.id) && digest(byId.get(message.id)) === digest(message)) &&
    digest(filtered) === digest(expected)
}

export function restarted(before, after) {
  return Boolean(before?.pid && before?.startedAt && after?.pid && after?.startedAt) &&
    (before.pid !== after.pid || before.startedAt !== after.startedAt)
}

export function readOnlyRpc(method) {
  return ['initialize', 'notifications/initialized', 'tools/list'].includes(method)
}

export function rpcResult(text) {
  if (!text.trim()) return null
  const data = text.split('\n').find((line) => line.trim().startsWith('data:'))
  return JSON.parse(data ? data.slice(data.indexOf(':') + 1).trim() : text)
}

export function seedMessage(sessionId, messageId, messages) {
  if (!sessionId?.startsWith('t1-') || !messageId) return null
  const index = messages.findIndex((value) => value.id === messageId)
  const message = messages[index]
  return index > 0 && messages[index - 1].role === 'user' && message?.role === 'assistant' && message.response ? message : null
}
