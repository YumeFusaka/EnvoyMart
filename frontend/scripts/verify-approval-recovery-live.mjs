/**
 * 已提交后丢响应的审批恢复验收入口。
 *
 * 真实执行必须由主代理按窗口传入固定的独立账号、代理端口和重启证据；脚本不读 .env、不启动服务、不自动填 secret。
 * `--dry-run` 只打印两类独立实验和证据门。`--self-test` 只跑本地代理 helper。
 * 真实 capture/compare 的业务步骤与 PID/Redis 证据由主代理在服务窗口就绪后执行。
 */
import { readFile, writeFile, mkdir, readdir } from 'node:fs/promises'
import { resolve } from 'node:path'
import { randomUUID, randomBytes } from 'node:crypto'
import { spawnSync } from 'node:child_process'
import { createLostResponseProxy, readApprovalSummary, fingerprint, assertOriginalRecovery, comparisonReport } from './lib/approval-recovery-live.mjs'
import { close, listen } from './lib/complex-agent-controls.mjs'
import { digest, assertReceipt, parameterHash } from './lib/write-operation-receipts.mjs'
import { fileURLToPath } from 'node:url'

const args = process.argv.slice(2)
if (args.length === 0) {
  console.log(JSON.stringify({ status: 'BLOCKED', reason: '真实审批丢响应/重启验收需要显式prepare/capture/compare及主代理控制证据，默认不触发模型或服务控制' }))
  process.exit(2)
}
if (args.includes('--help')) {
  console.log('node scripts/verify-approval-recovery-live.mjs --dry-run\nnode scripts/verify-approval-recovery-live.mjs --self-test\nnode scripts/verify-approval-recovery-live.mjs --prepare\nnode scripts/verify-approval-recovery-live.mjs --capture --from=<prepared.json> --tool=coupon_receive\nnode scripts/verify-approval-recovery-live.mjs --capture --from=<prepared.json> --tool=ticket_create --downstream --activate-evidence=<json> --restore-evidence=<json>\nnode scripts/verify-approval-recovery-live.mjs --capture --from=<prepared.json> --tool=coupon_receive --promotion-downstream --activate-evidence=<json> --restore-evidence=<json>\nnode scripts/verify-approval-recovery-live.mjs --compare=<capture.json> --restart-evidence=<controls.json>\nprepare创建隔离账号并每项真实模型签发一次；capture/compare仅原token恢复，不模型/重启。order持有19003，promotion持有19007，等待主代理ready/恢复证据；不更改服务配置。compare另存报告和原capture SHA，不覆盖原报告。')
  process.exit(0)
}
const has = (prefix) => args.find((arg) => arg.startsWith(prefix))?.slice(prefix.length)
if (args.includes('--dry-run')) {
  console.log(JSON.stringify({ mode: 'DRY_RUN', experiments: [
    { name: 'ORDER_COMMITTED_UNKNOWN', route: 'AI -> order 19003', evidence: ['完整上游200+receipt headers已收到', '客户端响应丢失', '原op receipt query blocked/released', 'ticket首条消息与首次JSON digest'] },
    { name: 'PROMOTION_COMMITTED_UNKNOWN', route: 'AI -> promotion 19007', evidence: ['独立user+原op+couponId', '完整首次receipt headers/hash', '该账号唯一userCoupon权威读取', 'AI内部UNKNOWN', '原op查询/重试阻断后恢复'] },
    { name: 'CLIENT_COMMITTED_UNKNOWN', route: 'gateway -> AI client response proxy', evidence: ['AI真实响应完整收到', '客户端响应丢失', '只读/继续原token恢复', 'AI内部0恢复模型调用'] },
  ], blockedWithout: ['独立账号与原审批token', '主代理临时19003或19007及PID/ready窗口', 'order/promotion/ai真实重启证据'], modelCalls: 0, restart: false }))
  process.exit(0)
}
if (args.includes('--self-test')) {
  const result = spawnSync(process.execPath, [resolve(import.meta.dirname, 'lib/approval-recovery-live.test.mjs')], { stdio: 'inherit' })
  process.exit(result.status ?? 1)
}
const compare = has('--compare=')
const capture = args.includes('--capture')
const prepare = args.includes('--prepare')
const resumePrepared = has('--from=')
if (Number(capture) + Number(Boolean(compare)) + Number(prepare) !== 1) throw new Error('必须且只能选择 --prepare、--capture 或 --compare')
if (args.some((arg) => !['--prepare', '--capture', '--downstream', '--promotion-downstream'].includes(arg) && !['--from=', '--compare=', '--restart-evidence=', '--tool=', '--activate-evidence=', '--restore-evidence='].some((prefix) => arg.startsWith(prefix)))) throw new Error('参数不在固定验收白名单')
if (args.includes('--downstream') && args.includes('--promotion-downstream')) throw new Error('两个固定下游窗口必须独立实验，不能同时选择')
if (capture && !resumePrepared) throw Object.assign(new Error('capture必须复用 --from 原prepare证据，不能重复模型签发'), { blocked: true })
if (capture && (args.includes('--downstream') || args.includes('--promotion-downstream'))
  && (!has('--activate-evidence=') || !has('--restore-evidence='))) throw Object.assign(new Error('固定下游窗口必须同时提供主代理激活与恢复marker路径'), { blocked: true })
