/**
 * 复杂 Agent 真实控制适配器。
 *
 * 默认只读：真实 stop/start 或代理故障注入必须显式设置
 * COMPLEX_EVAL_CONTROL_ENABLE=1，由主代理串行协调。命令参数来自固定白名单，
 * 不接受模型、日志或 fixture 自由拼出的 shell 命令。
 */
import { createServer, request as httpRequest } from 'node:http'
import { readFile, readdir } from 'node:fs/promises'
import { existsSync } from 'node:fs'
import { spawn, spawnSync } from 'node:child_process'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import net from 'node:net'

const HERE = dirname(fileURLToPath(import.meta.url))
const BACKEND = resolve(HERE, '../../../backend')
const SERVICE_PORTS = Object.freeze({
  'gateway-service': 8080, 'auth-service': 9001, 'product-service': 9002,
  'order-service': 9003, 'knowledge-service': 9008, 'ai-service': 9004,
  'payment-service': 9005, 'review-service': 9006, 'promotion-service': 9007,
})
const RESTARTABLE = new Set(['ai-service', 'knowledge-service', 'order-service', 'promotion-service'])
const FAULT_FAILURES = new Set(['READ_TIMEOUT', 'BUSINESS_FAILURE', 'CONNECTION_FAILURE'])
const FAULT_TARGETS = new Set(['order-service'])
const SAFE_PATH_PREFIXES = Object.freeze({
  READ_TIMEOUT: ['/cart/items/'],
  BUSINESS_FAILURE: ['/orders/'],
  CONNECTION_FAILURE: ['/orders/'],
})
const activeFaults = new Map()

function blocked(message) {
  throw Object.assign(new Error(message), { blocked: true })
}

function ensureEnabled(enabled) {
  if (!enabled) blocked('真实控制默认关闭；主代理协调后设置 COMPLEX_EVAL_CONTROL_ENABLE=1')
}

export function findGitBash() {
  const candidates = [process.env.GIT_BASH, 'C:/Program Files/Git/bin/bash.exe', 'C:/Program Files (x86)/Git/bin/bash.exe'].filter(Boolean)
  for (const candidate of candidates) {
    try { if (existsSync(candidate)) return candidate } catch { /* 尝试下一个固定候选 */ }
  }
  try {
    const result = spawnSync('git', ['--exec-path'], { encoding: 'utf8', timeout: 5000 })
    if (result.status === 0 && result.stdout) {
      const candidate = resolve(result.stdout.trim(), '..', '..', '..', 'bin', 'bash.exe')
      if (existsSync(candidate)) return candidate
    }
  } catch { /* 非Windows可使用原生bash */ }
  if (process.platform === 'win32') blocked('未找到Git Bash；禁止回退到Windows PATH里的WSL bash')
  return 'bash'
}

async function runLocalDefault(args, overrides = {}) {
  const allowed = new Set(['stop', ...RESTARTABLE])
  if (!Array.isArray(args) || args.length < 1 || !allowed.has(args[0]) || args.length > 2 || (args.length === 2 && (args[0] !== 'stop' || !RESTARTABLE.has(args[1])))) {
    throw new Error('run-local 参数不在固定服务白名单中')
  }
  const script = resolve(BACKEND, 'run-local.sh')
  if (Object.entries(overrides).some(([key, value]) => key !== 'ORDER_SERVICE_URL' || !['http://127.0.0.1:19003', 'http://127.0.0.1:9003'].includes(value))) throw new Error('控制环境仅允许已明确审阅的ORDER_SERVICE_URL两个本地地址')
  return new Promise((done, reject) => {
    const child = spawn(findGitBash(), [script, ...args], { cwd: BACKEND, stdio: 'inherit', windowsHide: true, env: { ...process.env, ...overrides } })
    child.once('error', reject)
    child.once('exit', (status) => status === 0 ? done({ status }) : reject(new Error(`run-local ${args.join(' ')} 退出码 ${status}`)))
  })
}

