import { randomUUID } from 'node:crypto'
import { deflateSync } from 'node:zlib'

export function endpointKey(method, path) { return method.toUpperCase() + ' ' + path }
export function rows(value) {
  if (Array.isArray(value)) return value
  if (Array.isArray(value?.records)) return value.records
  throw new Error('响应不是数组或records分页，不能把错误响应当空数据')
}
export function assert(condition, message) { if (!condition) throw new Error(message) }
/** 浏览器SSE按原UTF-8字节解析，不依赖自动推测的response.text字符集。 */
export function parseSseDone(buffer) {
  const frames = Buffer.from(buffer).toString('utf8').replaceAll('\r\n', '\n').split('\n\n')
  const done = frames.findLast((frame) => /^event:\s*done\s*$/m.test(frame))
  const data = done?.split('\n').filter((line) => line.startsWith('data:')).map((line) => line.slice(5).trimStart()).join('\n')
  return data ? JSON.parse(data) : null
}
/** Chrome CDP可能重编码SSE；通过浏览器透明clone读原UTF-8响应，原请求/Response不变。 */
export async function installBrowserSseCapture(context) {
  await context.addInitScript(() => {
    const original = window.fetch.bind(window)
    window.__verifyLiveSse = []
    window.fetch = async (...args) => {
      const response = await original(...args)
      if (new URL(response.url).pathname === '/ai/chat/stream' && response.headers.get('content-type')?.includes('text/event-stream')) {
        void response.clone().text().then((text) => {
          if (text.length > 2000000) throw new Error('SSE捕获超过上限')
          const frames = text.replaceAll('\r\n', '\n').split('\n\n')
          const done = frames.findLast((frame) => /^event:\s*done\s*$/m.test(frame))
          const json = done?.split('\n').filter((line) => line.startsWith('data:')).map((line) => line.slice(5).trimStart()).join('\n')
          if (json) window.__verifyLiveSse.push({ httpStatus: response.status, data: JSON.parse(json) })
        }).catch((error) => window.__verifyLiveSse.push({ error: error.message }))
      }
      return response
    }
  })
}
/** 每个页面主写都必须能在同一步找到成功点击请求与其后的权威读取。 */
export function verifyPageWriteEvidence(result) {
  if (result?.status !== 'PASS' || result.state !== 'write' || !result.clicks?.length || result.errors?.length) return false
  return result.clicks.every((click) => click.method !== 'GET' && click.requestId && click.httpStatus === 200 && click.code === 200
    && result.authority?.some((read) => read.method === 'GET' && read.requestId && read.httpStatus === 200 && read.code === 200
      && (!read.capturedAt || !click.capturedAt || read.capturedAt >= click.capturedAt)))
}
/** 公共读取编号不能获得写权限；写归属只认本轮原POST及精确标题/状态。 */
export function verifyOwnedDocumentFixture(document, fixture, calls, allowedStatuses = [0]) {
  return Boolean(fixture.ownedDocNo && fixture.ownedDocTitle && fixture.ownedDocCreationRequestId
    && document?.docNo === fixture.ownedDocNo && document.title === fixture.ownedDocTitle && allowedStatuses.includes(document.status)
    && calls.some((call) => call.key === 'POST /knowledge/admin/documents' && call.requestId === fixture.ownedDocCreationRequestId
      && call.code === 200 && call.response === fixture.ownedDocNo))
}
/** 本地生成一像素PNG作上传夹具，不读取个人图片或上传外站。 */
export function pngFixture() {
  const crc = (buffer) => {
    let value = 0xffffffff
    for (const byte of buffer) {
      value ^= byte
      for (let bit = 0; bit < 8; bit += 1) value = (value >>> 1) ^ ((value & 1) ? 0xedb88320 : 0)
    }
    return (value ^ 0xffffffff) >>> 0
  }
  const chunk = (name, payload) => {
    const type = Buffer.from(name, 'ascii')
    const length = Buffer.alloc(4); length.writeUInt32BE(payload.length)
    const checksum = Buffer.alloc(4); checksum.writeUInt32BE(crc(Buffer.concat([type, payload])))
    return Buffer.concat([length, type, payload, checksum])
  }
  const header = Buffer.alloc(13)
  header.writeUInt32BE(1, 0); header.writeUInt32BE(1, 4); header[8] = 8; header[9] = 6
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', header), chunk('IDAT', deflateSync(Buffer.from([0, 30, 120, 90, 255]))), chunk('IEND', Buffer.alloc(0))])
}
export function validateReadResponse(path, value) {
  if (path === '/ai/graph') return typeof value === 'string' && /graph|flowchart/.test(value)
  const arrays = ['/auth/addresses', '/cart', '/coupons/available', '/coupons/mine', '/brands', '/brands/admin', '/categories/tree', '/categories/admin/tree', '/products/skus', '/products/summaries', '/products/suggest', '/products/hot-keywords', '/knowledge/documents', '/knowledge/admin/migration-warnings', '/knowledge/admin/graph/failures', '/knowledge/graph/entity', '/knowledge/graph/search', '/knowledge/graph/recall', '/ai/sessions', '/ai/admin/bad-cases', '/ai/admin/bad-cases/fixtures', '/after-sales']
  if (arrays.includes(path) || /\/attributes$|\/bindings$|\/products\/\{spuKey\}\/documents$|\/refunds\/\{orderId\}$/.test(path)) return Array.isArray(value)
  const pages = ['/products', '/products/search', '/products/admin/spus', '/orders', '/orders/admin/orders', '/tickets', '/tickets/admin/tickets', '/after-sales/admin/after-sales', '/reviews/mine', '/reviews/admin/reviews', '/reviews/spu/{spuId}', '/auth/admin/users']
  if (pages.includes(path)) return Array.isArray(value?.records) && Number.isInteger(value.total) && value.total >= value.records.length
  if (path === '/orders/summary') return Array.isArray(value) && value.length > 0 && value.every((tab) => typeof tab.tab === 'string' && typeof tab.label === 'string' && Number.isInteger(tab.count))
  if (path === '/tickets/summary') return Number.isInteger(value?.total) && Number.isInteger(value.awaitingMe) && value.total >= 0
  if (path === '/favorites') return Array.isArray(value?.records) && Number.isInteger(value.total)
  if (path === '/favorites/check') return Array.isArray(value) && value.every(Number.isInteger)
  if (path === '/ai/eval/retrieval/report') return value?.status === 'COMPLETED' && Boolean(value.runId) && Array.isArray(value.cases) && value.cases.length === value.corpus?.cases && value.cases.length === value.overall?.caseCount
  if (path === '/ai/eval/grounding/report') return value?.live?.status === 'COMPLETED' && Boolean(value.live.runId) && Array.isArray(value.live.cases) && value.live.cases.length === value.live.totalCases
  if (path === '/ai/admin/knowledge/status') return typeof value?.running === 'boolean'
  if (path === '/ai/admin/knowledge/coverage') return typeof value?.available === 'boolean' && (!value.available || Array.isArray(value.uncoveredSpu) && value.totalSpu >= value.coveredSpu)
  if (path === '/knowledge/graph/stats') return value?.available === true && Number.isInteger(value.entities) && Number.isInteger(value.relations)
  if (path === '/knowledge/graph/interactions') return value?.available === true && Array.isArray(value.items) && value.items.every((item) => typeof item.found === 'boolean')
  if (path === '/knowledge/admin/graph/failures/stats') return Number.isInteger(value?.failed) && value.byStage && value.byReason
  if (path.startsWith('/knowledge/documents/')) return typeof value?.docNo === 'string' && typeof value.content === 'string' && Array.isArray(value.chunks)
  if (path.startsWith('/knowledge/chunks/')) return typeof value?.chunkId === 'string' && typeof value.content === 'string'
  if (path.startsWith('/products/')) return Number.isInteger(value?.id) && typeof value.name === 'string' && Array.isArray(value.skus)
  if (path.startsWith('/auth/admin/users/')) return Boolean(value?.user?.id) && typeof value.user.roleName === 'string'
  if (path.startsWith('/auth/addresses/')) return Number.isInteger(value?.id) && typeof value.receiverName === 'string'
  if (path.startsWith('/reviews/admin/reviews/')) return Number.isInteger(value?.review?.id) && typeof value.review.status === 'string'
  if (path === '/reviews/spu/{spuId}/statistics') return Number.isInteger(value?.total) || Number.isInteger(value?.reviewCount)
  if (path.startsWith('/orders/admin/orders/')) return Number.isInteger(value?.order?.id) && Array.isArray(value.statusLogs)
  if (path.endsWith('/logistics')) return Array.isArray(value?.steps)
  if (path.startsWith('/orders/')) return Number.isInteger(value?.id) && typeof value.status === 'string' && Array.isArray(value.items)
  if (path.startsWith('/tickets/')) return Number.isInteger(value?.ticket?.id) && Array.isArray(value.messages)
  if (path.startsWith('/after-sales/')) return Number.isInteger((value?.afterSale ?? value)?.id) && typeof (value?.afterSale ?? value)?.status === 'string'
  if (path.startsWith('/payments/')) return Number.isInteger(value?.orderId) && typeof value.status === 'string' && Number.isInteger(value.amount)
  if (path === '/knowledge/admin/graph/failures/{id}') return Number.isInteger(value?.id) && typeof value.batchId === 'string' && typeof value.reasonCode === 'string'
  return false
}
export function verifyExternalEvidence(key, proof, original) {
  if (!proof?.runId || original.runId !== proof.runId) return false
  if (proof.kind === 'AI_RESPONSE_CONTRACT_REPLAY') {
    const selected = original.calls?.filter((call) => proof.requestIds?.includes(call.requestId)) ?? []
    if (selected.length !== proof.requestIds?.length) return false
    if (key === 'GET /ai/sessions/{sessionId}/messages') {
      const positive = selected.find((call) => call.key === key && call.code === 200 && Array.isArray(call.response))
      const denied = selected.find((call) => call.key === key && call.code === 404 && call.role === 'other')
      const chat = original.calls?.find((call) => call.key === 'POST /ai/chat' && call.code === 200 && positive?.response.some((message) => message.id === call.response?.assistantMessageId))
      return Boolean(positive && denied && chat && positive.response.some((message) => message.id === chat.response.userMessageId && message.role === 'user')
        && positive.response.some((message) => message.id === chat.response.assistantMessageId && message.role === 'assistant') && denied.response == null)
    }
    if (key === 'POST /ai/sessions/{sessionId}/messages/{messageId}/dismiss-approval') {
      const dismiss = selected.find((call) => call.key === key && call.code === 200)
      const id = dismiss?.path.match(/\/messages\/([^/]+)\/dismiss-approval$/)?.[1]
      const history = selected.find((call) => call.key === 'GET /ai/sessions/{sessionId}/messages' && call.code === 200
        && call.response?.some((message) => message.id === id && message.response?.approvalStatus === 'DISMISSED'))
      const tickets = selected.filter((call) => call.key === 'GET /tickets' && call.code === 200 && Array.isArray(call.response?.records))
      return Boolean(dismiss && history && tickets.length === 2 && JSON.stringify(tickets[0].response.records) === JSON.stringify(tickets[1].response.records))
    }
    return false
  }
  if (proof.kind === 'CAPTURED_RESPONSE_REPLAY') {
    if (key !== 'GET /after-sales/preview' || !proof.requestIds?.length) return false
    return proof.requestIds.every((requestId) => original.calls?.some((call) => call.key === key && call.requestId === requestId && call.code === 200
      && call.response?.eligible === true && Number.isInteger(call.response.orderItemId) && Number.isInteger(call.response.maxRefundAmount)
      && Number.isInteger(call.response.itemSubtotal) && call.response.maxRefundAmount >= 0 && call.response.maxRefundAmount <= call.response.itemSubtotal))
  }
  if (proof.kind === 'VERIFY_SCRIPT_OUTPUT') {
    if (!proof.assertions?.length || !original.results?.length) return false
    return proof.assertions.every((assertion) => {
      const result = original.results.find((item) => item.script === assertion.script && item.ok && item.status === 0 && !item.timedOut)
      const output = result?.output?.replace(new RegExp(String.fromCharCode(27) + '\\[[0-9;]*m', 'g'), '') ?? ''
      const lines = output.split(/\r?\n/)
      return assertion.anchor && lines.some((line) => /\bPASS\b/.test(line) && line.includes(assertion.anchor))
        && !lines.some((line) => /\b(?:FAIL|SKIP)\b/.test(line) && line.includes(assertion.anchor))
    })
  }
  if (!proof.requestIds?.length) return false
  if (original.endpoints) {
    const endpoint = original.endpoints.find((item) => item.key === key && item.status === 'PASS')
    const owned = new Set(endpoint?.checks?.filter((check) => check.pass).flatMap((check) => check.requestIds) ?? [])
    return Boolean(endpoint?.checks?.length) && endpoint.checks.every((check) => check.pass && check.requestIds?.length)
      && proof.requestIds.every((id) => owned.has(id) && original.calls?.some((call) => call.requestId === id))
  }
  const cases = original.cases?.filter((item) => proof.caseIds?.includes(item.caseId)) ?? []
  if (!cases.length || !cases.every((item) => item.status === 'PASS' && item.assertions?.length && item.assertions.every((check) => check.pass))) return false
  if (key === 'POST /ai/chat') return proof.requestIds.every((id) => cases.some((item) => item.turns?.some((turn) => turn.requestId === id && turn.businessCode === 200 && turn.usageEvidence?.valid)))
  const normalized = (path) => path.split('?')[0].replace(/\{\{[^}]+\}\}|\{[^}]+\}/g, '{value}')
  const [method, path] = key.split(' ')
  return proof.requestIds.every((id) => cases.some((item) => item.apiEvidence?.some((api) => {
    const step = item.plan?.steps?.find((candidate) => candidate.id === api.stepId)
    if (api.requestId === id && api.businessCode === 200 && step?.kind === 'api') {
      return (step.method ?? 'GET') === method && normalized(step.path) === normalized(path)
    }
    if (step?.kind === 'feedback' && path === '/ai/sessions/{sessionId}/messages/{messageId}/bad-case') {
      return method === 'PUT' ? api.saved?.requestId === id && api.saved.businessCode === 200
        : method === 'GET' && api.revokedResponse?.requestId === id && api.revokedResponse.businessCode === 200
    }
    return false
  })))
}
export function redact(value) {
  if (Array.isArray(value)) return value.map(redact)
  if (value && typeof value === 'object') return Object.fromEntries(Object.entries(value).map(([key, item]) => [key,
    ['promptTokens', 'completionTokens', 'totalTokens', 'inputTokens', 'outputTokens'].includes(key) && Number.isSafeInteger(item) && item >= 0 ? item
      : /token|password|signature|authorization|receiverPhone|phone|receiverDetail|detailAddress/i.test(key) ? '[已脱敏]' : redact(item)]))
  return typeof value === 'string' ? value.replace(/(?<!\d)1\d{10}(?!\d)/g, '[手机号已脱敏]') : value
}