const gateway = process.env.VERIFY_GW ?? 'http://127.0.0.1:8080'
const gatewayUrl = new URL(gateway)
if (!['127.0.0.1', 'localhost'].includes(gatewayUrl.hostname) || gatewayUrl.protocol !== 'http:') throw new Error('真实验收仅允许本机HTTP网关')
const directory = fileURLToPath(new URL('../.evidence/', import.meta.url))
const logDirectory = fileURLToPath(new URL('../../logs/logs-local/', import.meta.url))
const sourcePath = compare ?? resumePrepared
const sourceBytes = sourcePath ? await readFile(resolve(sourcePath), 'utf8') : null
const snapshot = sourceBytes ? JSON.parse(sourceBytes) : { version: 'approval-recovery-live-v1', runId: randomUUID(), startedAt: new Date().toISOString(), actions: [], checks: [], calls: [], restartStatus: 'NOT_EXECUTED' }
if (compare && (snapshot.version !== 'approval-recovery-live-v1' || !['CAPTURED_WAITING_COMPARE', 'PASS'].includes(snapshot.status)
  || snapshot.checks.some((item) => !item.pass))) throw new Error('capture不完整或已有失败，不能比较成PASS')
if (resumePrepared && (snapshot.version !== 'approval-recovery-live-v1' || !['PREPARED', 'CAPTURED_PARTIAL'].includes(snapshot.status))) throw new Error('原签名prepare证据不完整')
const comparison = compare ? comparisonReport(compare, sourceBytes, snapshot, directory) : null
const captureId = capture ? randomUUID() : null
const publicPath = comparison ? comparison.publicPath : capture ? resolve(directory, 'approval-recovery-capture-' + captureId + '.json')
  : resolve(directory, 'approval-recovery-' + snapshot.runId + '.json')
