import test from 'node:test'
import nodeAssert from 'node:assert/strict'
import { ApiEvidence, rows, redact, pollAuthority, pngFixture, validateReadResponse, verifyExternalEvidence, verifyPageWriteEvidence, verifyOwnedDocumentFixture, parseSseDone } from './public-api-evidence.mjs'
import { inflateSync } from 'node:zlib'

const contracts = [{ method: 'POST', path: '/items', authority: 'GET /items' }, { method: 'GET', path: '/items', authority: 'GET /items' }]
test('真实SSE UTF-8中文和服务端requestId保留，只有delta不冒充done成功', () => {
  const frame = Buffer.from('event: delta\r\ndata: {"content":"请确认"}\r\n\r\nevent: done\r\ndata: {"requestId":"server-real-id","reply":"请确认执行","pendingActionDetails":[{"arguments":{"title":"页面工单"}}]}\r\n\r\n', 'utf8')
  const done = parseSseDone(frame)
  nodeAssert.equal(done.requestId, 'server-real-id')
  nodeAssert.equal(done.reply, '请确认执行')
  nodeAssert.equal(done.pendingActionDetails[0].arguments.title, '页面工单')
  nodeAssert.equal(parseSseDone(Buffer.from('event: delta\ndata: {}\n\n')), null)
})
test('公开读取编号覆盖不能改写自有文档身份；原POST、标题和停用状态缺一不可', () => {
  const fixture = { ownedDocNo: 'KB-0055', readDocNo: 'KB-0001', ownedDocTitle: '独立验收', ownedDocCreationRequestId: 'created' }
  const calls = [{ key: 'POST /knowledge/admin/documents', requestId: 'created', code: 200, response: 'KB-0055' }]
  nodeAssert.equal(verifyOwnedDocumentFixture({ docNo: 'KB-0055', title: '独立验收', status: 0 }, fixture, calls), true)
  nodeAssert.equal(verifyOwnedDocumentFixture({ docNo: 'KB-0001', title: '平台政策', status: 1 }, fixture, calls), false)
  nodeAssert.equal(verifyOwnedDocumentFixture({ docNo: 'KB-0055', title: '他人改名', status: 0 }, fixture, calls), false)
  nodeAssert.equal(verifyOwnedDocumentFixture({ docNo: 'KB-0055', title: '独立验收', status: 1 }, fixture, calls), false)
  nodeAssert.equal(verifyOwnedDocumentFixture({ docNo: 'KB-0055', title: '独立验收', status: 0 }, fixture, []), false)
})
test('成功响应与准备调用不能自动计覆盖；写后权威读回才明确通过', async () => {
  const evidence = new ApiEvidence(contracts, async () => ({ code: 200, httpStatus: 200, data: [{ id: 1 }] }), 'run-test')
  const write = await evidence.call('POST /items', '/items')
  nodeAssert.equal(evidence.report().summary.PASS, 0)
  const read = await evidence.call('GET /items', '/items')
  evidence.check('POST /items', '只新增一条', rows(read.data).length === 1, [write, read])
  nodeAssert.equal(evidence.report().summary.PASS, 1)
  nodeAssert.throws(() => evidence.check('GET /items', '外部伪证据', true, [{ requestId: 'fake' }]), /本次真实/)
})
test('403仅证明拒绝分支；业务错误不能降级空数组或计通过', async () => {
  const evidence = new ApiEvidence(contracts, async () => ({ code: 403, httpStatus: 403, data: null }))
  await evidence.call('POST /items', '/items', { allowedCodes: [403] })
  nodeAssert.equal(evidence.report().summary.PASS, 0)
  await nodeAssert.rejects(evidence.call('GET /items', '/items'), /业务码=403/)
  nodeAssert.throws(() => rows(null), /不能把错误/)
})
test('失败断言保留FAIL，人工样本没有run与请求证明保留待验', async () => {
  const evidence = new ApiEvidence(contracts, async () => ({ code: 200, httpStatus: 200, data: [] }))
  const read = await evidence.call('GET /items', '/items')
  nodeAssert.throws(() => evidence.check('GET /items', '应非空', false, [read]), /应非空/)
  evidence.manual('POST /items', '需要真实专项', { runId: '只有runId' })
  nodeAssert.equal(evidence.report().summary.FAIL, 1)
  nodeAssert.equal(evidence.report().summary.MANUAL_PENDING, 1)
  evidence.manual('GET /items', '旧样本', { runId: '旧run', status: 'PASS', requestIds: ['old'], path: '.evidence/old.json' })
  nodeAssert.equal(evidence.report().summary.FAIL, 1)
})
test('输出不保留口令、审批令牌与手机号；轮询保存最终权威响应', async () => {
  nodeAssert.deepEqual(redact({ password: 'private', approvalToken: 'private', receiverPhone: '13800000000' }), { password: '[已脱敏]', approvalToken: '[已脱敏]', receiverPhone: '[已脱敏]' })
  nodeAssert.deepEqual(redact({ promptTokens: 12, completionTokens: 0, totalTokens: 12, inputTokens: 12, outputTokens: 0, accessToken: 'secret', refreshTokens: ['secret'] }), { promptTokens: 12, completionTokens: 0, totalTokens: 12, inputTokens: 12, outputTokens: 0, accessToken: '[已脱敏]', refreshTokens: '[已脱敏]' })
  nodeAssert.deepEqual(redact({ promptTokens: 'secret', completionTokens: -1, totalTokens: Infinity }), { promptTokens: '[已脱敏]', completionTokens: '[已脱敏]', totalTokens: '[已脱敏]' })
  let count = 0
  const result = await pollAuthority(async () => ({ data: { state: ++count === 2 ? 'PAID' : 'CREATED' } }), (data) => data.state === 'PAID', { timeoutMs: 20, intervalMs: 1 })
  nodeAssert.equal(result.data.state, 'PAID')
})