function processSnapshotDefault(port) {
  if (process.platform !== 'win32') return null
  const script = `$c=Get-NetTCPConnection -LocalPort ${Number(port)} -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1; if($c){$p=Get-Process -Id $c.OwningProcess; [pscustomobject]@{pid=$p.Id; startedAt=$p.StartTime.ToUniversalTime().ToString('o')} | ConvertTo-Json -Compress}`
  const result = spawnSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', script], { encoding: 'utf8', timeout: 20000 })
  if (result.status !== 0 || !result.stdout.trim()) return null
  return JSON.parse(result.stdout)
}

function stopOwnedProcessDefault(snapshot) {
  if (process.platform !== 'win32') blocked('缺本平台按PID/StartTime安全停止本轮进程的实现')
  if (!Number.isInteger(snapshot?.pid) || snapshot.pid <= 0 || !/^\d{4}-\d{2}-\d{2}T[\d:.]+Z$/.test(snapshot.startedAt)) throw new Error('本轮进程身份缺有效PID/StartTime')
  // 再由原生进程句柄核对启动时间；端口已被复用时不能误杀另一个实例。
  const script = `$p=Get-Process -Id ${snapshot.pid} -ErrorAction Stop; if($p.StartTime.ToUniversalTime().ToString('o') -ne '${snapshot.startedAt}'){throw '进程启动时间已变化'}; Stop-Process -InputObject $p -Force`
  const result = spawnSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', script], { encoding: 'utf8', timeout: 30000 })
  if (result.status !== 0) throw new Error(`停止本轮PID ${snapshot.pid}失败；未尝试其他PID`)
}

function portOpen(port, timeoutMs = 1000) {
  return new Promise((resolveResult) => {
    const socket = net.connect(port, '127.0.0.1')
    const timer = setTimeout(() => { socket.destroy(); resolveResult(false) }, timeoutMs)
    socket.once('connect', () => { clearTimeout(timer); socket.destroy(); resolveResult(true) })
    socket.once('error', () => { clearTimeout(timer); resolveResult(false) })
  })
}

async function waitDownDefault(port, timeoutMs = 30000) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    if (!(await portOpen(port))) return true
    await new Promise((done) => setTimeout(done, 500))
  }
  return false
}

async function waitReadyDefault(service, timeoutMs = 900000) {
  const port = SERVICE_PORTS[service]
  if (!port) throw new Error(`未知服务 ${service}`)
  const deadline = Date.now() + timeoutMs
  let last = '未响应'
  let adminToken
  while (Date.now() < deadline) {
    try {
      const response = await fetch(`http://127.0.0.1:${port}/actuator/health`, { signal: AbortSignal.timeout(5000) })
      last = `HTTP ${response.status}`
      if (response.status === 200 || response.status === 404) {
        if (service !== 'ai-service') return { ready: true, status: response.status }
        // AI健康端点先于后台索引完成；必须等知识链路恢复，避免用空索引验收重启。
        const gateway = process.env.COMPLEX_EVAL_GW ?? 'http://127.0.0.1:8080'
        if (!adminToken) {
          const login = await fetch(gateway + '/auth/login', {
            method: 'POST', headers: { 'Content-Type': 'application/json' }, signal: AbortSignal.timeout(5000),
            body: JSON.stringify({ username: process.env.COMPLEX_EVAL_ADMIN_USERNAME ?? 'admin', password: process.env.COMPLEX_EVAL_ADMIN_PASSWORD ?? '123456' }),
          })
          const payload = await login.json()
          if (login.ok && payload.code === 200) adminToken = payload.data?.token
          if (!adminToken) throw new Error('重启索引状态认证失败')
        }
        const statusResponse = await fetch(gateway + '/ai/admin/knowledge/status', {
          headers: { Authorization: 'Bearer ' + adminToken }, signal: AbortSignal.timeout(5000),
        })
        const payload = await statusResponse.json()
        if (statusResponse.ok && payload.code === 200 && knowledgeIndexReady(payload.data)) {
          return { ready: true, status: response.status, knowledge: payload.data }
        }
        if ([401, 403].includes(statusResponse.status) || [401, 403].includes(payload.code)) adminToken = undefined
        last = '知识索引未就绪'
      }
    } catch (error) { last = error.code ?? error.name ?? '连接失败' }
    await new Promise((done) => setTimeout(done, 2000))
  }
  throw new Error(`${service} 就绪超时：${last}`)
}

