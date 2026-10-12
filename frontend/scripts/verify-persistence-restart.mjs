/**
 * 只读持久化重启验收。脚本不调用模型、不重启服务、不写业务数据。
 * node scripts/verify-persistence-restart.mjs --capture
 * 联合重启 ai-service、knowledge-service、前端后：
 * node scripts/verify-persistence-restart.mjs --compare
 * 必要测试反馈可在主流程串行协调时单独创建：
 * node scripts/verify-persistence-restart.mjs --seed --seed-session=t1-... --seed-message=...
 * seed只为已有测试会话写一条明确测试反馈与审核标注，不产生新对话。
 * 默认基线落在已忽略的 frontend/.evidence/，只存稳定编号、摘要和哈希。
 * 无阶段参数只检查静态契约，必须明确报告真实重启未覆盖。
 */
import { readFile, writeFile, mkdir } from 'node:fs/promises'
import { execFileSync } from 'node:child_process'
import { dirname, resolve, relative, isAbsolute } from 'node:path'
import { fileURLToPath } from 'node:url'
import { readFixture, printContractSummary } from './lib/verify-util.mjs'
import { digest, validReport, reportEvidence, messagesEvidence, restoredMessages, restarted, readOnlyRpc, rpcResult, seedMessage } from './lib/persistence-evidence.mjs'