test('上传夹具是合法一像素PNG且只含本地生成像素', () => {
  const image = pngFixture()
  nodeAssert.deepEqual([...image.subarray(0, 8)], [137, 80, 78, 71, 13, 10, 26, 10])
  nodeAssert.equal(image.readUInt32BE(16), 1)
  nodeAssert.equal(image.readUInt32BE(20), 1)
  const length = image.readUInt32BE(33)
  nodeAssert.deepEqual([...inflateSync(image.subarray(41, 41 + length))], [0, 30, 120, 90, 255])
})

test('公开读逐类型契约拒绝任意object、缺字段与不一致分母', () => {
  nodeAssert.equal(validateReadResponse('/products', {}), false)
  nodeAssert.equal(validateReadResponse('/products', { records: [], total: 0 }), true)
  nodeAssert.equal(validateReadResponse('/products', { records: [{ id: 1 }], total: 0 }), false)
  nodeAssert.equal(validateReadResponse('/reviews/admin/reviews/{id}', { review: { id: 1, status: 'PUBLISHED' } }), true)
  nodeAssert.equal(validateReadResponse('/orders/{id}', { id: 1, status: 'CREATED' }), false)
  nodeAssert.equal(validateReadResponse('/ai/eval/retrieval/report', { status: 'COMPLETED', runId: 'r', cases: [], totalCases: 70 }), false)
  nodeAssert.equal(validateReadResponse('/ai/eval/retrieval/report', { status: 'COMPLETED', runId: 'r', cases: [], corpus: { cases: 0 }, overall: { caseCount: 0 } }), true)
  nodeAssert.equal(validateReadResponse('/favorites/check', []), true)
  nodeAssert.equal(validateReadResponse('/favorites/check', {}), false)
  nodeAssert.equal(validateReadResponse('/unknown', {}), false)
})

test('API报告状态集合阻止把未执行、阻塞或人工模型项计入通过', () => {
  const evidence = new ApiEvidence([{ method: 'GET', path: '/x', authority: 'GET /x' }], async () => ({ code: 200, httpStatus: 200, data: [] }))
  evidence.block('GET /x', '前提不成立')
  nodeAssert.deepEqual(evidence.report().summary, { PASS: 0, FAIL: 0, BLOCKED: 1, MANUAL_PENDING: 0, UNEXECUTED: 0 })
  evidence.manual('GET /x', '需外部证据', { runId: 'r', status: 'COMPLETED', requestIds: ['q'], path: 'x' })
  nodeAssert.equal(evidence.report().summary.MANUAL_PENDING, 1)
})

test('外部证据必须同run同接口同请求的真实PASS，复杂样例FAIL和未选不能填分母', () => {
  const source = { runId: 'r', cases: [{ caseId: 'CA-1', status: 'PASS', assertions: [{ pass: true }], turns: [{ requestId: 'q', businessCode: 200, usageEvidence: { valid: true } }], plan: { steps: [{ id: 's', kind: 'api', method: 'POST', path: '/tickets/{{ticket.id}}/close' }] }, apiEvidence: [{ stepId: 's', requestId: 'close', businessCode: 200 }] }] }
  const proof = { runId: 'r', requestIds: ['q'], caseIds: ['CA-1'] }
  nodeAssert.equal(verifyExternalEvidence('POST /ai/chat', proof, source), true)
  nodeAssert.equal(verifyExternalEvidence('POST /ai/chat/stream', proof, source), false)
  nodeAssert.equal(verifyExternalEvidence('POST /tickets/{id}/close', { ...proof, requestIds: ['close'] }, source), true)
  source.cases[0].status = 'FAIL'
  nodeAssert.equal(verifyExternalEvidence('POST /ai/chat', proof, source), false)
  nodeAssert.equal(verifyExternalEvidence('POST /ai/chat', { ...proof, runId: 'fake' }, source), false)
})

