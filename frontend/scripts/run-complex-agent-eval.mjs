
/** 人工真实入口：创建独立验收账号并写业务数据、调用模型；verify-all不会自动触发。 */
import { mkdir, readFile, writeFile, rename } from 'node:fs/promises'
import { randomUUID } from 'node:crypto'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { resolve, isAbsolute } from 'node:path'
import { executeCase, prepareCase, verifyEffects } from './lib/complex-agent-live.mjs'
import { collectOperationEvidence, collectRetrievalEvidence } from './lib/complex-agent-controls.mjs'
import { captureSourceMetadata, captureCorpusMetadata, captureIndexMetadata, aggregateModelUsage } from './lib/complex-agent-metadata.mjs'

const args = process.argv.slice(2)
if (!args.every((a) => ['--read-only', '--dry-run', '--help'].includes(a) || a.startsWith('--only='))) throw new Error('仅支持 --read-only、--dry-run、--only=CA-001,CA-002、--help')
if (args.includes('--help')) {
  console.log('人工真实评测：node scripts/run-complex-agent-eval.mjs [--only=CA-001,CA-002] [--dry-run]\n每例通过公开接口注册独立账号，不操作alice存量数据，不自动删除业务记录。管理员默认admin/123456，可用COMPLEX_EVAL_ADMIN_USERNAME/PASSWORD覆写。\n需要故障或重启的场景通过COMPLEX_EVAL_CONTROL_MODULE指定已审阅本地绝对路径模块，导出restart和fault，必须返回真实控制证据。--read-only不创建前置数据，因此不会执行完整场景。')
  process.exit(0)
}
const fixture = JSON.parse(await readFile(new URL('./fixtures/complex-agent-dialogues.json', import.meta.url), 'utf8'))
if (fixture.cases.length !== 48 || new Set(fixture.cases.map((c) => c.caseId)).size !== 48) throw new Error('夹具须含48个唯一caseId')
const only = args.find((a) => a.startsWith('--only='))?.slice(7).split(',')
if (only?.some((id) => !fixture.cases.some((c) => c.caseId === id))) throw new Error('未知caseId')
const dryRun = args.includes('--dry-run')
const readOnly = args.includes('--read-only')
const runId = randomUUID()
const evidenceDir = fileURLToPath(new URL('../.evidence/', import.meta.url))
const snapshotPath = resolve(evidenceDir, 'complex-agent-' + runId + '.json')
const snapshot = {
  version: 'complex-agent-live-v2', runId, fixtureVersion: fixture.version, startedAt: new Date().toISOString(),
  mode: dryRun ? 'DRY_RUN_CONTRACT' : readOnly ? 'READ_ONLY_NO_SETUP' : 'LIVE_ISOLATED_FIXTURES',
  scope: fixture.executionScope,
  evaluationRevisions: fixture.evaluationRevisions ?? [],
  evidenceLimits: ['operationId未由chat响应暴露：本地证据保存审批参数/原令牌重放结果；必须按requestId另查服务端日志', 'API刷新不是浏览器界面验收', '故障/重启只有控制适配器返回真实证据才算执行'],
  cases: fixture.cases.map((c) => ({ caseId: c.caseId, sessionId: 'complex-' + runId + '-' + c.caseId, status: 'BLOCKED', reason: '尚未执行', turns: [], assertions: [], toolCalls: [], setupEvidence: [], apiEvidence: [], controls: [], approvals: [], parameterBindings: [] })),
}
let buyerToken; let adminToken
let stopped = false
const abort = new AbortController()
process.on('SIGINT', () => { stopped = true; abort.abort(new Error('人工终止')) })