function knowledgeIndexReady(status) {
  const result = status?.result
  return status?.running === false && !status.error && Boolean(status.finishedAt)
    && Number.isInteger(result?.documentCount) && result.documentCount > 0
    && Number.isInteger(result?.chunkCount) && result.chunkCount > 0
    && result.graphAvailable === true && result.graphFailed === 0
}

function validateServices(services) {
  if (!Array.isArray(services) || services.length !== 1 || !RESTARTABLE.has(services[0])) {
    blocked(`控制只允许一次重启一个固定服务：${[...RESTARTABLE].join(', ')}`)
  }
  return services[0]
}

async function restartImpl({ services, caseId, sessionId }, deps) {
  ensureEnabled(deps.enabled)
  const service = validateServices(services)
  const port = SERVICE_PORTS[service]
  const before = await deps.snapshot(port)
  if (!before?.pid || !before.startedAt) blocked(`${service} 重启前没有可核对的 PID/StartTime`)
  let stopped = false
  let startedProcess
  try {
    stopped = true
    await deps.runLocal(['stop', service])
    if (!(await deps.waitDown(port))) throw new Error(`${service} 停止后端口仍在监听`)
    await deps.runLocal([service])
    const first = await deps.snapshot(port)
    if (first?.pid && first.startedAt && String(first.pid) !== String(before.pid) && first.startedAt !== before.startedAt) startedProcess = first
    const ready = await deps.waitReady(service)
    const after = await deps.snapshot(port)
    if (!after?.pid || !after.startedAt || String(after.pid) === String(before.pid) || after.startedAt === before.startedAt) throw new Error(`${service} 重启后 PID/StartTime 未发生变化`)
    return { ready: Boolean(ready?.ready ?? ready), service, caseId, sessionId, port, services: { [service]: { beforePid: before.pid, afterPid: after.pid, beforeStartTime: before.startedAt, afterStartTime: after.startedAt } }, beforePid: before.pid, afterPid: after.pid, beforeStartTime: before.startedAt, afterStartTime: after.startedAt, health: ready }
  } catch (error) {
    // stop之后的任何失败都必须尝试恢复；不能让一次验收把服务留在半停状态。
    if (stopped) {
      try {
        const current = await deps.snapshot(port)
        if (!current) await deps.runLocal([service])
        else if (startedProcess && current.pid === startedProcess.pid && current.startedAt === startedProcess.startedAt) {
          await deps.stopOwnedProcess(startedProcess)
          if (!(await deps.waitDown(port))) throw new Error(`${service} 本轮半启进程停止后端口仍被占用`)
          await deps.runLocal([service])
        }
        await deps.waitReady(service)
      } catch (restoreError) { throw new AggregateError([error, restoreError], `${service} 重启失败：${error.message}；恢复失败：${restoreError.message}`) }
    }
    throw error
  }
}

function pathMatches(pathname, prefixes) {
  return prefixes.some((prefix) => pathname.startsWith(prefix))
}