test('同run中的其他接口请求不能借来证明目标endpointPASS', () => {
  const report = { runId: 'r', endpoints: [{ key: 'POST /x', status: 'PASS', checks: [{ pass: true, requestIds: ['own'] }] }], calls: [{ requestId: 'own' }, { requestId: 'other' }] }
  nodeAssert.equal(verifyExternalEvidence('POST /x', { runId: 'r', requestIds: ['own'] }, report), true)
  nodeAssert.equal(verifyExternalEvidence('POST /x', { runId: 'r', requestIds: ['other'] }, report), false)
  report.endpoints[0].checks.push({ pass: false, requestIds: ['other'] })
  nodeAssert.equal(verifyExternalEvidence('POST /x', { runId: 'r', requestIds: ['own'] }, report), false)
})

test('页面比较逐点击验证回执，不容许一次成功掩盖失败点击或写前读取', () => {
  const result = { status: 'PASS', state: 'write', errors: [], clicks: [{ method: 'POST', requestId: 'click', httpStatus: 200, code: 200, capturedAt: '2026-10-11T10:00:00Z' }], authority: [{ method: 'GET', requestId: 'read', httpStatus: 200, code: 200, capturedAt: '2026-10-11T10:00:01Z' }] }
  nodeAssert.equal(verifyPageWriteEvidence(result), true)
  result.clicks.push({ method: 'POST', requestId: 'failed', httpStatus: 200, code: 500 })
  nodeAssert.equal(verifyPageWriteEvidence(result), false)
  result.clicks.pop()
  result.authority[0].capturedAt = '2026-10-11T09:59:59Z'
  nodeAssert.equal(verifyPageWriteEvidence(result), false)
  result.authority[0].capturedAt = '2026-10-11T10:00:01Z'
  result.authority[0].requestId = null
  nodeAssert.equal(verifyPageWriteEvidence(result), false)
})

test('已有脚本只能导入同次逐断言PASS，脚本ok总数与SKIP不得代替主写', () => {
  const proof = { runId: 'r', kind: 'VERIFY_SCRIPT_OUTPUT', assertions: [{ script: 'verify-example.mjs', anchor: '订单只新增一次' }] }
  const source = { runId: 'r', results: [{ script: 'verify-example.mjs', ok: true, status: 0, output: 'PASS 订单只新增一次\n12通过' }] }
  nodeAssert.equal(verifyExternalEvidence('POST /orders/checkout', proof, source), true)
  source.results[0].output = '12通过'
  nodeAssert.equal(verifyExternalEvidence('POST /orders/checkout', proof, source), false)
  source.results[0].output = 'SKIP 订单只新增一次'
  nodeAssert.equal(verifyExternalEvidence('POST /orders/checkout', proof, source), false)
})

test('只对原始真实售后preview捕获做确定性字段回放，不补造写操作PASS', () => {
  const proof = { runId: 'r', kind: 'CAPTURED_RESPONSE_REPLAY', requestIds: ['q'] }
  const source = { runId: 'r', calls: [{ key: 'GET /after-sales/preview', requestId: 'q', code: 200, response: { eligible: true, orderItemId: 8, maxRefundAmount: 1200, itemSubtotal: 1200 } }] }
  nodeAssert.equal(verifyExternalEvidence('GET /after-sales/preview', proof, source), true)
  nodeAssert.equal(verifyExternalEvidence('POST /after-sales', proof, source), false)
  source.calls[0].response.maxRefundAmount = 1201
  nodeAssert.equal(verifyExternalEvidence('GET /after-sales/preview', proof, source), false)
})

test('AI捕获契约回放区分消息DISMISSED与批次CANCELLED，负测404不能标产品失败', () => {
  const history = 'GET /ai/sessions/{sessionId}/messages'
  const dismiss = 'POST /ai/sessions/{sessionId}/messages/{messageId}/dismiss-approval'
  const source = { runId: 'r', calls: [
    { key: 'POST /ai/chat', code: 200, response: { userMessageId: 'u', assistantMessageId: 'a' } },
    { key: history, requestId: 'history', code: 200, response: [{ id: 'u', role: 'user' }, { id: 'a', role: 'assistant', response: { approvalStatus: 'DISMISSED' } }] },
    { key: history, requestId: 'denied', role: 'other', code: 404, response: null },
    { key: dismiss, requestId: 'dismiss', code: 200, path: '/ai/sessions/s/messages/a/dismiss-approval' },
    { key: 'GET /tickets', requestId: 'before', code: 200, response: { records: [{ id: 8 }] } },
    { key: 'GET /tickets', requestId: 'after', code: 200, response: { records: [{ id: 8 }] } },
  ] }
  nodeAssert.equal(verifyExternalEvidence(history, { runId: 'r', kind: 'AI_RESPONSE_CONTRACT_REPLAY', requestIds: ['history', 'denied'] }, source), true)
  const proof = { runId: 'r', kind: 'AI_RESPONSE_CONTRACT_REPLAY', requestIds: ['history', 'dismiss', 'before', 'after'] }
  nodeAssert.equal(verifyExternalEvidence(dismiss, proof, source), true)
  source.calls[1].response[1].response.approvalStatus = 'CANCELLED'
  nodeAssert.equal(verifyExternalEvidence(dismiss, proof, source), false)
})