const privatePath = resolve(directory, 'approval-recovery-' + snapshot.runId + '.private.json')
const privateState = sourcePath ? JSON.parse(await readFile(privatePath, 'utf8')) : { runId: snapshot.runId, actions: [] }
if (sourcePath && privateState.runId !== snapshot.runId) throw new Error('私有证据与报告runId不匹配')
if (compare) {
  snapshot.comparisonRunId = comparison.comparisonRunId
  snapshot.sourceCapture = comparison.sourceCapture
} else if (capture) {
  snapshot.captureRunId = captureId
  snapshot.sourcePrepare = { path: resolve(resumePrepared), sha256: fingerprint(sourceBytes), status: snapshot.status }
}
const check = (name, condition, detail = {}) => { snapshot.checks.push({ name, pass: !!condition, detail }); if (!condition) throw new Error(name) }
async function save() {
  await mkdir(directory, { recursive: true })
  await writeFile(publicPath, JSON.stringify(snapshot, null, 2) + '\n')
  if (!compare) await writeFile(privatePath, JSON.stringify(privateState, null, 2) + '\n')
}
async function api(path, { method = 'GET', body, actor, entry = gateway, operationId, allow = [200] } = {}) {
  const requestId = randomUUID(), started = Date.now()
  const response = await fetch(entry + path, { method, headers: { 'Content-Type': 'application/json', 'X-Request-Id': requestId,
    ...(actor ? { Authorization: 'Bearer ' + actor.token } : {}), ...(operationId ? { 'X-Operation-Id': operationId } : {}) },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }), signal: AbortSignal.timeout(180000) })
  const result = { status: response.status, headers: Object.fromEntries(response.headers), body: await response.json(), requestId }
  snapshot.calls.push({ path: path.split('?')[0], method, requestId, status: result.status, code: result.body.code, durationMs: Date.now() - started })
  if (!allow.includes(result.body.code)) throw new Error(path + '返回业务码' + result.body.code)
  return result
}
const data = async (path, options) => (await api(path, options)).body.data
async function account() {
  const actor = { username: 'ar_' + randomBytes(7).toString('hex'), password: 'Ar' + randomBytes(12).toString('hex') }
  const registration = await data('/auth/register', { method: 'POST', body: { ...actor, nickname: '审批恢复隔离验收' } })
  actor.token = registration.token; actor.userId = String(registration.user.id)
  check('独立账号已签发', actor.token && /^[A-Za-z0-9_-]{1,100}$/.test(actor.userId)); return actor
}
async function authority(actor, tool) {
  if (tool === 'ticket_create') {
    const page = await data('/tickets?page=0&size=100', { actor })
    const details = await Promise.all(page.records.map((item) => data('/tickets/' + item.id, { actor })))
    return { total: page.total, tickets: details.map((item) => ({ ticket: item.ticket, messages: item.messages })).sort((a, b) => a.ticket.id - b.ticket.id) }
  }
  return (await data('/coupons/mine', { actor })).map((item) => ({ id: item.id, couponId: item.couponId, status: item.status })).sort((a, b) => a.id - b.id)
}
async function logProof(requestId) {
  const candidates = ['ai-service.log']
  try { candidates.push(...(await readdir(resolve(logDirectory, 'archive'))).filter((item) => /^ai-service.*\.log$/.test(item)).map((item) => 'archive/' + item)) } catch { /* 未轮转 */ }
  for (let attempt = 0; attempt < 10; attempt++) {
    let lines = []
    for (const path of candidates) { try { lines.push(...(await readFile(resolve(logDirectory, path), 'utf8')).split(/\r?\n/).filter((line) => line.includes('[' + requestId + ']'))) } catch { /* 文件未就绪 */ } }
    const approvals = lines.filter((line) => line.includes('[Approval]') && line.includes('mode='))
    const model = lines.filter((line) => /\[LLM\]|\[QueryExpand\]|\[Rerank\]|本轮用量/.test(line))
    if (approvals.length) return { requestId, observed: true, approvalModes: approvals.map((line) => line.match(/mode=([^\s]+)/)?.[1]), modelEntries: model.length }
    await new Promise((done) => setTimeout(done, 100))
  }
  return { requestId, observed: false, modelEntries: null }
}
async function recover(state, retry) {
  const result = await api(`/ai/sessions/${state.sessionId}/approvals/recover`, { method: 'POST', actor: state.actor, body: { approvalToken: state.approvalToken, retry } })
  assertOriginalRecovery(result.body.data, state)
  const proof = await logProof(result.requestId)
  check('恢复日志证明执行模式且模型调用为零', proof.observed && proof.modelEntries === 0, proof)
  return { requestId: result.requestId, messageId: result.body.data.assistantMessageId, operationId: result.body.data.toolCalls[0].operationId,
    status: result.body.data.approvalStatus, usage: result.body.data.usage, mode: retry ? 'EXPLICIT_RETRY' : 'READ_ONLY', outputDigest: digest(result.body.data.toolCalls[0].output), log: proof }
}
async function authoritativeReceipt(state) {
  const action = state.action
  const expectedPath = action.tool === 'ticket_create' ? '/tickets' : action.tool === 'coupon_receive'
    && Number.isSafeInteger(action.parameters?.couponId) && action.parameters.couponId > 0 ? `/coupons/${action.parameters.couponId}/receive` : null
  if (!expectedPath || action.path !== expectedPath || !action.operationId || !/^[A-Za-z0-9_-]{1,100}$/.test(String(state.actor.userId))) throw new Error('回执比较路径或隔离身份不在实验白名单')
  const response = await api(action.path, { actor: state.actor, method: 'POST', body: action.body, operationId: action.operationId })
  const receiptDigest = assertReceipt(response, action, state.actor.userId, true)
  return { digest: receiptDigest, parametersHash: response.headers['x-operation-parameters-hash'], responseId: response.body.data.ticket?.id ?? response.body.data.id, requestId: response.requestId }
}
async function waitMarker(path, label, timeoutMs = 900000) {
  if (!path) throw Object.assign(new Error(label + '缺主代理证据文件'), { blocked: true })
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    try { const marker = JSON.parse(await readFile(resolve(path), 'utf8')); if (marker.runId === snapshot.runId && marker.ready === true && marker.pid > 0) return marker } catch { /* 主代理未兑现窗口 */ }
    await new Promise((done) => setTimeout(done, 500))
  }
  throw Object.assign(new Error(label + '等待主代理窗口超时'), { blocked: true })
}
async function prepareOne(tool) {
  const actor = await account(); const sessionId = 'approval-live-' + randomBytes(10).toString('hex')
  const state = { actor, sessionId }
  privateState.actions.push(state); await save()
  let message
  if (tool === 'ticket_create') message = '请创建客服工单，分类OTHER，标题“审批恢复验收”，内容“我需要核对客服处理规则”，不要关联订单。直接给出这一个工单的确认卡，不执行其他操作。'
  else {
    const coupon = (await data('/coupons/available', { actor })).find((item) => !item.received && item.remainingCount > 0)
    check('存在独立用户可领取优惠券', !!coupon)
    message = `请领取优惠券编号${coupon.id}，这是我明确提供的编号。只给出这张券的确认卡，不调用工具表外前置工具。`
  }
  const first = await data('/ai/chat', { actor, method: 'POST', body: { sessionId, message } })
  check('真实模型签发单动作审批', first.approvalToken && first.usage?.totalTokens > 0 && first.pendingActionDetails?.length === 1 && first.pendingActionDetails[0].tool === tool)
  const actions = JSON.parse(Buffer.from(first.approvalToken.split('.')[0], 'base64url').toString('utf8')).a
  check('真实签名载荷含唯一原工具及op', actions?.length === 1 && actions[0].t === tool && typeof actions[0].o === 'string' && actions[0].o.length > 0)
  const action = actions[0]; const parameters = tool === 'ticket_create'
    ? { category: String(action.p.category).toUpperCase(), title: String(action.p.title).trim(), content: String(action.p.content).trim(), orderId: action.p.orderId == null ? null : Number(action.p.orderId) }
    : { couponId: Number(action.p.couponId) }
  Object.assign(state, { approvalToken: first.approvalToken, messageId: first.assistantMessageId, turnId: first.turnId, signedModelUsage: first.usage.totalTokens,
    action: { tool, operationId: action.o, parameters, path: tool === 'ticket_create' ? '/tickets' : `/coupons/${parameters.couponId}/receive`, body: tool === 'ticket_create' ? parameters : undefined } })
  state.before = await authority(actor, tool); check('未确认时零副作用', tool === 'ticket_create' ? state.before.total === 0 : state.before.length === 0)
  snapshot.actions.push({ tool, sessionId, messageId: state.messageId, turnId: state.turnId, tokenFingerprint: fingerprint(first.approvalToken), operationId: action.o, signedModelUsage: first.usage.totalTokens, preparedAt: new Date().toISOString() })
  await save(); return state
}