/** 调用与通过分开记录；200/夹具准备/403都不会自动把某接口记成已覆盖。 */
export class ApiEvidence {
  constructor(contracts, transport, runId = randomUUID()) {
    this.runId = runId
    this.transport = transport
    this.calls = []
    this.contracts = new Map(contracts.map((contract) => [endpointKey(contract.method, contract.path), {
      key: endpointKey(contract.method, contract.path), status: 'UNEXECUTED', checks: [],
      minimumFixture: contract.authority, kind: contract.kind,
    }]))
  }
  async call(key, path, options = {}) {
    assert(this.contracts.has(key), '请求不在唯一API矩阵中：' + key)
    const requestId = randomUUID()
    const started = Date.now()
    let result
    try { result = await this.transport(key.split(' ')[0], path, { ...options, requestId }) }
    catch (error) {
      this.calls.push({ key, path, role: options.role ?? 'buyer', requestId, durationMs: Date.now() - started, transportFailure: error.message, accepted: false })
      const contract = this.contracts.get(key)
      contract.status = 'FAIL'; contract.reason = '传输失败：' + error.message
      throw error
    }
    const call = { key, path, role: options.role ?? 'buyer', requestId, durationMs: Date.now() - started,
      httpStatus: result.httpStatus, code: result.code, response: redact(result.data), accepted: result.code === 200 }
    this.calls.push(call)
    const allowed = options.allowedCodes ?? [200]
    if (!allowed.includes(result.code)) {
      const contract = this.contracts.get(key)
      contract.status = 'FAIL'; contract.reason = '返回HTTP=' + result.httpStatus + ' 业务码=' + result.code + '：' + result.message
      throw new Error(key + ' ' + contract.reason)
    }
    return { ...result, requestId, call }
  }
  check(key, name, condition, authorityCalls, detail = {}) {
    const contract = this.contracts.get(key)
    assert(contract, '断言不在API矩阵中：' + key)
    const calls = authorityCalls.map((call) => call.call ?? call)
    assert(calls.length > 0 && calls.every((call) => this.calls.includes(call)), '证据必须来自本次真实调用')
    const check = { name, pass: Boolean(condition), requestIds: calls.map((call) => call.requestId), detail: redact(detail) }
    contract.checks.push(check)
    if (!condition) { contract.status = 'FAIL'; throw new Error(key + ' ' + name) }
    if (contract.status !== 'FAIL') contract.status = 'PASS'
  }
  block(key, reason) {
    const contract = this.contracts.get(key)
    if (contract && !['PASS', 'FAIL'].includes(contract.status)) { contract.status = 'BLOCKED'; contract.reason = reason }
  }
  manual(key, reason, evidence) {
    const contract = this.contracts.get(key)
    assert(contract, '人工证据接口不在矩阵中')
    if (contract.status === 'FAIL') { contract.externalEvidenceRejected = '本轮失败不能由旧或外部PASS覆盖'; return }
    if (evidence?.runId && evidence.status === 'PASS' && (evidence.requestIds?.length || evidence.kind === 'VERIFY_SCRIPT_OUTPUT') && evidence.path && evidence.verifiedSource === true) {
      contract.status = 'PASS'; contract.externalEvidence = evidence
    } else { contract.status = 'MANUAL_PENDING'; contract.reason = reason }
  }
  report() {
    const endpoints = [...this.contracts.values()]
    return { runId: this.runId, mode: 'LIVE_ISOLATED_PUBLIC_API', endpoints, calls: this.calls,
      summary: Object.fromEntries(['PASS', 'FAIL', 'BLOCKED', 'MANUAL_PENDING', 'UNEXECUTED'].map((status) => [status, endpoints.filter((endpoint) => endpoint.status === status).length])) }
  }
}

export async function pollAuthority(read, predicate, { timeoutMs = 30000, intervalMs = 250 } = {}) {
  const deadline = Date.now() + timeoutMs
  let latest
  do {
    latest = await read()
    if (predicate(latest.data)) return latest
    await new Promise((done) => setTimeout(done, intervalMs))
  } while (Date.now() < deadline)
  throw new Error('权威状态轮询超时，最终摘要=' + JSON.stringify(redact(latest?.data)))
}