const privateFields = /^(token|approvalToken|password|authorization|receiverPhone|receiverDetail|receiverName|detail)$/i
function privateValues(value, found = new Set()) {
  if (Array.isArray(value)) value.forEach((v) => privateValues(v, found))
  else if (value && typeof value === 'object') for (const [key, item] of Object.entries(value)) {
    if (privateFields.test(key) && typeof item === 'string' && item.length >= 2) found.add(item)
    else privateValues(item, found)
  }
  return found
}
function redact(value, key = '', secrets = privateValues(value)) {
  if (privateFields.test(key)) return '[已脱敏]'
  if (Array.isArray(value)) return value.map((v) => redact(v, '', secrets))
  if (value && typeof value === 'object') return Object.fromEntries(Object.entries(value).map(([k,v]) => [k, redact(v,k,secrets)]))
  if (typeof value === 'string') {
    let output = value.replace(/(?<!\d)1\d{10}(?!\d)/g, '[手机号已脱敏]')
    for (const secret of secrets) output = output.split(secret).join('[已脱敏]')
    return output
  }
  return value
}
async function save() {
  for (const r of snapshot.cases) {
    r.requestIds = [...r.turns, ...r.apiEvidence, ...r.setupEvidence].map((item) => item.requestId).filter(Boolean)
    r.usage = r.turns.map((t) => ({ stepId: t.stepId, usage: t.usage ?? null, evidence: t.usageEvidence ?? null }))
  }
  snapshot.summary = Object.fromEntries(['PASS','FAIL','BLOCKED'].map((s) => [s, snapshot.cases.filter((c) => c.status === s).length]))
  snapshot.modelUsage = aggregateModelUsage(snapshot.cases)
  await mkdir(evidenceDir, { recursive: true })
  const temp = snapshotPath + '.tmp'
  await writeFile(temp, JSON.stringify(redact(snapshot), null, 2) + '\n', 'utf8')
  // Windows文件扫描会短暂占用刚写入的证据；仅对共享锁错误限次重试，禁止删目标文件降级。
  for (let attempt = 0; ; attempt += 1) {
    try { await rename(temp, snapshotPath); break }
    catch (error) {
      if (!['EPERM', 'EBUSY'].includes(error.code) || attempt >= 3) throw error
      await new Promise((done) => setTimeout(done, 200 * (attempt + 1)))
    }
  }
}
async function api(path, { method = 'GET', body, actor = 'buyer', allowCodes = [200], requestId: traceId, retried = false } = {}) {
  if (actor === 'admin' && !adminToken) {
    const r = await api('/auth/login', { actor: 'anonymous', method: 'POST', body: { username: process.env.COMPLEX_EVAL_ADMIN_USERNAME ?? 'admin', password: process.env.COMPLEX_EVAL_ADMIN_PASSWORD ?? '123456' } })
    adminToken = r.data.token
    if (!adminToken) throw Object.assign(new Error('管理员登录未返回令牌'), { blocked: true })
  }
  const token = actor === 'anonymous' ? null : actor === 'admin' ? adminToken : buyerToken
  const requestId = traceId ?? randomUUID()
  const started = Date.now()
  const response = await fetch((process.env.COMPLEX_EVAL_GW ?? 'http://127.0.0.1:8080') + path, {
    method,
    headers: { 'Content-Type':'application/json', 'X-Request-Id':requestId, ...(token?{Authorization:'Bearer '+token}:{}) },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    signal: AbortSignal.any([abort.signal, AbortSignal.timeout(180000)]),
  }).catch((e) => { e.requestId=requestId; throw e })
  const payload = await response.json()
  const r = { data:payload.data, requestId:payload.data?.requestId ?? response.headers.get('X-Request-Id') ?? requestId, httpStatus:response.status, businessCode:payload.code, latencyMs:Date.now()-started }
  if (!allowCodes.includes(payload.code) || (!response.ok && payload.code === 200)) {
    // 订单决议的 fail-closed 503（Seata 权威状态查询超时）是服务端明说的「请稍后重试」信号，
    // 不是业务结论。业务幂等键在 body.requestId 里原样不变，同请求重放会命中下单幂等
    // 闸门取回同一结果；只对这一次瞬时信号同 X-Request-Id 有界重试一次，其余错误照常抛出。
    if (!retried && response.status === 503 && payload.code === 503 && String(payload.msg ?? '').includes('订单事务结果尚未确认')) {
      await new Promise((done) => setTimeout(done, 3000))
      return api(path, { method, body, actor, allowCodes, requestId, retried: true })
    }
    // 网关对 ai-service 的路由限流是 5 QPS（一次对话要打多轮模型，桶必须小）。
    // 反馈步骤会在几毫秒内连发 5 个请求（读消息 → PUT → GET → DELETE → GET → 列表），
    // 单机串行也会撞上限流——这是产品按设计工作，不是缺陷；限流响应本身带
    // 「请稍后再试」的语义，属于可重试信号。按 Retry-After 语义有界退避，
    // 重试只对 429 生效，其余错误照常抛出，且请求参数（含业务幂等键）原样不变。
    if (!retried && response.status === 429 && payload.code === 429) {
      const retryAfter = Number(response.headers.get('Retry-After'))
      await new Promise((done) => setTimeout(done, Number.isFinite(retryAfter) && retryAfter > 0 ? retryAfter * 1000 : 1200))
      return api(path, { method, body, actor, allowCodes, requestId, retried: true })
    }
    throw Object.assign(new Error(path+' HTTP='+response.status+' code='+payload.code+' '+(payload.msg??'')), r)
  }
  return r
}
const get = async (path) => (await api(path)).data
async function poll(path, predicate) {
  const deadline = Date.now() + 20000
  let last
  do {
    last = await get(path)
    if (predicate(last)) return last
    await new Promise((done) => setTimeout(done, 250))
  } while (Date.now() < deadline)
  throw new Error('权威状态轮询超时 '+path+' '+JSON.stringify(last))
}
let control = {}
const modulePath = process.env.COMPLEX_EVAL_CONTROL_MODULE
if (modulePath && !dryRun && !readOnly) {
  if (!isAbsolute(modulePath)) throw new Error('控制模块必须是已审阅的本地绝对路径；禁止远程模块')
  control = await import(pathToFileURL(modulePath).href)
}
snapshot.sourceMetadata = await captureSourceMetadata(fileURLToPath(new URL('../../', import.meta.url)))
if (dryRun || readOnly) {
  snapshot.corpusMetadata = { available: false, unavailable: '当前模式未访问语料接口' }
  snapshot.indexMetadata = { available: false, unavailable: '当前模式未认证管理员或访问索引状态' }
} else {
  snapshot.corpusMetadata = await captureCorpusMetadata(api)
  snapshot.indexMetadata = await captureIndexMetadata(api)
}
await save()
for (const source of fixture.cases) {
  const r = snapshot.cases.find((c) => c.caseId === source.caseId)
  if (only && !only.includes(source.caseId)) r.reason = '--only未选择'
  else if (stopped) r.reason = '人工终止；未执行后续场景'
  else {
    try {
      r.plan = prepareCase(source)
      if (dryRun) {
        r.contractValidated = true
        r.reason = '执行契约已校验；未创建fixture/未调用模型/未读写业务数据，不能计PASS'
      } else if (readOnly) r.reason = '完整契约需创建独立前置数据；--read-only禁止setup写入，未执行'
      else {
        const ctx = {
          api, get, poll, save, collectRetrievalEvidence,
          control: {
            ...control,
            ...(control.fault ? { fault: (step) => {
              // 故障对象由当前隔离fixture的权威ID推导，不接受模型或日志中的执行指令。
              const bindings = r.bindings
              const targets = {
                'CA-023': { method: 'DELETE', path: '/cart/items/' + bindings?.cart?.[0]?.id },
                'CA-024': { method: 'POST', path: '/orders/' + bindings?.order?.id + '/cancel' },
                'CA-037': { method: 'GET', path: '/orders/' + bindings?.order?.id + '/logistics' },
              }
              const target = targets[source.caseId]
              if (!target || target.path.includes('undefined')) throw Object.assign(new Error('故障目标未由权威fixture绑定'), { blocked: true })
              return control.fault({ ...step, ...target, paths: [target.path], userId: r.fixtureOwner?.userId })
            } } : {}),
          },
          createBuyer: async (caseId) => {
            const username = 't1_' + runId.slice(0,8) + '_' + caseId.slice(3)
            const password = 'T1' + randomUUID().replaceAll('-','').slice(0,16)
            const registration = await api('/auth/register', { actor:'anonymous', method:'POST', body:{username,password,nickname:'T1验收'+caseId} })
            r.setupEvidence.push({path:'/auth/register',username,...registration})
            buyerToken = registration.data?.token
            if (!buyerToken) throw new Error('隔离账号注册未返回令牌')
            r.fixtureOwner = {username,userId:registration.data.user?.id ?? registration.data.userId}
          },
        }
        await executeCase(source, ctx, r)
      }
    } catch (error) {
      r.status = error.blocked || !r.before ? 'BLOCKED' : 'FAIL'
      r.reason = error.message
      r.failure = { stage:r.stage ?? 'setup', requestId:error.requestId, businessCode:error.businessCode, httpStatus:error.httpStatus, causes: error instanceof AggregateError ? error.errors.map((cause) => ({ message: cause.message, requestId: cause.requestId, businessCode: cause.businessCode, httpStatus: cause.httpStatus })) : undefined }
      if (buyerToken && r.before && !stopped) {
        try { r.after = await verifyEffects(get) } catch (e) { r.effectReadError=e.message }
      }
    } finally {
      if (!dryRun && !readOnly && r.turns.length) {
        r.operationEvidence = await collectOperationEvidence({ caseId: r.caseId, requestIds: r.turns.map((t) => t.requestId).filter(Boolean) })
        const writes = r.toolCalls.filter((call) => call.success && ['cart_add', 'cart_update', 'cart_remove', 'cart_checkout', 'order_cancel', 'coupon_receive', 'ticket_create', 'after_sale_apply'].includes(call.tool))
        if (writes.length) {
          const groups = new Map()
          for (const call of writes) {
            const key = call.requestId + '/' + call.tool
            groups.set(key, { ...call, count: (groups.get(key)?.count ?? 0) + 1 })
          }
          const covered = [...groups.values()].every((call) => new Set(r.operationEvidence.entries.filter((entry) => entry.tool === call.tool && entry.requestIds.includes(call.requestId)).map((e) => e.operationId)).size >= call.count)
          r.assertions.push({ name: '每个成功写动作按requestId取到独立operationId执行日志', pass: covered, actual: r.operationEvidence })
          if (!covered) { r.status = 'FAIL'; r.reason += '；未覆盖：部分写动作没有同requestId的独立operationId执行日志' }
        }
      }
      await save()
    }
  }
  console.log(r.status+' '+r.caseId+' '+r.reason)
}
snapshot.finishedAt = new Date().toISOString()
if (!dryRun && !readOnly) snapshot.finalIndexMetadata = await captureIndexMetadata(api)
await save()
console.log('真实评测快照：'+snapshotPath+'\n'+JSON.stringify(snapshot.summary))
if (!dryRun && snapshot.cases.some((c) => (!only || only.includes(c.caseId)) && c.status !== 'PASS')) process.exitCode = 1