function createFaultProxy({ targetPort, failure, targetUserId, pathPrefixes, targetMethod, targetPath, timeoutMs = 32000 }) {
  if (!FAULT_FAILURES.has(failure)) throw new Error(`未知故障模式 ${failure}`)
  if (!targetUserId || typeof targetUserId !== 'string') blocked('故障代理必须绑定独立验收用户 X-User-Id')
  const prefixes = pathPrefixes?.length ? pathPrefixes : SAFE_PATH_PREFIXES[failure]
  if (!prefixes?.length || prefixes.some((p) => typeof p !== 'string' || !p.startsWith('/'))) blocked('故障代理缺少固定路径白名单')
  const evidence = { injected: [], forwarded: 0 }
  const server = createServer((request, response) => {
    const user = String(request.headers['x-user-id'] ?? '')
    const pathname = new URL(request.url ?? '/', 'http://127.0.0.1').pathname
    const target = user === targetUserId && pathMatches(pathname, prefixes) && (!targetMethod || request.method === targetMethod) && (!targetPath || pathname === targetPath)
    if (target) {
      evidence.injected.push({ method: request.method, path: pathname, requestId: String(request.headers['x-request-id'] ?? ''), failure, at: new Date().toISOString(), forwarded: false })
      if (failure === 'BUSINESS_FAILURE') {
        response.writeHead(200, { 'content-type': 'application/json; charset=utf-8' })
        response.end(JSON.stringify({ code: 500, msg: 'control：受控业务失败', data: null }))
        return
      }
      if (failure === 'CONNECTION_FAILURE') { request.destroy(); return }
      const timer = setTimeout(() => { request.destroy(); response.destroy() }, timeoutMs)
      request.on('close', () => clearTimeout(timer))
      return
    }
    evidence.forwarded += 1
    const body = []
    request.on('data', (chunk) => body.push(chunk))
    request.on('end', () => {
      const headers = { ...request.headers, host: `127.0.0.1:${targetPort}` }
      const upstream = netRequest(targetPort, request.method, request.url, headers, Buffer.concat(body))
      upstream.on('response', (upstreamResponse) => {
        response.writeHead(upstreamResponse.statusCode ?? 502, upstreamResponse.headers)
        upstreamResponse.pipe(response)
      })
      upstream.on('error', () => { if (!response.headersSent) response.writeHead(502); response.end() })
    })
  })
  server.controlEvidence = evidence
  return server
}

function netRequest(port, method, path, headers, body) {
  const request = httpRequest({ hostname: '127.0.0.1', port, method, path, headers }, () => {})
  request.end(body)
  return request
}

async function listen(server, port = 0) {
  await new Promise((resolveListen, reject) => { server.once('error', reject); server.listen(port, '127.0.0.1', resolveListen) })
  return server.address().port
}

async function close(server) {
  if (!server?.listening) return
  const closed = new Promise((resolveClose) => server.close(() => resolveClose()))
  server.closeAllConnections?.()
  await closed
}

async function faultImpl(step, deps) {
  ensureEnabled(deps.enabled)
  // run-local的端口判据来自application.yml，不能安全启动/停止9009替身。
  // 必须显式启用已审阅19003代理方案或注入经审阅的staging生命周期。
  if (!deps.staging) blocked('19003代理方案尚未显式启用（COMPLEX_EVAL_FAULT_STAGING=1）；禁止用9009迁移真实order-service')
  if (step.target !== 'order-service' || !FAULT_TARGETS.has(step.target)) blocked('故障控制只允许 order-service')
  if (!FAULT_FAILURES.has(step.failure)) blocked('故障模式不在白名单')
  const key = `${step.caseId}:${step.sessionId}`
  if (step.mode === 'disable') {
    const active = activeFaults.get(key)
    if (!active) blocked('缺本轮已执行故障控制状态，不能伪造恢复证据')
    const proxyEvidence = structuredClone(active.proxy.controlEvidence)
    let ready; let restoreError; let closeError
    try { ready = await deps.staging.restore(active) }
    catch (error) { restoreError = error }
    finally {
      try { await close(active.proxy) } catch (error) { closeError = error }
      activeFaults.delete(key)
    }
    if (restoreError && closeError) throw new AggregateError([restoreError, closeError], `恢复staging失败：${restoreError.message}；关闭故障代理失败：${closeError.message}`)
    if (restoreError) throw restoreError
    if (closeError) throw closeError
    return { verified: Boolean(ready?.ready ?? ready) && proxyEvidence.injected.length > 0, mode: 'disable', failure: step.failure, target: step.target, restored: true, caseId: step.caseId, sessionId: step.sessionId, health: ready, proxyEvidence }
  }
  if (step.mode !== 'enable') blocked('故障模式只能 enable/disable')
  if (activeFaults.size) blocked('已有受控故障，必须串行恢复后再执行另一条场景')
  if (!step.userId) blocked('故障注入缺独立验收用户 userId')
  const original = await deps.snapshot(9003)
  if (!original?.pid) blocked('order-service 原实例没有可核对的 PID')
  let preparation
  try {
    preparation = await deps.staging.prepare(step)
    const proxy = createFaultProxy({ targetPort: preparation.targetPort, failure: step.failure, targetUserId: step.userId, pathPrefixes: step.paths, targetMethod: step.method, targetPath: step.path })
    const proxyPort = await listen(proxy, preparation.proxyPort)
    const active = { proxy, original, preparation, step }
    activeFaults.set(key, active)
    const targetReady = await deps.staging.activate(active)
    if (!targetReady?.ready) throw new Error('staging流量入口未就绪')
    return { verified: true, mode: 'enable', failure: step.failure, target: step.target, caseId: step.caseId, sessionId: step.sessionId, proxyPort, targetPort: preparation.targetPort, targetUserId: step.userId, originalPid: original.pid, originalStartTime: original.startedAt, targetReady }
  } catch (error) {
    const active = activeFaults.get(key)
    try {
      if (active) await deps.staging.restore(active)
      else if (preparation) await deps.staging.restore({ preparation, step, original })
    } catch (restoreError) { error.message += `；恢复staging失败：${restoreError.message}` }
    finally {
      if (active) { await close(active.proxy).catch((closeError) => { error.message += `；关闭故障代理失败：${closeError.message}` }); activeFaults.delete(key) }
    }
    throw error
  }
}