async function captureOne(state) {
  const { actor, sessionId, action } = state
  const tool = action.tool
  const mode = args.includes('--promotion-downstream') ? 'PROMOTION_COMMITTED_UNKNOWN' : args.includes('--downstream') ? 'ORDER_COMMITTED_UNKNOWN' : 'CLIENT_COMMITTED_UNKNOWN'
  if (mode === 'ORDER_COMMITTED_UNKNOWN' && tool !== 'ticket_create') throw new Error('19003固定窗口仅覆盖order工单')
  if (mode === 'PROMOTION_COMMITTED_UNKNOWN' && tool !== 'coupon_receive') throw new Error('19007固定窗口仅覆盖原签名领取优惠券')
  let server, upstreamCapture
  state.mode = mode
  try {
    if (mode === 'ORDER_COMMITTED_UNKNOWN') {
      server = createLostResponseProxy({ targetPort: 9003, mode, userId: actor.userId, operationId: action.operationId,
        parametersHash: parameterHash(actor.userId, action.tool, action.parameters), capture: (response) => { upstreamCapture = response } })
      await listen(server, 19003)
      console.log(JSON.stringify({ event: 'ORDER_PROXY_READY', runId: snapshot.runId, port: 19003, publicPath }))
      await waitMarker(has('--activate-evidence='), 'AI19003激活')
    }
    if (mode === 'PROMOTION_COMMITTED_UNKNOWN') {
      server = createLostResponseProxy({ targetPort: 9007, mode, userId: actor.userId, operationId: action.operationId, couponId: action.parameters.couponId,
        parametersHash: parameterHash(actor.userId, action.tool, action.parameters),
        verifyPromotionCommit: async (firstCoupon) => {
          const result = await api('/coupons/mine', { actor })
          const records = Array.isArray(result.body.data) ? result.body.data.filter((item) => item.couponId === action.parameters.couponId) : []
          return { userId: actor.userId, couponId: action.parameters.couponId, userCouponId: records[0]?.id,
            count: records.length, requestId: result.requestId, firstUserCouponId: firstCoupon.id }
        }, capture: (response) => { upstreamCapture = response } })
      await listen(server, 19007)
      console.log(JSON.stringify({ event: 'PROMOTION_PROXY_READY', runId: snapshot.runId, port: 19007, publicPath }))
      await waitMarker(has('--activate-evidence='), 'AI19007激活')
    }
    if (!server) {
      server = createLostResponseProxy({ targetPort: Number(gatewayUrl.port || 80), mode,
        authFingerprint: fingerprint('Bearer ' + actor.token), tokenFingerprint: fingerprint(state.approvalToken), capture: (response) => { upstreamCapture = response } })
      await listen(server)
    }
    let confirmation
    const confirmEntry = mode === 'CLIENT_COMMITTED_UNKNOWN' ? 'http://127.0.0.1:' + server.address().port : gateway
    try { confirmation = await api('/ai/chat', { actor, entry: confirmEntry, method: 'POST', body: { sessionId, message: '确认执行', approvalToken: state.approvalToken } }) }
    catch (failure) { if (!server.controlEvidence.dropped.length) throw failure }
    check('完整成功响应已接收后真实丢弃返回', server.controlEvidence.dropped.length === 1 && server.controlEvidence.dropped[0].forwarded && server.controlEvidence.dropped[0].fullResponseReceived)
    if (mode !== 'CLIENT_COMMITTED_UNKNOWN') check('AI内部UNKNOWN且不是客户端未知', confirmation?.body.data.approvalStatus === 'UNKNOWN' && confirmation.body.data.toolCalls[0].operationState === 'UNKNOWN' && server.controlEvidence.blockedQueries.length > 0)
    state.redis = readApprovalSummary(actor.userId, sessionId, state.approvalToken)
    check('Redis保留原接受批次及原消息', state.redis.acceptedAt > 0 && state.redis.originalMessageId === state.messageId && state.redis.tokenFingerprint === fingerprint(state.approvalToken))
    if (mode !== 'CLIENT_COMMITTED_UNKNOWN') {
      check('未知批次未消费DONE', state.redis.status === 'UNKNOWN')
      state.firstReceipt = { digest: digest(upstreamCapture.body), parametersHash: upstreamCapture.headers['x-operation-parameters-hash'],
        responseId: mode === 'ORDER_COMMITTED_UNKNOWN' ? upstreamCapture.body.data.ticket.id : upstreamCapture.body.data.id }
      server.releaseQueries()
    }
    const baselineReceipt = await authoritativeReceipt(state)
    check('读取首次回执没有再次业务副作用', !state.firstReceipt || baselineReceipt.digest === state.firstReceipt.digest)
    state.firstReceipt = baselineReceipt
    state.recovery = [await recover(state, false), await recover(state, true)]
    state.authority = await authority(actor, tool); state.authorityDigest = digest(state.authority)
    check('原op恢复只有一个业务对象', tool === 'ticket_create' ? state.authority.total === 1 && state.authority.tickets[0].messages.length === 1 : state.authority.length === 1)
    const receiptAgain = await authoritativeReceipt(state); check('首次JSON回执全部字段和值稳定', receiptAgain.digest === state.firstReceipt.digest)
    check('恢复不增加副作用', digest(await authority(actor, tool)) === state.authorityDigest)
    state.proxyEvidence = structuredClone(server.controlEvidence)
    Object.assign(snapshot.actions.find((item) => item.operationId === action.operationId), { mode, firstReceipt: state.firstReceipt, authorityDigest: state.authorityDigest, redis: state.redis, recovery: state.recovery, proxyEvidence: state.proxyEvidence })
    await save()
  } finally {
    if (server && mode !== 'CLIENT_COMMITTED_UNKNOWN') {
      console.log(JSON.stringify({ event: 'RESTORE_AI_BASELINE_REQUIRED', runId: snapshot.runId, publicPath }))
      const restored = await waitMarker(has('--restore-evidence='), mode === 'PROMOTION_COMMITTED_UNKNOWN' ? 'AI9007恢复' : 'AI9003恢复').finally(() => close(server))
      snapshot.aiBaselineRestore = { ready: restored.ready, pid: restored.pid, proxyClosed: true }
    } else await close(server)
  }
}
try {
  if (prepare || capture) {
    const tools = has('--tool=') ? [has('--tool=')] : args.includes('--promotion-downstream') ? ['coupon_receive'] : args.includes('--downstream') ? ['ticket_create'] : ['ticket_create', 'coupon_receive']
    if (tools.some((tool) => !['ticket_create', 'coupon_receive'].includes(tool))) throw new Error('工具不在两个独立丢响应实验白名单')
    if (prepare) {
      for (const tool of tools) await prepareOne(tool)
      snapshot.status = 'PREPARED'
    } else {
      if (!resumePrepared) throw new Error('capture必须复用 --from 原prepare证据，不能重复模型签发')
      for (const state of privateState.actions.filter((item) => tools.includes(item.action.tool) && !item.proxyEvidence)) await captureOne(state)
      snapshot.status = privateState.actions.every((item) => item.proxyEvidence) ? 'CAPTURED_WAITING_COMPARE' : 'CAPTURED_PARTIAL'; snapshot.restartStatus = 'NOT_EXECUTED'
      snapshot.uncovered = [...(snapshot.actions.some((item) => item.mode === 'PROMOTION_COMMITTED_UNKNOWN') ? [] : ['promotion AI内部提交未知（本轮只证明客户端或order模式）']), 'MySQL/Seata全局事务未决专项']
    }
  } else {
    const restartPath = has('--restart-evidence=')
    if (!restartPath) throw Object.assign(new Error('缺主代理三服务真实重启证据'), { blocked: true })
    const evidence = JSON.parse(await readFile(resolve(restartPath), 'utf8'))
    for (const service of ['ai', 'order', 'promotion']) {
      const item = Array.isArray(evidence.services) ? evidence.services.find((entry) => entry.service === service) : evidence.services?.[service]
      check(service + '真实PID/ready改变', item?.beforePid > 0 && item.afterPid > 0 && item.beforePid !== item.afterPid && item.afterReady === true)
    }
    for (const state of privateState.actions) {
      const original = snapshot.actions.find((item) => item.operationId === state.action?.operationId)
      check('私有原签名身份与capture报告一致', !!original && original.tokenFingerprint === fingerprint(state.approvalToken)
        && original.messageId === state.messageId && original.turnId === state.turnId && original.tool === state.action.tool)
      const login = await data('/auth/login', { method: 'POST', body: { username: state.actor.username, password: state.actor.password } }); state.actor.token = login.token
      const before = await authority(state.actor, state.action.tool); check('重启前后业务状态稳定', digest(before) === state.authorityDigest)
      const response = await recover(state, false)
      const receipt = await authoritativeReceipt(state); check('跨重启首次JSON与原hash保持不变', receipt.digest === state.firstReceipt.digest && receipt.parametersHash === state.firstReceipt.parametersHash)
      const redis = readApprovalSummary(state.actor.userId, state.sessionId, state.approvalToken)
      check('跨重启原Redis消息与接受时间保持', redis.originalMessageId === state.messageId && redis.acceptedAt === state.redis.acceptedAt)
      check('跨重启恢复副作用没有新增', digest(await authority(state.actor, state.action.tool)) === state.authorityDigest)
      snapshot.actions.find((item) => item.operationId === state.action.operationId).restartRecovery = { response, receipt, redis }
    }
    snapshot.restartStatus = 'PASS'; snapshot.status = 'PASS'
    snapshot.uncovered = [...(snapshot.actions.some((item) => item.mode === 'PROMOTION_COMMITTED_UNKNOWN') ? [] : ['promotion AI内部提交未知（本轮只证明客户端或order模式）']), 'MySQL/Seata全局事务未决专项']
  }
} catch (failure) { snapshot.status = failure.blocked ? 'BLOCKED' : 'FAIL'; snapshot.error = failure.message; process.exitCode = failure.blocked ? 2 : 1 }
finally { snapshot.finishedAt = new Date().toISOString(); await save(); console.log(JSON.stringify({ runId: snapshot.runId, status: snapshot.status, checks: snapshot.checks.length, path: publicPath, restartStatus: snapshot.restartStatus })) }