const HERE = dirname(fileURLToPath(import.meta.url))
const ROOT = resolve(HERE, '..')
const GW = process.env.VERIFY_GW ?? 'http://localhost:8080'
const BASE = process.env.VERIFY_BASE ?? 'http://localhost:5173'
const MCP = process.env.VERIFY_MCP ?? 'http://localhost:9004'
const args = process.argv.slice(2)
const phase = args.includes('--seed') ? 'seed' : args.includes('--capture') ? 'capture' : args.includes('--compare') ? 'compare' : 'contract'
const custom = args.find((arg) => arg.startsWith('--baseline='))?.slice('--baseline='.length)
const path = custom ? resolve(ROOT, custom) : resolve(ROOT, '.evidence/persistence-restart-baseline.json')
let pass = 0
let fail = 0
const uncovered = []
const requests = []
function ck(name, ok, detail = '') {
  console.log(`  ${ok ? '通过' : '失败'} ${name}${detail ? '：' + detail : ''}`)
  if (ok) pass++
  else fail++
}
function gap(name) { uncovered.push(name); console.log('  未覆盖 ' + name) }
async function api(endpoint, token, body, method = body === undefined ? 'GET' : 'POST', { allow404 = false } = {}) {
  // 唯一写协议是认证；禁止把评测、聊天、索引重建加入只读验收。
  const seedWrite = phase === 'seed' && (
    (method === 'PUT' && /^\/ai\/sessions\/[^/]+\/messages\/[^/]+\/bad-case$/.test(endpoint)) ||
    (method === 'POST' && /^\/ai\/admin\/bad-cases\/[^/]+\/(review\?status=REVIEWED|test-case)$/.test(endpoint)))
  if (method !== 'GET' && endpoint !== '/auth/login' && !seedWrite) throw new Error('只读验收拒绝写接口')
  let response
  for (let attempt = 0; attempt < 3; attempt++) {
    const startedAt = Date.now()
    response = await fetch(GW + endpoint, {
      method, signal: AbortSignal.timeout(20000),
      headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: 'Bearer ' + token } : {}) },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    })
    requests.push({ method, path: endpoint.split('?')[0], status: response.status, attempt: attempt + 1, durationMs: Date.now() - startedAt })
    if (method !== 'GET' || response.status !== 429 || attempt === 2) break
    const retryAfter = Number(response.headers.get('retry-after'))
    if (retryAfter > 10) break
    const delay = Number.isFinite(retryAfter) && retryAfter > 0 ? retryAfter * 1000 : 1500
    console.log(`  限流退避 GET ${endpoint.split('?')[0]} 等待=${delay}ms 尝试=${attempt + 1}/3`)
    await response.body?.cancel()
    await new Promise((resolve) => setTimeout(resolve, delay))
  }
  const value = await response.json()
  if (allow404 && response.status === 200 && value.code === 404) return null
  if (!response.ok || value.code !== 200) throw new Error(`接口失败 ${method} ${endpoint.split('?')[0]} HTTP=${response.status} code=${value.code}`)
  return value.data
}
async function token(role) {
  const provided = process.env[role === 'admin' ? 'VERIFY_ADMIN_TOKEN' : 'VERIFY_USER_TOKEN']
  if (provided) return provided
  const value = await api('/auth/login', null, { username: role, password: '123456' })
  if (!value?.token) throw new Error('既有账号认证失败')
  return value.token
}
async function toolRegistry(user) {
  let sessionId
  async function rpc(body) {
    if (!readOnlyRpc(body.method)) throw new Error('持久化验收禁止执行MCP工具')
    requests.push({ method: 'POST', path: '/mcp', rpcMethod: body.method })
    const response = await fetch(MCP + '/mcp', {
      method: 'POST', signal: AbortSignal.timeout(20000),
      headers: { 'Content-Type': 'application/json', Accept: 'application/json, text/event-stream', Authorization: 'Bearer ' + user,
        ...(sessionId ? { 'mcp-session-id': sessionId } : {}) }, body: JSON.stringify(body),
    })
    sessionId = response.headers.get('mcp-session-id') ?? sessionId
    const text = await response.text()
    const json = rpcResult(text)
    if (!response.ok || json?.error) throw new Error('MCP只读工具表查询失败')
    return json?.result
  }
  let toolsResult
  let primaryError
  let releaseError
  try {
    const initialized = await rpc({ jsonrpc: '2.0', id: 1, method: 'initialize', params: {
      protocolVersion: '2025-06-18', capabilities: {}, clientInfo: { name: 'verify-persistence-restart', version: '1.0.0' },
    } })
    if (!initialized?.serverInfo || !sessionId) throw new Error('MCP初始化缺少会话证据')
    await rpc({ jsonrpc: '2.0', method: 'notifications/initialized' })
    const { tools } = await rpc({ jsonrpc: '2.0', id: 2, method: 'tools/list' })
    if (!Array.isArray(tools) || !tools.length) throw new Error('MCP注册工具表为空')
    toolsResult = tools.map((value) => ({ name: value.name, digest: digest(value) })).sort((a, b) => a.name.localeCompare(b.name))
  } catch (error) {
    primaryError = error
  } finally {
    if (sessionId) {
      // 关闭本次只读查询的传输会话，避免留存测试客户端资源；不调用任何业务工具。
      try {
        const response = await fetch(MCP + '/mcp', { method: 'DELETE', signal: AbortSignal.timeout(20000),
          headers: { Authorization: 'Bearer ' + user, 'mcp-session-id': sessionId } })
        requests.push({ method: 'DELETE', path: '/mcp', status: response.status })
        if (!response.ok) releaseError = new Error('MCP测试传输会话未释放 HTTP=' + response.status)
      } catch (error) { releaseError = error }
    }
  }
  if (primaryError && releaseError) throw new AggregateError([primaryError, releaseError], '工具表读取和会话释放均失败')
  if (primaryError) throw primaryError
  if (releaseError) throw releaseError
  return toolsResult
}
async function seed(admin, user) {
  const sessionId = args.find((arg) => arg.startsWith('--seed-session='))?.slice('--seed-session='.length)
  const messageId = args.find((arg) => arg.startsWith('--seed-message='))?.slice('--seed-message='.length)
  if (!sessionId?.startsWith('t1-') || !messageId) throw new Error('seed必须明确指定独立t1-测试会话与稳定助手消息编号')
  const messages = await api(`/ai/sessions/${encodeURIComponent(sessionId)}/messages`, user)
  const message = seedMessage(sessionId, messageId, messages)
  if (!message) throw new Error('测试会话没有指定的已持久化助手响应，不会触发模型造数据')
  const endpoint = `/ai/sessions/${encodeURIComponent(sessionId)}/messages/${encodeURIComponent(messageId)}/bad-case`
  let value = await api(endpoint, user, undefined, 'GET', { allow404: true })
  if (!value) value = await api(endpoint, user, { reasonCodes: ['OTHER'], selectedMessageIds: [], comment: 'T1联合重启持久化验收测试记录，不代表用户真实投诉。' }, 'PUT')
  if (value.status === 'ACTIVE') value = await api(`/ai/admin/bad-cases/${value.badCaseId}/review?status=REVIEWED`, admin, null)
  if (value.status === 'REVIEWED') value = await api(`/ai/admin/bad-cases/${value.badCaseId}/test-case`, admin, {
    evalKind: 'ANSWERABLE', expectRefuse: false, mustMention: [],
    expectedTools: (message.response.toolCalls ?? []).map((tool) => tool.tool),
    annotation: 'T1只验证反馈持久化与审核标注，不执行追加集模型评测。',
  })
  ck('明确测试反馈、审核状态与追加集标注已持久化', value.status === 'IN_TEST_SET' && Boolean(value.testCaseId), 'badCaseId=' + value.badCaseId)
  return { version: 1, seededAt: new Date().toISOString(), badCaseId: value.badCaseId, status: value.status, testCaseId: value.testCaseId, requests }
}
function processes() {
  if (process.platform !== 'win32') return null
  const script = '$result = @{}; foreach ($port in @(9004, 9008, 5173)) { $connection = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1; if ($connection) { $process = Get-Process -Id $connection.OwningProcess; $result[[string]$port] = @{ pid = $process.Id; startedAt = $process.StartTime.ToUniversalTime().ToString("o") } } }; $result | ConvertTo-Json -Compress'
  return JSON.parse(execFileSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', script], { encoding: 'utf8', timeout: 20000 }))
}
async function frontend() {
  const response = await fetch(BASE, { signal: AbortSignal.timeout(20000) })
  const html = await response.text()
  if (!response.ok || !html.includes('id="app"')) throw new Error('前端应用入口不可用')
  const sources = [...html.matchAll(/<script\b[^>]*\bsrc="([^"]+)"/g)].map((match) => match[1])
  if (!sources.length) throw new Error('前端入口没有脚本资源')
  for (const source of sources) {
    const url = new URL(source, BASE)
    if (url.origin !== new URL(BASE).origin) throw new Error('前端资源不是同源地址')
    const asset = await fetch(url, { signal: AbortSignal.timeout(20000) })
    if (!asset.ok || !(await asset.text()).trim()) throw new Error('前端脚本资源不可读')
  }
  return { scripts: sources, status: response.status }
}
async function capture(admin, user) {
  const grounding = (await api('/ai/eval/grounding/report')).live
  const retrieval = await api('/ai/eval/retrieval/report')
  ck('回答快照已完成，36条明细与指标分母一致', validReport(grounding, 'grounding'))
  ck('检索快照已完成，70条明细与指标分母一致', validReport(retrieval, 'retrieval'))
  if (fail) throw new Error('评测快照不满足基线契约')
  const histories = []
  for (const session of (await api('/ai/sessions', user)).slice(0, 20)) {
    const messages = await api(`/ai/sessions/${encodeURIComponent(session.sessionId)}/messages`, user)
    if (!messages.some((message) => message.id && message.role === 'assistant' && message.response)) continue
    const history = { sessionId: session.sessionId, messages: messagesEvidence(messages) }
    const hasTools = history.messages.some((message) => message.toolCount > 0)
    if (histories.length < 2) histories.push(history)
    else if (hasTools && !histories.some((item) => item.messages.some((message) => message.toolCount > 0))) histories[1] = history
    // 最近两轮可能只是闲聊；基线必须实际选中工具轨迹，不能据选样缺失认定历史没有工具。
    if (histories.length === 2 && histories.some((item) => item.messages.some((message) => message.toolCount > 0))) break
  }
  ck('捕获既有会话稳定消息编号及完整响应哈希', histories.length > 0)
  const badCases = (await api('/ai/admin/bad-cases?size=100', admin)).slice(0, 5)
    .map((value) => ({ id: value.badCaseId, status: value.status, digest: digest(value) }))
  const fixtureRows = await api('/ai/admin/bad-cases/fixtures?limit=1000', admin)
  const fixtures = fixtureRows.filter((value) => badCases.some((feedback) => feedback.id === value.sourceBadCaseId))
    .map((value) => ({ caseId: value.caseId, sourceBadCaseId: value.sourceBadCaseId, digest: digest(value) }))
  if (badCases.length) ck('BadCase审核状态及已纳入追加集的标注完整', badCases.every((value) =>
    value.status !== 'IN_TEST_SET' || fixtures.some((fixture) => fixture.sourceBadCaseId === value.id)))
  if (!badCases.length) {
    gap('BadCase历史恢复：现有管理员列表为空，未创建测试反馈')
    ck('BadCase存在可重启复核的既有记录', false)
  }
  const failures = await api('/knowledge/admin/graph/failures?limit=1000', admin)
  const diagnostics = []
  for (const value of failures.slice(-5)) {
    const detail = await api(`/knowledge/admin/graph/failures/${value.id}`, admin)
    diagnostics.push({ id: detail.id, batchId: detail.batchId, stage: detail.stage, reasonCode: detail.reasonCode, digest: digest(detail) })
  }
  const batches = []
  for (const batchId of new Set(diagnostics.map((value) => value.batchId))) {
    batches.push({ batchId, stats: await api('/knowledge/admin/graph/failures/stats?batchId=' + encodeURIComponent(batchId), admin) })
  }
  if (!diagnostics.length) { gap('图谱诊断历史恢复：没有既有失败记录'); ck('图谱存在可重启复核的历史诊断', false) }
  const tools = await toolRegistry(user)
  ck('MCP权威工具注册表存在15个唯一工具，未执行工具', tools.length === 15 && new Set(tools.map((tool) => tool.name)).size === 15)
  const toolCount = histories.reduce((count, history) => count + history.messages.reduce((n, message) => n + message.toolCount, 0), 0)
  if (!toolCount) { gap('历史工具状态恢复：选中会话没有工具轨迹'); ck('历史工具状态存在可复核轨迹', false) }
  const processIdentity = processes()
  if (!processIdentity || [9004, 9008, 5173].some((port) => !processIdentity[port])) { gap('联合重启进程证据：三个本地监听进程未全部读取'); ck('联合重启进程基线完整', false) }
  const front = await frontend()
  ck('前端入口和同源脚本资源可读', true)
  return { version: 1, valid: fail === 0 && uncovered.length === 0, capturedAt: new Date().toISOString(), gateway: GW, frontendBase: BASE,
    grounding: reportEvidence(grounding), retrieval: reportEvidence(retrieval), histories,
    badCases, fixtures, diagnostics, batches, toolCount, tools, mcpBase: MCP, processes: processIdentity, frontend: front, uncovered, requests }
}
async function compare(before, admin, user) {
  if (before.version !== 1 || !before.valid || before.gateway !== GW || before.frontendBase !== BASE || before.mcpBase !== MCP || !before.histories?.length || !before.badCases?.length || !before.diagnostics?.length || before.tools?.length !== 15) throw new Error('基线版本、目标地址或必要持久化数据不完整，禁止伪通过')
  const currentProcesses = processes()
  for (const [port, name] of [[9004, 'ai-service'], [9008, 'knowledge-service'], [5173, '前端']]) {
    ck(name + '进程已真实重启', restarted(before.processes?.[port], currentProcesses?.[port]))
  }
  for (const [kind, endpoint] of [['grounding', '/ai/eval/grounding/report'], ['retrieval', '/ai/eval/retrieval/report']]) {
    const data = await api(endpoint)
    const value = kind === 'grounding' ? data.live : data
    ck(kind + '快照状态、runId、指标与明细原样恢复', validReport(value, kind) && digest(reportEvidence(value)) === digest(before[kind]), 'runId=' + (value?.runId ?? '无'))
  }
  const sessions = await api('/ai/sessions', user)
  for (const history of before.histories) {
    const messages = messagesEvidence(await api(`/ai/sessions/${encodeURIComponent(history.sessionId)}/messages`, user))
    ck('历史会话与稳定消息内容恢复', sessions.some((session) => session.sessionId === history.sessionId) && restoredMessages(history.messages, messages), 'sessionId=' + history.sessionId + ' 消息=' + history.messages.length)
  }
  for (const value of before.badCases) {
    const data = await api('/ai/admin/bad-cases/' + encodeURIComponent(value.id), admin)
    ck('BadCase稳定编号、状态及反馈内容恢复', data.badCaseId === value.id && data.status === value.status && digest(data) === value.digest)
  }
  const fixtures = await api('/ai/admin/bad-cases/fixtures?limit=1000', admin)
  for (const value of before.fixtures) {
    const data = fixtures.find((fixture) => fixture.caseId === value.caseId)
    ck('BadCase追加集编号与审核标注原样恢复', Boolean(data) && digest(data) === value.digest, 'caseId=' + value.caseId)
  }
  for (const value of before.diagnostics) {
    const data = await api('/knowledge/admin/graph/failures/' + value.id, admin)
    ck('图谱历史诊断编号和内容恢复', data.id === value.id && digest(data) === value.digest, 'id=' + value.id)
  }
  for (const batch of before.batches) {
    const stats = await api('/knowledge/admin/graph/failures/stats?batchId=' + encodeURIComponent(batch.batchId), admin)
    ck('固定历史图谱批次统计恢复', digest(stats) === digest(batch.stats), 'batchId=' + batch.batchId)
  }
  const tools = await toolRegistry(user)
  ck('MCP工具清单、描述与参数契约重启后原样恢复', digest(tools) === digest(before.tools), '工具=' + tools.length)
  const currentStats = await api('/knowledge/admin/graph/failures/stats', admin)
  console.log(`  状态摘要 当前图谱诊断=${currentStats.failed} 批次=${currentStats.batches}；当前列表允许启动新增，历史按固定编号/批次比较`)
  await frontend()
  ck('联合重启后前端入口和脚本资源可读', true)
  for (const value of before.uncovered) gap(value)
  return { version: 1, baselineCapturedAt: before.capturedAt, comparedAt: new Date().toISOString(), pass, fail, uncovered, requests, processes: currentProcesses, currentGraphStats: currentStats }
}

try {
  if (args.filter((arg) => ['--capture', '--compare', '--seed'].includes(arg)).length > 1) throw new Error('capture、compare与seed不能同时执行')
  if (phase === 'contract') {
    const fixture = await readFixture('failure-recovery-scenarios.json')
    ck('夹具包含重启恢复场景', fixture.scenarios.some((item) => item.failure === 'SERVICE_RESTART'))
    ck('快照损坏不会伪装成未运行', fixture.scenarios.find((item) => item.failure === 'SNAPSHOT_CORRUPTED')?.expected.includes('不伪装'))
    gap('真实联合重启：运行 --capture，联合重启三进程，再运行 --compare')
  } else {
    const local = relative(resolve(ROOT, '.evidence'), path)
    if (!local || local.startsWith('..') || isAbsolute(local)) throw new Error('基线只能写入frontend/.evidence/，避免提交运行期数据')
    const before = phase === 'compare' ? JSON.parse(await readFile(path, 'utf8')) : null
    const admin = await token('admin')
    const user = await token('alice')
    const result = phase === 'seed' ? await seed(admin, user) : phase === 'capture' ? await capture(admin, user) : await compare(before, admin, user)
    await mkdir(dirname(path), { recursive: true })
    const output = phase === 'capture' ? path : path.replace(/\.json$/, '') + (phase === 'seed' ? '-seed.json' : '-comparison.json')
    await writeFile(output, JSON.stringify(result, null, 2) + '\n', 'utf8')
    console.log('  脱敏证据 ' + output)
    if (phase === 'capture') console.log(`  状态 基线${result.valid ? '有效' : '不完整，不可用于重启通过声明'}；真实联合重启尚未验收`)
  }
} catch (error) {
  ck('持久化验收执行', false, error.message)
}
printContractSummary('持久化重启' + (phase === 'seed' ? '测试记录准备' : phase === 'contract' ? '静态契约' : phase === 'capture' ? '基线捕获' : '真实比较'), pass, fail,
  `未覆盖=${uncovered.length} 模式=${phase}；未覆盖不计通过，capture不代表重启通过`)
if (phase === 'compare' && !fail && uncovered.length) process.exitCode = 2
