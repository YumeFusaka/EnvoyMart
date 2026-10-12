/** AI公开契约真跑：两个模型请求，其余操作使用同轮真实消息/签名，不碰存量会话。 */
import { randomUUID, createHash } from 'node:crypto'
import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { ApiEvidence, assert, rows, redact, verifyExternalEvidence } from './lib/public-api-evidence.mjs'

const ROOT = fileURLToPath(new URL('../', import.meta.url))
const args = process.argv.slice(2)
const compare = args.find((arg) => arg.startsWith('--compare='))?.slice(10)
assert(args.every((arg) => arg === '--capture' || arg.startsWith('--compare=')), '仅支持--capture或--compare=ignored报告')
assert(!(args.includes('--capture') && compare), 'capture与compare不能同时运行')
if (compare) {
  const path = resolve(ROOT, compare)
  assert(path.startsWith(resolve(ROOT, '.evidence') + '\\') || path.startsWith(resolve(ROOT, '.evidence') + '/'), 'compare仅允许忽略证据目录')
  const report = JSON.parse(await readFile(path, 'utf8'))
  assert(report.mode === 'LIVE_ISOLATED_AI_PUBLIC_API' && report.modelRequests === 2 && report.endpoints.length === 14, '必须完整真实两模型捕获，不能用mock总数')
  let pass = 0
  for (const endpoint of report.endpoints) {
    const corrected = endpoint.key === 'GET /ai/sessions/{sessionId}/messages' || endpoint.key.includes('/dismiss-approval')
    const proof = corrected ? { kind: 'AI_RESPONSE_CONTRACT_REPLAY', runId: report.runId,
      requestIds: endpoint.key.includes('/dismiss-approval') ? endpoint.checks[0]?.requestIds : [report.calls.find((call) => call.key === endpoint.key && call.code === 200)?.requestId, report.calls.find((call) => call.key === endpoint.key && call.role === 'other' && call.code === 404)?.requestId] }
      : { runId: report.runId, requestIds: [...new Set(endpoint.checks.flatMap((check) => check.requestIds))] }
    assert(verifyExternalEvidence(endpoint.key, proof, report), '原始捕获契约没有兑现：' + endpoint.key)
    pass += 1
  }
  console.log('真实AI API原捕获比较：' + pass + '/14 PASS；模型调用0次；原报告=' + path)
  process.exit(0)
}
if (!args.includes('--capture')) { console.log('BLOCKED 缺阶段：使用--capture人工两模型入口或--compare=原报告；默认不调用模型'); process.exit(2) }
const GW = process.env.VERIFY_GW ?? 'http://127.0.0.1:8080'
assert(['localhost', '127.0.0.1'].includes(new URL(GW).hostname), '只允许本地网关')
const runId = randomUUID()
const prefix = 't1ai_' + runId.slice(0, 8)
const matrix = JSON.parse(await readFile(new URL('./fixtures/platform-feature-matrix.json', import.meta.url), 'utf8'))
const targets = [
  'POST /ai/chat', 'POST /ai/chat/stream', 'GET /ai/admin/bad-cases/{id}', 'POST /ai/admin/bad-cases/{id}/review',
  'POST /ai/admin/bad-cases/{id}/test-case', 'DELETE /ai/admin/bad-cases/{id}/test-case', 'POST /ai/sessions/{sessionId}/approvals/recover',
  'GET /ai/sessions/{sessionId}/messages', 'POST /ai/sessions/{sessionId}/messages/{messageId}/dismiss-approval',
  'DELETE /ai/sessions/{sessionId}', 'PUT /ai/sessions/{sessionId}/messages/{messageId}/bad-case',
  'GET /ai/sessions/{sessionId}/messages/{messageId}/bad-case', 'GET /ai/sessions/{sessionId}/bad-cases', 'DELETE /ai/sessions/{sessionId}/messages/{messageId}/bad-case',
]
const tokens = {}
const sessions = { chat: prefix + '_chat', stream: prefix + '_stream' }
const state = {}
const startedAt = new Date().toISOString()
const sourceVersion = createHash('sha256').update(await readFile(new URL(import.meta.url))).digest('hex')
const output = resolve(ROOT, '.evidence', 'ai-public-api-' + runId + '.json')
let lastRequest = 0
let modelRequests = 0
const evidence = new ApiEvidence(matrix.apiContracts, async (method, path, options) => {
  const delay = 1100 - (Date.now() - lastRequest)
  if (path.startsWith('/ai/') && delay > 0) await new Promise((done) => setTimeout(done, delay))
  lastRequest = Date.now()
  if (path === '/ai/chat' || path === '/ai/chat/stream') {
    if (!options.body?.approvalToken) { modelRequests += 1; assert(modelRequests <= 2, '只允许一次chat和一次stream模型入口') }
  }
  const response = await fetch(GW + path, { method, signal: AbortSignal.timeout(240000),
    headers: { 'Content-Type': 'application/json', 'X-Request-Id': options.requestId, ...(tokens[options.role ?? 'buyer'] ? { Authorization: 'Bearer ' + tokens[options.role ?? 'buyer'] } : {}) },
    ...(options.body === undefined ? {} : { body: JSON.stringify(options.body) }),
  })
  if (path === '/ai/chat/stream' && response.ok && response.headers.get('content-type')?.includes('text/event-stream')) {
    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''; const events = []; let done
    try {
      while (!done) {
        const part = await reader.read()
        if (part.done) break
        buffer += decoder.decode(part.value, { stream: true }).replaceAll('\r\n', '\n')
        assert(buffer.length < 2_000_000, 'SSE缓存超过契约界限')
        let at
        while ((at = buffer.indexOf('\n\n')) >= 0) {
          const frame = buffer.slice(0, at); buffer = buffer.slice(at + 2)
          const event = frame.match(/^event:\s*(.+)$/m)?.[1]
          const data = frame.split('\n').filter((line) => line.startsWith('data:')).map((line) => line.slice(5).trimStart()).join('\n')
          if (!event) continue
          events.push(event)
          if (event === 'error') throw new Error('SSE真实错误：' + data)
          if (event === 'done') done = JSON.parse(data)
        }
      }
      assert(done, 'SSE没有权威done事件')
      state.streamEvents = events
      return { httpStatus: response.status, code: 200, data: done }
    } finally { await reader.cancel() }
  }
  const body = await response.json()
  return { httpStatus: response.status, code: body.code ?? response.status, data: body.data, message: body.msg }
}, runId)
const call = (key, path, body, role = 'buyer', allowedCodes) => evidence.call(key, path, { body, role, allowedCodes })
const read = (key, path, role = 'buyer') => call(key, path, undefined, role)
const check = (key, name, condition, calls, detail) => evidence.check(key, name, condition, calls, detail)
async function save(finished = false) {
  const report = evidence.report()
  report.endpoints = report.endpoints.filter((entry) => targets.includes(entry.key))
  report.summary = Object.fromEntries(['PASS', 'FAIL', 'BLOCKED', 'UNEXECUTED'].map((status) => [status, report.endpoints.filter((entry) => entry.status === status).length]))
  await mkdir(resolve(ROOT, '.evidence'), { recursive: true })
  await writeFile(output, JSON.stringify({ ...report, mode: 'LIVE_ISOLATED_AI_PUBLIC_API', startedAt, finishedAt: finished ? new Date().toISOString() : undefined,
    sourceVersion, targets, modelRequests, sessions, state: redact(state), limits: ['两次真实模型入口，不自动重试模型', '原审批恢复/确认不重新问模型', '用户和会话均新建；只删除自有会话', '调用200不自动覆盖，所有PASS有权威断言'] }, null, 2) + '\n')
  return report
}
async function section(name, execute) {
  try { await execute(); console.log('PASS ' + name) }
  catch (error) { console.log('FAIL ' + name + ' ' + error.message); state.failures ??= []; state.failures.push({ name, reason: error.message }) }
  await save()
}
const historyKey = 'GET /ai/sessions/{sessionId}/messages'
const recoverKey = 'POST /ai/sessions/{sessionId}/approvals/recover'
const feedbackKey = (method) => method + ' /ai/sessions/{sessionId}/messages/{messageId}/bad-case'