async function collectOperationEvidence({ requestIds = [], caseId } = {}, logPaths, readText = readFile) {
  logPaths ??= await defaultLogPaths()
  const ids = new Set(requestIds.filter((id) => typeof id === 'string' && id.length > 0))
  const lines = []
  const files = []
  for (const path of logPaths) {
    try {
      const text = await readText(path, 'utf8')
      files.push(path)
      for (const line of text.split(/\r?\n/)) {
        if (![...ids].some((id) => line.includes(`[${id}]`))) continue
        if (!line.includes('[Tool]')) continue
        if (!/\[Tool\] [a-z_]+ success=true\b/.test(line) || /重复提交|跳过重复|首次结果/.test(line)) continue
        const match = line.match(/operationId=([^\s,]+)/)
        const operationId = match?.[1]
        if (!operationId || /^(?:null|undefined|NULL|-)$/i.test(operationId)) continue
        lines.push({ path, line: line.slice(0, 2000), requestIds: [...ids].filter((id) => line.includes(`[${id}]`)), operationId, tool: line.match(/tool=([a-z_]+)/)?.[1] ?? line.match(/\[Tool\] ([a-z_]+) /)?.[1] ?? null })
      }
    } catch { /* 日志文件可能不存在；返回未覆盖而非伪造证据 */ }
  }
  return { caseId, requestIds: [...ids], files, entries: lines, covered: lines.length > 0, uniqueOperationIds: [...new Set(lines.map((line) => line.operationId))] }
}

/** 入口RAG执行取证：同请求原查询、重排与证据门共同证明，未检索NONE不能当执行。 */
async function collectRetrievalEvidence({ requestId, query } = {}, logPaths, readText = readFile) {
  logPaths ??= await defaultLogPaths()
  const evidence = { requestId, query, available: false, queryEntries: [], rerankEntries: [], gateEntries: [] }
  if (!requestId || !query) { evidence.unavailable = '缺本轮requestId或实际检索查询'; return evidence }
  for (const path of logPaths) {
    try {
      const lines = (await readText(path, 'utf8')).split(/\r?\n/).filter(line=>line.includes(`[${requestId}]`))
      for (const line of lines) {
        const queryMatch = line.match(/\[QueryExpand\] 「([^」]+)」/)
        if (queryMatch?.[1] === query) evidence.queryEntries.push({ path, line, query: queryMatch[1] })
        const rerank = line.match(/\[Rerank\].*candidates=(\d+) kept=(\d+)/)
        if (rerank) evidence.rerankEntries.push({ path, line, candidates: Number(rerank[1]), kept: Number(rerank[2]) })
        const gate = line.match(/\[Agent\] 证据门 (SUFFICIENT|WEAK|NONE) 切片=(\d+) —— (.*)/)
        if (gate && !gate[3].includes('未检索')) evidence.gateEntries.push({ path, line, level: gate[1], chunks: Number(gate[2]), reason: gate[3] })
      }
    } catch { /* 日志不可读时保持未覆盖 */ }
  }
  evidence.available = evidence.queryEntries.length > 0 && evidence.rerankEntries.length > 0 && evidence.gateEntries.length > 0
  if (!evidence.available) evidence.unavailable = '未取齐同requestId的原查询、重排与已执行证据门日志'
  return evidence
}

async function defaultLogPaths() {
  const logDir = resolve(BACKEND, '../logs/logs-local')
  const paths = ['ai-service.log', 'order-service.log'].map((name) => resolve(logDir, name))
  try { paths.push(...(await readdir(resolve(logDir, 'archive'))).filter((n) => /^ai-service(?:-|\.).*\.log$/.test(n)).map((n) => resolve(logDir, 'archive', n))) } catch { /* 未发生日志轮换 */ }
  return paths
}

/** 已审阅方案：原order9003不迁移，不增加DB/MQ实例；只有AI临时改出口到19003透明代理。 */
function createProxyStaging(deps) {
  if (process.env.COMPLEX_EVAL_FAULT_STAGING !== '1') return undefined
  return {
    async prepare() {
      if (await portOpen(19003)) blocked('19003已被其他进程占用，不抢占端口')
      if (process.env.ORDER_SERVICE_URL && process.env.ORDER_SERVICE_URL !== 'http://127.0.0.1:9003') blocked('当前ORDER_SERVICE_URL不是明确的9003基线，禁止自行覆盖')
      await deps.waitReady('order-service')
      return { targetPort: 9003, proxyPort: 19003 }
    },
    async activate(active) {
      return restartImpl({ services: ['ai-service'], caseId: active.step.caseId, sessionId: active.step.sessionId }, { ...deps, runLocal: (args) => deps.runLocal(args, { ORDER_SERVICE_URL: 'http://127.0.0.1:19003' }) })
    },
    async restore(active) {
      return restoreAiBaseline(active, deps)
    },
  }
}

async function restoreAiBaseline(active, deps) {
  const baselineDeps = { ...deps, runLocal: (args) => deps.runLocal(args, { ORDER_SERVICE_URL: 'http://127.0.0.1:9003' }) }
  const current = await deps.snapshot(9004)
  if (current?.pid) {
    return restartImpl({ services: ['ai-service'], caseId: active.step.caseId, sessionId: active.step.sessionId }, baselineDeps)
  }
  // 仅故障生命周期内可走此恢复分支；启动失败时没有重启前PID，仍必须恢复原出口。
  await baselineDeps.runLocal(['ai-service'])
  const health = await deps.waitReady('ai-service')
  const after = await deps.snapshot(9004)
  if (!after?.pid || !after.startedAt) throw new Error('恢复AI基线后缺PID/StartTime证据')
  return { ready: Boolean(health?.ready ?? health), restoredFromStopped: true, afterPid: after.pid, afterStartTime: after.startedAt, health }
}

function createControls(options = {}) {
  const enabled = options.enabled ?? process.env.COMPLEX_EVAL_CONTROL_ENABLE === '1'
  const deps = {
    enabled,
    runLocal: options.runLocal ?? runLocalDefault,
    snapshot: options.snapshot ?? processSnapshotDefault,
    waitDown: options.waitDown ?? waitDownDefault,
    waitReady: options.waitReady ?? waitReadyDefault,
    stopOwnedProcess: options.stopOwnedProcess ?? stopOwnedProcessDefault,
  }
  deps.staging = options.staging ?? createProxyStaging(deps)
  return {
    restart: (payload) => restartImpl(payload, deps),
    fault: (payload) => faultImpl(payload, deps),
    collectOperationEvidence: (payload) => collectOperationEvidence(payload, options.logPaths),
  }
}

export { SERVICE_PORTS, RESTARTABLE, FAULT_FAILURES, SAFE_PATH_PREFIXES, createFaultProxy, listen, close, collectOperationEvidence, collectRetrievalEvidence, createControls, knowledgeIndexReady, restoreAiBaseline }
export const restart = (payload) => createControls().restart(payload)
export const fault = (payload) => createControls().fault(payload)