await section('独立身份', async () => {
  for (const role of ['buyer', 'other']) {
    const password = 'T1' + randomUUID().replaceAll('-', '').slice(0, 16)
    const account = await call('POST /auth/register', '/auth/register', { username: prefix + (role === 'buyer' ? 'a' : 'b'), password, nickname: 'AI公开接口验收' }, 'anonymous')
    assert(account.data?.token && account.data.user?.id, '注册未返回权威身份')
    tokens[role] = account.data.token; state[role + 'Id'] = account.data.user.id
  }
  const admin = await call('POST /auth/login', '/auth/login', { username: process.env.VERIFY_ADMIN_USERNAME ?? 'admin', password: process.env.VERIFY_ADMIN_PASSWORD ?? '123456' }, 'anonymous')
  assert(admin.data?.user?.roleName === 'ADMIN', '管理员角色不符')
  tokens.admin = admin.data.token
})
await section('真实chat待审批与权威消息', async () => {
  const response = await call('POST /ai/chat', '/ai/chat', { sessionId: sessions.chat, message: '请提交客服工单：分类OTHER，标题“独立接口审批验收”，内容“本轮只验证平台工单接口，请客服说明如何查看记录”。必须先生成待审批操作，等我明确确认后再执行。' })
  state.chat = response.data
  assert(response.data.approvalToken && response.data.pendingActionDetails?.some((action) => action.tool === 'ticket_create'), '真实规划没有生成预期工单审批')
  const history = await read(historyKey, '/ai/sessions/' + sessions.chat + '/messages')
  const messages = rows(history.data)
  check('POST /ai/chat', '真实模型有用量且权威消息编号落历史', response.data.usage?.totalTokens > 0 && messages.some((message) => message.id === response.data.assistantMessageId && message.response?.approvalToken === response.data.approvalToken) && messages.some((message) => message.id === response.data.userMessageId), [response, history])
  check(historyKey, '完整会话双消息与本轮权威ID一致', messages.length === 2 && messages[0].id === response.data.userMessageId && messages[1].id === response.data.assistantMessageId, [response, history])
  const denied = await call(historyKey, '/ai/sessions/' + sessions.chat + '/messages', undefined, 'other', [404])
  check(historyKey, '跨用户会话读取明确404且不泄露消息', denied.code === 404 && denied.data == null, [denied])
})
await section('真实stream待审批与原参数执行恢复', async () => {
  const stream = await call('POST /ai/chat/stream', '/ai/chat/stream', { sessionId: sessions.stream, message: '请提交客服工单：分类OTHER，标题“真实流式接口验收”，内容“本轮仅验证流式工单审批接口”。请先产生待确认操作，等我明确确认后再执行。' })
  state.stream = stream.data
  assert(stream.data.approvalToken && stream.data.pendingActionDetails?.some((action) => action.tool === 'ticket_create'), 'stream未生成真实待审批工单')
  const history = await read(historyKey, '/ai/sessions/' + sessions.stream + '/messages')
  check('POST /ai/chat/stream', '真实SSE done与历史权威ID一致且有模型用量', state.streamEvents.includes('done') && stream.data.usage?.totalTokens > 0 && rows(history.data).some((message) => message.id === stream.data.assistantMessageId), [stream, history], { events: state.streamEvents })
  const before = await read('GET /tickets', '/tickets?page=0&size=100')
  const confirmed = await call('POST /ai/chat', '/ai/chat', { sessionId: sessions.stream, message: '确认执行', approvalToken: stream.data.approvalToken })
  const after = await read('GET /tickets', '/tickets?page=0&size=100')
  const recover = await call(recoverKey, '/ai/sessions/' + sessions.stream + '/approvals/recover', { approvalToken: stream.data.approvalToken, retry: false })
  const afterRecover = await read('GET /tickets', '/tickets?page=0&size=100')
  const identity = await read(historyKey, '/ai/sessions/' + sessions.stream + '/messages')
  check(recoverKey, '原签名只读恢复首次COMMITTED回执且没有二次工单', rows(after.data).length === rows(before.data).length + 1 && rows(afterRecover.data).length === rows(after.data).length && recover.data.approvalStatus === 'CONFIRMED' && recover.data.toolCalls.some((tool) => tool.tool === 'ticket_create' && tool.operationState === 'COMMITTED' && tool.receiptReplayed) && rows(identity.data).some((message) => message.id === stream.data.assistantMessageId), [confirmed, after, recover, afterRecover, identity])
  const cross = await call(recoverKey, '/ai/sessions/' + sessions.stream + '/approvals/recover', { approvalToken: stream.data.approvalToken, retry: true }, 'other', [400, 403, 409])
  check(recoverKey, '跨用户不能恢复/执行原签名', cross.code !== 200, [cross])
})
await section('待审批取消无副作用', async () => {
  assert(state.chat?.approvalToken, 'chat真实待审批前提缺失')
  const before = await read('GET /tickets', '/tickets?page=0&size=100')
  const path = '/ai/sessions/' + sessions.chat + '/messages/' + state.chat.assistantMessageId + '/dismiss-approval'
  const dismissed = await call('POST /ai/sessions/{sessionId}/messages/{messageId}/dismiss-approval', path)
  const history = await read(historyKey, '/ai/sessions/' + sessions.chat + '/messages')
  const after = await read('GET /tickets', '/tickets?page=0&size=100')
  check('POST /ai/sessions/{sessionId}/messages/{messageId}/dismiss-approval', '原消息DISMISSED持久化且不创建工单', rows(history.data).find((message) => message.id === state.chat.assistantMessageId)?.response?.approvalStatus === 'DISMISSED' && rows(after.data).length === rows(before.data).length, [dismissed, history, before, after])
})
await section('反馈完整消息保存撤销审核与追加集', async () => {
  assert(state.chat?.assistantMessageId, '真实消息前提缺失')
  const session = sessions.chat
  const path = '/ai/sessions/' + session + '/messages/' + state.chat.assistantMessageId + '/bad-case'
  const history = await read(historyKey, '/ai/sessions/' + session + '/messages')
  const selectedMessageIds = rows(history.data).map((message) => message.id)
  const payload = { reasonCodes: ['OTHER'], selectedMessageIds, comment: '独立API完整会话反馈' }
  const saved = await call(feedbackKey('PUT'), path, payload)
  state.badCaseId = saved.data.badCaseId
  const restored = await read(feedbackKey('GET'), path)
  check(feedbackKey('PUT'), '反馈绑定权威助手和完整选择且可读回', restored.data.badCaseId === saved.data.badCaseId && restored.data.assistantMessageId === state.chat.assistantMessageId && JSON.stringify(restored.data.selectedMessageIds) === JSON.stringify(selectedMessageIds) && restored.data.status === 'ACTIVE', [saved, restored, history])
  check(feedbackKey('GET'), '单条反馈权威身份可刷新', restored.data.badCaseId === saved.data.badCaseId && restored.data.userId === state.buyerId, [restored])
  const revoked = await call(feedbackKey('DELETE'), path)
  const revokedRead = await read(feedbackKey('GET'), path)
  const sessionFeedback = await read('GET /ai/sessions/{sessionId}/bad-cases', '/ai/sessions/' + session + '/bad-cases')
  check(feedbackKey('DELETE'), '撤销保留REVOKED记录和稳定ID', revokedRead.data.status === 'REVOKED' && revokedRead.data.badCaseId === saved.data.badCaseId, [revoked, revokedRead])
  check('GET /ai/sessions/{sessionId}/bad-cases', '会话反馈与单条REVOKED一致', rows(sessionFeedback.data).some((feedback) => feedback.badCaseId === saved.data.badCaseId && feedback.status === 'REVOKED'), [revokedRead, sessionFeedback])
  await call(feedbackKey('PUT'), path, payload)
  const adminPath = '/ai/admin/bad-cases/' + saved.data.badCaseId
  const detail = await read('GET /ai/admin/bad-cases/{id}', adminPath, 'admin')
  check('GET /ai/admin/bad-cases/{id}', '管理员详情原消息与反馈一致', detail.data.badCaseId === saved.data.badCaseId && detail.data.assistantMessageId === state.chat.assistantMessageId, [detail, saved])
  const review = await call('POST /ai/admin/bad-cases/{id}/review', adminPath + '/review?status=REVIEWED', undefined, 'admin')
  const reviewed = await read('GET /ai/admin/bad-cases/{id}', adminPath, 'admin')
  check('POST /ai/admin/bad-cases/{id}/review', '审核后稳定记录为REVIEWED且有操作人', reviewed.data.status === 'REVIEWED' && Boolean(reviewed.data.reviewedBy), [review, reviewed])
  const added = await call('POST /ai/admin/bad-cases/{id}/test-case', adminPath + '/test-case', { evalKind: 'ANSWERABLE', expectRefuse: false, mustMention: ['审批'], expectedTools: ['ticket_create'], annotation: '真实模型独立验收样本；独立追加集，不进入基础分母' }, 'admin')
  const fixtures = await read('GET /ai/admin/bad-cases/fixtures', '/ai/admin/bad-cases/fixtures?limit=1000', 'admin')
  const fixture = rows(fixtures.data).find((value) => value.sourceBadCaseId === saved.data.badCaseId)
  check('POST /ai/admin/bad-cases/{id}/test-case', '标注完整进入独立追加集且来源一致', added.data.status === 'IN_TEST_SET' && fixture?.evalKind === 'ANSWERABLE' && fixture.expectedTools.includes('ticket_create'), [added, fixtures])
  const removed = await call('DELETE /ai/admin/bad-cases/{id}/test-case', adminPath + '/test-case', undefined, 'admin')
  const fixturesAfter = await read('GET /ai/admin/bad-cases/fixtures', '/ai/admin/bad-cases/fixtures?limit=1000', 'admin')
  const detailAfter = await read('GET /ai/admin/bad-cases/{id}', adminPath, 'admin')
  check('DELETE /ai/admin/bad-cases/{id}/test-case', '移出仅撤销追加集且原反馈仍REVIEWED', !rows(fixturesAfter.data).some((value) => value.sourceBadCaseId === saved.data.badCaseId) && detailAfter.data.status === 'REVIEWED', [removed, fixturesAfter, detailAfter])
})
await section('只删除自有会话', async () => {
  const own = await read('GET /ai/sessions', '/ai/sessions')
  for (const session of Object.values(sessions)) {
    if (!rows(own.data).some((entry) => entry.sessionId === session)) continue
    const denied = await call('DELETE /ai/sessions/{sessionId}', '/ai/sessions/' + session, undefined, 'other', [404])
    const removed = await call('DELETE /ai/sessions/{sessionId}', '/ai/sessions/' + session)
    const after = await read('GET /ai/sessions', '/ai/sessions')
    const missing = await call(historyKey, '/ai/sessions/' + session + '/messages', undefined, 'buyer', [404])
    check('DELETE /ai/sessions/{sessionId}', '越权删404，自有删后列表与历史均不存在', denied.code === 404 && !rows(after.data).some((entry) => entry.sessionId === session) && missing.code === 404, [denied, removed, after, missing])
  }
})
for (const key of targets) if (evidence.contracts.get(key).status === 'UNEXECUTED') evidence.block(key, '真实两轮前提/审批/反馈后续尚未兑现，不能补造消息或令牌')
const report = await save(true)
console.log(JSON.stringify(report.summary) + '\n证据=' + output)
if (report.endpoints.some((entry) => entry.status !== 'PASS') || state.failures?.length) process.exitCode = 1
