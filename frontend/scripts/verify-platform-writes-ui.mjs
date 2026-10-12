/** 页面主写真跑：真实点击、真实HTTP和权威读回；全部夹具属于本轮独立账号。 */
import { chromium } from 'playwright-core'
import { randomUUID, createHash } from 'node:crypto'
import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { assert, rows, redact, verifyPageWriteEvidence, verifyOwnedDocumentFixture, parseSseDone, installBrowserSseCapture } from './lib/public-api-evidence.mjs'
import { poll } from './lib/verify-util.mjs'
import { captureFixtureSearch, confirmFixtureSearch, cleanupFixtureSearches } from './lib/fixture-hot-keywords.mjs'

const ROOT = fileURLToPath(new URL('../', import.meta.url))
const args = process.argv.slice(2)
const compare = args.find((arg) => arg.startsWith('--compare='))?.slice(10)
assert(args.every((arg) => arg === '--capture' || arg === '--cleanup-authorized' || arg === '--simulate-failure-after-shop' || arg === '--model-window' || arg.startsWith('--model-source=') || arg.startsWith('--compare=') || arg.startsWith('--only=')), '仅支持capture/compare/only/cleanup-authorized/simulate-failure-after-shop/model-window/model-source')
assert(!(args.includes('--capture') && compare), 'capture与compare不能同时运行')
if (compare) {
  const path = resolve(ROOT, compare)
  assert(path.startsWith(resolve(ROOT, '.evidence') + '\\') || path.startsWith(resolve(ROOT, '.evidence') + '/'), 'compare仅允许忽略证据目录')
  const report = JSON.parse(await readFile(path, 'utf8'))
  assert(['LIVE_REAL_PAGE_WRITE', 'LIVE_REAL_PAGE_WRITE_UNION'].includes(report.mode) && report.runId && report.results.length, '不是完整真实页面写捕获')
  assert(!report.state?.cleanupFailure, '原捕获收尾失败，不得声称夹具已隔离')
  assert(report.expectedRoutes?.length === report.results.length && report.expectedRoutes.every((id) => report.results.filter((result) => result.routeId === id).length === 1), '捕获缺预期页面或包含重复页面，不能接受中断报告')
  for (const result of report.results) {
    if (report.mode === 'LIVE_REAL_PAGE_WRITE_UNION') {
      const originalPath = resolve(ROOT, result.source.path)
      assert(originalPath.startsWith(resolve(ROOT, '.evidence') + '\\') || originalPath.startsWith(resolve(ROOT, '.evidence') + '/'), '原页面证据必须位于忽略目录')
      const original = JSON.parse(await readFile(originalPath, 'utf8'))
      assert(original.runId === result.source.runId && original.mode === 'LIVE_REAL_PAGE_WRITE', '原页面run或模式不符')
      const captured = original.results.find((entry) => entry.routeId === result.routeId)
      assert(captured && JSON.stringify(captured.clicks) === JSON.stringify(result.clicks) && JSON.stringify(captured.authority) === JSON.stringify(result.authority), '页面点击/权威请求不能从别处补造')
      assert(verifyPageWriteEvidence(captured), '原页面记录未通过：' + result.routeId)
    }
    for (const click of result.clicks ?? []) if (click.source) {
      const sourcePath = resolve(ROOT, click.source.path)
      assert(sourcePath.startsWith(resolve(ROOT, '.evidence') + '\\') || sourcePath.startsWith(resolve(ROOT, '.evidence') + '/'), '恢复点击必须引用忽略目录原报告')
      const source = JSON.parse(await readFile(sourcePath, 'utf8'))
      const captured = source.results?.flatMap((entry) => entry.clicks ?? []).find((entry) => entry.requestId === click.requestId)
      const { source: _source, ...originalClick } = click
      assert(source.runId === click.source.runId && source.mode === 'LIVE_REAL_PAGE_WRITE' && captured && JSON.stringify(captured) === JSON.stringify(originalClick), '恢复请求必须逐字段匹配原真实点击，不得重写原失败报告')
    }
    assert(verifyPageWriteEvidence(result), '页面主写逐点击及权威GET证据未兑现：' + result.routeId)
    assert(!result.errors?.length, '页面异常：' + result.routeId)
  }
  console.log('真实页面主写原捕获比较：' + report.results.length + ' PASS；新写/模型0；原报告=' + path)
  process.exit(0)
}
if (!args.includes('--capture')) { console.log('BLOCKED 缺阶段：使用--capture真实自有页面动作或--compare=原报告；默认不写业务'); process.exit(2) }
assert(args.includes('--cleanup-authorized'), '缺自有夹具停用授权：capture必须在本任务获授权后显式传--cleanup-authorized；不得留下公开类目/品牌')
const BASE = process.env.VERIFY_BASE ?? 'http://127.0.0.1:5173'
const GW = process.env.VERIFY_GW ?? 'http://127.0.0.1:8080'
const runId = randomUUID()
const prefix = 't1ui_' + runId.slice(0, 8)
const directory = resolve(ROOT, '.evidence', 'platform-writes-' + runId)
const only = process.argv.find((arg) => arg.startsWith('--only='))?.slice(7).split(',')
const writeRoutes = ['register', 'login', 'admin-catalog', 'profile', 'admin-product-create', 'admin-product-edit', 'admin-products', 'shop', 'product-detail', 'cart', 'coupons', 'favorites', 'checkout', 'payment', 'orders', 'tickets', 'admin-tickets', 'ticket-detail', 'admin-users', 'admin-bad-cases', 'after-sales', 'order-detail', 'admin-orders', 'admin-reviews', 'admin-after-sales', 'assistant', 'admin-knowledge', 'admin-evaluations']
assert(!only || only.every((id) => writeRoutes.includes(id)), 'only包含非主写页面')
const expectedRoutes = only ?? writeRoutes
const simulateFailure = args.includes('--simulate-failure-after-shop')
assert(!simulateFailure || only?.length === 2 && only.includes('admin-catalog') && only.includes('shop'), '受控异常只允许独立catalog/shop最小收尾验收')
const modelWindow = args.includes('--model-window')
const modelSource = args.find((arg) => arg.startsWith('--model-source='))?.slice(15)
assert(!only?.every((id) => ['assistant', 'admin-knowledge', 'admin-evaluations'].includes(id)) || modelWindow, '模型页面选择必须显式--model-window，禁止准备普通商品类目夹具')
assert(!modelWindow || only?.length > 0 && only.every((id) => ['assistant', 'admin-knowledge', 'admin-evaluations'].includes(id)), '模型窗口必须明确选择模型页面，不创建商品类目或触发普通页面写入')
assert(!modelSource || modelWindow && only?.length === 1 && (only.includes('assistant') || only.includes('admin-evaluations')), '原模型窗口证据只用于恢复原评测读取或独立助手页')
const startedAt = new Date().toISOString()
const sourceVersion = createHash('sha256').update(await readFile(new URL(import.meta.url))).digest('hex')
const password = 'T1' + randomUUID().replaceAll('-', '').slice(0, 16)
const tokens = {}; const users = {}; const state = {}; const results = []; const apiCalls = []
state.fixtureSearchLedger = []
let lastAiRequestAt = 0
const browser = await chromium.launch({ executablePath: process.env.PLAYWRIGHT_CHROMIUM ?? 'C:/Program Files/Google/Chrome/Application/chrome.exe' })
const contexts = []
await mkdir(directory, { recursive: true })
async function api(path, { method = 'GET', body, role = 'buyer', readAttempt = 0 } = {}) {
  if (path.startsWith('/ai/')) {
    const delay = 1100 - (Date.now() - lastAiRequestAt)
    if (delay > 0) await new Promise((done) => setTimeout(done, delay))
    lastAiRequestAt = Date.now()
  }
  const requestId = randomUUID()
  captureFixtureSearch(state.fixtureSearchLedger, path, prefix, requestId)
  const response = await fetch(GW + path, { method, headers: { 'Content-Type': 'application/json', 'X-Request-Id': requestId, ...(tokens[role] ? { Authorization: 'Bearer ' + tokens[role] } : {}) }, ...(body === undefined ? {} : { body: JSON.stringify(body) }), signal: AbortSignal.timeout(30000) })
  const payload = await response.json()
  confirmFixtureSearch(state.fixtureSearchLedger, requestId, payload.code)
  apiCalls.push({ method, path, requestId, httpStatus: response.status, code: payload.code, data: redact(payload.data), role, capturedAt: new Date().toISOString() })
  if (method === 'GET' && payload.code === 429 && readAttempt < 3) {
    console.log('READ_RATE_LIMIT_RETRY ' + path + ' attempt=' + (readAttempt + 1) + ' requestId=' + requestId)
    await new Promise((done) => setTimeout(done, 1200))
    return api(path, { method, role, readAttempt: readAttempt + 1 })
  }
  assert(payload.code === 200, method + ' ' + path + ' code=' + payload.code + ' ' + payload.msg)
  return payload.data
}
async function pageFor(role) {
  const context = await browser.newContext({ viewport: { width: 1440, height: 1200 }, reducedMotion: 'reduce' })
  contexts.push(context)
  await installBrowserSseCapture(context)
  if (tokens[role]) await context.addInitScript((session) => localStorage.setItem('user', JSON.stringify(session)), { token: tokens[role], profile: users[role] })
  const page = await context.newPage()
  page.__requests = []; page.__errors = []
  await page.route(/\/products\/search(?:\?|$)/, async (route) => {
    const request = route.request()
    const id = request.headers()['x-request-id']
    captureFixtureSearch(state.fixtureSearchLedger, request.url(), prefix, id)
    await route.continue()
  })
  page.on('request', (request) => { if (new URL(request.url()).pathname.startsWith('/ai/')) lastAiRequestAt = Date.now() })
  page.on('response', async (response) => {
    const request = response.request(); const path = new URL(request.url()).pathname
    if (path === '/products/search') {
      let payload
      try { payload = await response.json() } catch { payload = null }
      confirmFixtureSearch(state.fixtureSearchLedger, request.headers()['x-request-id'], payload?.code)
    }
    if (request.method() === 'GET' || !/^\/(auth|products|categories|brands|cart|orders|payments|after-sales|tickets|reviews|favorites|coupons|knowledge|ai)(\/|$)/.test(path)) return
    let payload
    try {
      if (response.headers()['content-type']?.includes('text/event-stream')) {
        const cdp = parseSseDone(await response.body())
        const captured = await poll(() => page.evaluate((requestId) => window.__verifyLiveSse?.find((entry) => entry.data?.requestId === requestId), cdp?.requestId), Boolean, { timeoutMs: 10000, intervalMs: 50 })
        const done = captured?.data
        payload = done ? { code: 200, data: done } : null
      } else payload = await response.json()
    } catch { payload = null }
    page.__requests.push({ method: request.method(), path, requestId: request.headers()['x-request-id'] ?? response.headers()['x-request-id'] ?? payload?.data?.requestId ?? null, httpStatus: response.status(), code: payload?.code, data: redact(payload?.data), rawData: payload?.data, capturedAt: new Date().toISOString() })
  })
  page.on('pageerror', (error) => page.__errors.push(error.message))
  return page
}
async function goto(page, path) {
  await page.goto(BASE + '/#' + path, { waitUntil: 'domcontentloaded' })
  // hash导航不会在所有浏览器路径触发router监听；刷新加载对应真实页面，避免点击旧页面按钮。
  await page.reload({ waitUntil: 'domcontentloaded' })
  await page.locator('h1,h2').first().waitFor({ timeout: 15000 })
  await poll(() => page.locator('.el-loading-mask:visible,.el-skeleton:visible').count(), (count) => count === 0, { timeoutMs: 15000, intervalMs: 100 })
}
async function clickWrite(page, method, path, action, timeoutMs = 30000) {
  const start = page.__requests.length
  await action()
  const result = await poll(() => page.__requests.slice(start).find((request) => request.method === method && request.path === path), Boolean, { timeoutMs, intervalMs: 100 })
  assert(result?.code === 200 && result.httpStatus === 200 && result.requestId, '真实页面主写没有成功HTTP/业务回执：' + method + ' ' + path)
  return result
}
async function messageBox(page, text) {
  const box = page.locator('.el-message-box:visible').last()
  await box.waitFor()
  if (text) await box.locator('input,textarea').first().fill(text)
  await box.locator('.el-message-box__btns button').last().click()
}
async function receivedOrderFixture() {
  if (!state.order) {
    if (!(await api('/cart')).length) await api('/cart/items', { method: 'POST', body: { skuId: state.sku, quantity: 1 } })
    state.order = await api('/orders/checkout', { method: 'POST', body: { receiverName: '页面验收', receiverPhone: '13800000000', receiverProvince: '上海市', receiverCity: '上海市', receiverDistrict: '浦东新区', receiverDetail: '独立页面测试地址', requestId: randomUUID() } })
    await api('/payments', { method: 'POST', body: { orderId: state.order.id, channel: 'MOCK' } })
    await api('/payments/' + state.order.id + '/mock-pay', { method: 'POST' })
    await poll(() => api('/orders/' + state.order.id), (order) => order.status === 'PAID', { timeoutMs: 30000 })
  }
  if ((await api('/orders/' + state.order.id)).status === 'PAID') await api('/orders/admin/orders/' + state.order.id + '/ship', { method: 'POST', role: 'admin', body: { carrierCode: 'SF', carrierName: '顺丰速运', trackingNo: prefix + '-UI' } })
  if ((await api('/orders/' + state.order.id)).status === 'SHIPPED') await api('/orders/' + state.order.id + '/receive', { method: 'POST' })
  return api('/orders/' + state.order.id)
}
async function save() {
  const endpoints = state.manualApiChecks ? Object.entries(state.manualApiChecks).map(([key, checks]) => ({ key, status: 'PASS', checks })) : undefined
  const calls = endpoints ? [...new Map([...apiCalls, ...results.flatMap((result) => result.clicks)].map((call) => [call.requestId, {
    key: call.method + ' ' + call.path.split('?')[0].replace(/(\/ai\/admin\/knowledge\/reindex)\/[^/]+$/, '$1/{docNo}'),
    path: call.path, requestId: call.requestId, httpStatus: call.httpStatus, code: call.code, response: call.data,
  }])).values()] : undefined
  await writeFile(resolve(directory, 'report.json'), JSON.stringify({ runId, mode: 'LIVE_REAL_PAGE_WRITE', sourceVersion, startedAt, updatedAt: new Date().toISOString(), expectedRoutes, results, state: redact(state), apiCalls, endpoints, calls, limits: ['API准备夹具不计页面点击', '不拦截写请求或模拟成功', modelWindow ? '本轮显式模型窗口，四入口顺序真实点击，不自动重试' : '默认不执行知识/评测模型入口', '只改本轮自有账号/商品/业务数据'] }, null, 2) + '\n')
}
async function step(routeId, page, name, action) {
  if (only && !only.includes(routeId)) return
  const before = apiCalls.length
  const result = { routeId, state: 'write', name, status: 'FAIL', clicks: [], authority: [] }
  const start = page?.__requests.length ?? 0
  try {
    await action()
    result.status = 'PASS'
    result.screenshot = resolve(directory, routeId + '.png')
    if (page) await page.screenshot({ path: result.screenshot, fullPage: true })
  } catch (error) {
    result.reason = error.message
    if (page) { result.screenshot = resolve(directory, routeId + '-failure.png'); await page.screenshot({ path: result.screenshot, fullPage: true }).catch(() => {}) }
  }
  result.clicks = (page?.__requests.slice(start) ?? []).map(({ rawData: _rawData, ...request }) => request)
  result.authority = apiCalls.slice(before)
  result.errors = page?.__errors ?? []
  if (result.errors.length) result.status = 'FAIL'
  results.push(result)
  console.log(result.status + ' ' + routeId + ' ' + name + (result.reason ? ' ' + result.reason : ''))
  await save()
}
let buyer; let admin
async function runModelPages() {
  let previousCapture
  if (modelSource) {
    const path = resolve(ROOT, modelSource)
    assert(path.startsWith(resolve(ROOT, '.evidence') + '\\') || path.startsWith(resolve(ROOT, '.evidence') + '/'), '原模型窗口必须位于忽略证据目录')
    previousCapture = JSON.parse(await readFile(path, 'utf8'))
    assert(previousCapture.mode === 'LIVE_REAL_PAGE_WRITE' && previousCapture.runId, '恢复必须引用原真实页面捕获')
  }
  const identity = await api('/auth/register', { method: 'POST', body: { username: prefix + 'm', password, nickname: '真实模型页面验收' }, role: 'anonymous' })
  tokens.buyer = identity.token; users.buyer = identity.user
  const adminIdentity = await api('/auth/login', { method: 'POST', body: { username: 'admin', password: '123456' }, role: 'anonymous' })
  tokens.admin = adminIdentity.token; users.admin = adminIdentity.user
  buyer = await pageFor('buyer'); admin = await pageFor('admin')
  state.manualApiChecks = {}
  const proved = (key, name, click) => {
    const authority = apiCalls.at(-1)
    assert(authority.method === 'GET' && authority.code === 200, '模型写缺权威读回')
    state.manualApiChecks[key] = [{ name, pass: true, requestIds: [click.requestId, authority.requestId] }]
  }
  await step('admin-knowledge', admin, '停用自有文档增量与全量索引真实点击', async () => {
    const source = JSON.parse(await readFile(resolve(ROOT, '.evidence/public-api-ddf4165c-42de-469b-94e4-a4f1a0119772.json'), 'utf8'))
    const created = source.calls.find((call) => call.key === 'POST /knowledge/admin/documents' && call.code === 200)
    assert(created?.response, '原真实POST自有文档归属缺失')
    const original = source.calls.filter((call) => call.key === 'GET /knowledge/documents/{docNo}' && call.code === 200 && call.response.docNo === created.response).at(-1)?.response
    assert(original?.title === 't1api_ddf4165c接口验收说明', '原真实POST标题不属于本任务')
    const ownership = { ownedDocNo: created.response, ownedDocTitle: original.title, ownedDocCreationRequestId: created.requestId }
    const current = await api('/knowledge/documents/' + ownership.ownedDocNo, { role: 'anonymous' })
    assert(verifyOwnedDocumentFixture(current, ownership, source.calls), '只能编辑原POST证明归属且status0的文档')
    state.modelDocNo = ownership.ownedDocNo
    const previous = await api('/ai/admin/knowledge/status', { role: 'admin' })
    assert(!previous.running, '正在索引，不得并行触发')
    await goto(admin, '/admin/knowledge')
    await admin.getByPlaceholder('标题或标签关键词').fill(current.title)
    await admin.getByRole('button', { name: '查询', exact: true }).click()
    await admin.locator('tbody tr').filter({ hasText: current.title }).getByRole('button', { name: '编辑', exact: true }).click()
    const drawer = admin.locator('.el-drawer:visible')
    await poll(() => drawer.locator('textarea').inputValue(), (text) => text === current.content, { timeoutMs: 30000 })
    const single = await clickWrite(admin, 'POST', '/ai/admin/knowledge/reindex/' + ownership.ownedDocNo, () => drawer.getByRole('button', { name: '保存', exact: true }).click(), 300000)
    assert(single.rawData.docNo === ownership.ownedDocNo && single.rawData.deletedOnly === true, '停用自有文档未走真实派生索引删除分支')
    const after = await api('/knowledge/documents/' + ownership.ownedDocNo, { role: 'anonymous' })
    assert(verifyOwnedDocumentFixture(after, ownership, source.calls) && after.content === current.content && after.title === current.title, '停用文档正文/状态被改变')
    proved('POST /ai/admin/knowledge/reindex/{docNo}', '原POST自有停用文档真实增量删除，权威正文状态保留', single)
    await drawer.waitFor({ state: 'hidden', timeout: 30000 })
    const full = await clickWrite(admin, 'POST', '/ai/admin/knowledge/reindex', () => admin.getByRole('button', { name: '重建索引', exact: true }).click())
    const finished = await poll(() => api('/ai/admin/knowledge/status', { role: 'admin' }), (status) => !status.running && Boolean(status.finishedAt) && status.finishedAt !== previous.finishedAt, { timeoutMs: 1200000, intervalMs: 3000 })
    assert(!finished.running && !finished.error && finished.finishedAt !== previous.finishedAt && finished.result?.documentCount > 0 && finished.result?.chunkCount > 0 && finished.result.graphAvailable && finished.result.graphStored > 0 && finished.result.graphFailed === 0 && finished.result.graphSkipped === 0, '全量重建/图谱未完成或失败，不得启动最终评测')
    state.indexResult = finished
    proved('POST /ai/admin/knowledge/reindex', '真实全量重建从本轮请求到新的完成时间，文档和切片非空', full)
  })
  await step('admin-evaluations', admin, '检索与回答两个模型入口顺序真实点击', async () => {
    if (previousCapture) {
      assert(verifyPageWriteEvidence(previousCapture.results.find((result) => result.routeId === 'admin-knowledge')), '恢复原评测缺索引真实页面完成证据')
      state.knowledgeSource = { runId: previousCapture.runId, path: modelSource }
      state.indexResult = previousCapture.state.indexResult
    } else assert(results.find((result) => result.routeId === 'admin-knowledge')?.status === 'PASS', '索引窗口未完成，不得触发评测')
    await goto(admin, '/admin/knowledge/evaluations')
    for (const kind of ['retrieval', 'grounding']) {
      const path = '/ai/eval/' + kind + '/report'
      const before = await api(path, { role: 'anonymous' })
      const capturedClick = previousCapture?.results.find((result) => result.routeId === 'admin-evaluations')?.clicks.find((click) => click.path === '/ai/admin/eval/' + kind + '/run' && click.code === 200 && click.requestId)
      const originalBefore = previousCapture?.apiCalls.find((call) => call.method === 'GET' && call.path === path && call.code === 200)
      const beforeData = capturedClick ? originalBefore?.data : before
      const previous = kind === 'grounding' ? beforeData?.live : beforeData
      assert(previous && previous.status !== 'RUNNING', '原评测基线缺失或仍在运行，不得重复触发')
      const card = admin.locator('.run-card').filter({ hasText: kind === 'retrieval' ? '检索质量评测' : '回答质量评测' })
      let started
      if (capturedClick) {
        started = { ...capturedClick, source: { runId: previousCapture.runId, path: modelSource } }
        admin.__requests.push(started)
        console.log('RESUME_CAPTURED_WRITE 不重复触发 ' + kind + ' requestId=' + started.requestId)
      } else started = await clickWrite(admin, 'POST', '/ai/admin/eval/' + kind + '/run', async () => { await card.getByRole('button', { name: '手动触发', exact: true }).click(); await messageBox(admin) })
      const report = await poll(() => api(path, { role: 'anonymous' }), (item) => {
        const current = kind === 'grounding' ? item.live : item
        return current.status === 'COMPLETED' && current.runId && current.runId !== previous.runId
      }, { timeoutMs: 2400000, intervalMs: 3000 })
      const current = kind === 'grounding' ? report.live : report
      assert(current.status === 'COMPLETED' && current.runId && current.runId !== previous.runId && current.cases.length > 0 && (!capturedClick || current.runId === capturedClick.data.runId), '本轮评测未完成或复用了旧run')
      if (kind === 'grounding') assert(current.failedCases === 0 && current.completedCases === current.totalCases && current.cases.length === current.totalCases && current.cases.every((item) => item.outcome && !item.error && item.requestId), '回答评测有失败或缺逐条真实请求/判定')
      else assert(current.cases.length === current.corpus.cases && current.cases.length === current.overall.caseCount && !current.error, '检索评测分母/完成状态不符')
      state[kind + 'RunId'] = current.runId
      state[kind + 'Summary'] = kind === 'grounding' ? current.metrics : current.overall
      proved('POST /ai/admin/eval/' + kind + '/run', '真实页面确认启动新run并完成全部逐条结果，未复用历史快照', started)
    }
  })
  await step('assistant', buyer, '助手真实发送、审批确认与工单权威落盘', async () => {
    if (modelSource) {
      const sourcePath = resolve(ROOT, modelSource)
      assert(sourcePath.startsWith(resolve(ROOT, '.evidence') + '\\') || sourcePath.startsWith(resolve(ROOT, '.evidence') + '/'), '原模型窗口报告必须位于忽略证据目录')
      const source = JSON.parse(await readFile(sourcePath, 'utf8'))
      let knowledge = source.results.find((result) => result.routeId === 'admin-knowledge')
      if (!knowledge && source.state.knowledgeSource) {
        const knowledgePath = resolve(ROOT, source.state.knowledgeSource.path)
        assert(knowledgePath.startsWith(resolve(ROOT, '.evidence') + '\\') || knowledgePath.startsWith(resolve(ROOT, '.evidence') + '/'), '原知识窗口必须位于忽略目录')
        const original = JSON.parse(await readFile(knowledgePath, 'utf8'))
        assert(original.runId === source.state.knowledgeSource.runId && original.mode === 'LIVE_REAL_PAGE_WRITE', '原知识窗口run不符')
        knowledge = original.results.find((result) => result.routeId === 'admin-knowledge')
      }
      assert(source.mode === 'LIVE_REAL_PAGE_WRITE' && verifyPageWriteEvidence(knowledge) && verifyPageWriteEvidence(source.results.find((result) => result.routeId === 'admin-evaluations')), '原模型窗口缺真实页面写入与权威终态')
      const retrieval = await api('/ai/eval/retrieval/report', { role: 'anonymous' })
      const grounding = await api('/ai/eval/grounding/report', { role: 'anonymous' })
      assert(retrieval.status === 'COMPLETED' && retrieval.runId === source.state.retrievalRunId && grounding.live.status === 'COMPLETED' && grounding.live.runId === source.state.groundingRunId, '当前评测快照与原模型窗口不一致')
      state.modelSource = { runId: source.runId, path: modelSource }
    } else assert(results.find((result) => result.routeId === 'admin-evaluations')?.status === 'PASS', '最终评测未完成，助手模型调用暂停')
    await goto(buyer, '/assistant')
    await buyer.locator('textarea').fill('请提交客服工单：分类OTHER，标题“' + prefix + '页面工单”，内容“独立助手页面点击验收”。请先产生待审批操作，等我明确确认后再执行。')
    const waiting = await clickWrite(buyer, 'POST', '/ai/chat/stream', () => buyer.getByRole('button', { name: '发送消息', exact: true }).click(), 300000)
    assert(waiting.rawData.sessionId && waiting.rawData.pendingActionDetails?.some((item) => item.tool === 'ticket_create' && item.arguments.title === prefix + '页面工单'), '助手没有绑定页面原意图审批')
    assert(waiting.rawData.approvalToken && waiting.rawData.turnId && waiting.rawData.assistantMessageId && waiting.rawData.usage?.totalTokens > 0, '待审批缺原turn/msg/令牌或真实模型用量')
    state.assistantPendingProof = { requestId: waiting.requestId, turnId: waiting.rawData.turnId, assistantMessageId: waiting.rawData.assistantMessageId, sessionId: waiting.rawData.sessionId,
      approvalFingerprint: createHash('sha256').update(waiting.rawData.approvalToken).digest('hex'), usage: waiting.rawData.usage }
    const before = rows(await api('/tickets?page=0&size=100'))
    assert(before.length === 0, '待确认时产生了工单副作用')
    const confirmed = await clickWrite(buyer, 'POST', '/ai/chat/stream', () => buyer.getByRole('button', { name: '确认执行', exact: true }).click(), 300000)
    const operation = confirmed.rawData.toolCalls?.find((tool) => tool.tool === 'ticket_create' && tool.success)
    assert(operation?.operationId && operation.operationState === 'COMMITTED', '真实确认缺稳定operationId与COMMITTED回执')
    state.assistantOperationProof = { requestId: confirmed.requestId, turnId: confirmed.rawData.turnId, assistantMessageId: confirmed.rawData.assistantMessageId, operationId: operation.operationId, operationState: operation.operationState, receiptReplayed: operation.receiptReplayed }
    const tickets = rows(await api('/tickets?page=0&size=100'))
    assert(tickets.length === 1 && tickets[0].title === prefix + '页面工单', '确认按钮没有只创建原意图的一张工单')
    const detail = await api('/tickets/' + tickets[0].id)
    assert(detail.ticket.id === tickets[0].id && detail.ticket.title === prefix + '页面工单' && detail.messages[0].content === '独立助手页面点击验收', '工单权威详情不等于原UI意图')
    const messages = await api('/ai/sessions/' + waiting.rawData.sessionId + '/messages')
    assert(messages.some((message) => message.id === waiting.rawData.assistantMessageId) && messages.some((message) => message.role === 'assistant' && message.response?.toolCalls?.some((tool) => tool.tool === 'ticket_create' && tool.success)), '原稳定消息和真实工具执行没有持久化')
    state.assistantSessionId = waiting.rawData.sessionId
    state.assistantTicketId = tickets[0].id
  })
}
try {
  if (modelWindow) {
    await runModelPages()
  } else {
  const registerPage = await pageFor('anonymous')
  await step('register', registerPage, '真实表单注册权威身份', async () => {
    await goto(registerPage, '/register')
    await registerPage.getByPlaceholder('4-20 位字母、数字或下划线').fill(prefix)
    await registerPage.getByPlaceholder('展示给其他人的名字').fill('页面写验收')
    await registerPage.locator('input[type="password"]').nth(0).fill(password)
    await registerPage.locator('input[type="password"]').nth(1).fill(password)
    const response = await clickWrite(registerPage, 'POST', '/auth/register', () => registerPage.getByRole('button', { name: '注册并登录', exact: true }).click())
    tokens.buyer = response.rawData.token; users.buyer = response.rawData.user
    assert(users.buyer.id && users.buyer.roleName === 'USER', '注册真实普通身份不符')
    const addresses = await api('/auth/addresses')
    assert(Array.isArray(addresses) && addresses.length === 0, '隔离账号起始地址不是空')
  })
  if (!tokens.buyer) {
    const identity = await api('/auth/register', { method: 'POST', body: { username: prefix + 'b', password, nickname: '页面夹具账号' }, role: 'anonymous' })
    tokens.buyer = identity.token; users.buyer = identity.user
  }
  const loginPage = await pageFor('anonymous')
  await step('login', loginPage, '真实表单登录与身份恢复', async () => {
    await goto(loginPage, '/login')
    await loginPage.locator('input').nth(0).fill(users.buyer.username)
    await loginPage.locator('input[type="password"]').fill(password)
    const response = await clickWrite(loginPage, 'POST', '/auth/login', () => loginPage.getByRole('button', { name: '登录', exact: true }).click())
    assert(response.rawData.user.id === users.buyer.id, '登录与注册身份不一致')
    await loginPage.waitForURL(/#\/shop/)
    assert(Array.isArray(await api('/auth/addresses')), '登录账号权威资源不可读')
  })
  const adminIdentity = await api('/auth/login', { method: 'POST', body: { username: 'admin', password: '123456' }, role: 'anonymous' })
  tokens.admin = adminIdentity.token; users.admin = adminIdentity.user
  buyer = await pageFor('buyer'); admin = await pageFor('admin')
  const categoryId = await api('/categories/admin', { method: 'POST', body: { parentId: 0, name: prefix, status: 0 }, role: 'admin' })
  state.categoryId = categoryId
  await step('admin-catalog', admin, '品牌对话框真实新增与列表读回', async () => {
    await goto(admin, '/admin/catalog')
    await admin.getByRole('button', { name: '新建品牌', exact: true }).first().click()
    const dialog = admin.locator('.el-dialog:visible')
    await dialog.locator('.el-form-item').filter({ hasText: /^名称/ }).locator('input').fill(prefix + '品牌')
    await dialog.locator('.el-form-item').filter({ hasText: /^状态/ }).locator('.el-select__wrapper').click()
    await admin.locator('.el-select-dropdown:visible').getByText('停用', { exact: true }).click()
    const response = await clickWrite(admin, 'POST', '/brands/admin', () => dialog.getByRole('button', { name: '保存', exact: true }).click())
    state.brandId = response.rawData
    const brands = await api('/brands/admin', { role: 'admin' })
    assert(brands.filter((brand) => brand.id === response.rawData && brand.name === prefix + '品牌' && brand.status === 0).length === 1, '品牌表单值未权威落盘或未保持停用')
  })
  await step('profile', buyer, '地址表单新增与权威读取', async () => {
    await goto(buyer, '/profile')
    await buyer.getByRole('button', { name: '新增地址', exact: true }).click()
    const dialog = buyer.locator('.el-dialog:visible')
    for (const [placeholder, value] of [['收货人姓名', '页面验收'], ['11 位手机号', '13800000000'], ['省 / 直辖市', '上海市'], ['市', '上海市'], ['区 / 县', '浦东新区'], ['街道、门牌号、楼层、房间号', '独立页面测试地址']]) await dialog.getByPlaceholder(placeholder, { exact: true }).fill(value)
    const created = await clickWrite(buyer, 'POST', '/auth/addresses', () => dialog.getByRole('button', { name: '保存', exact: true }).click())
    const addresses = await api('/auth/addresses')
    assert(addresses.some((item) => item.id === created.rawData.id && item.receiverName === '页面验收'), '表单地址未权威落盘')
  })
  if (!rows(await api('/auth/addresses')).length) await api('/auth/addresses', { method: 'POST', body: { receiverName: '页面验收', receiverPhone: '13800000000', province: '上海市', city: '上海市', district: '浦东新区', detail: '独立页面测试地址', isDefault: true } })
  await step('admin-product-create', admin, '商品表单新建单规格SKU', async () => {
    await goto(admin, '/admin/products/new')
    await admin.getByPlaceholder('如：维生素 D3 软胶囊').fill(prefix + '商品')
    await admin.locator('.el-form-item').filter({ has: admin.locator('label', { hasText: /^类目$/ }) }).locator('.el-select__wrapper').click()
    await admin.locator('.el-select-dropdown:visible').getByText(prefix, { exact: true }).click()
    await admin.getByRole('button', { name: '添加规格组', exact: true }).click()
    await admin.locator('.spec-row__name input').fill('规格')
    await admin.locator('.spec-row__values').click()
    await admin.locator('.spec-row__values input').first().fill('一件')
    await admin.locator('.el-select-dropdown:visible .el-select-dropdown__item').filter({ hasText: '一件' }).first().click()
    await admin.keyboard.press('Escape')
    await admin.getByRole('button', { name: /重算组合/ }).click()
    const sku = admin.locator('.admin-table').filter({ hasText: '规格组合' }).locator('tbody tr').first()
    await sku.getByPlaceholder('0.00').fill('12')
    await sku.locator('.el-input-number input').fill('30')
    await sku.locator('.el-input-number input').press('Tab')
    const created = await clickWrite(admin, 'POST', '/products/admin/spus', () => admin.getByRole('button', { name: '保存', exact: true }).first().click())
    state.spu = created.rawData
    const detail = await api('/products/admin/spus/' + state.spu, { role: 'admin' })
    assert(detail.name === prefix + '商品' && detail.skus.length === 1 && detail.skus[0].price === 1200 && detail.skus[0].stock === 30, '表单SPU/SKU字段未权威落盘')
    state.sku = detail.skus[0].id
  })
  if (!state.spu) {
    state.spu = await api('/products/admin/spus', { role: 'admin', method: 'POST', body: { name: prefix + '商品', categoryId, status: 0, specs: [], skus: [{ price: 1200, stock: 30, specValues: {}, status: 1 }] } })
    state.sku = (await api('/products/admin/spus/' + state.spu, { role: 'admin' })).skus[0].id
  }
  await step('admin-product-edit', admin, '编辑表单保留SKU编号', async () => {
    await goto(admin, '/admin/products/' + state.spu + '/edit')
    await admin.getByPlaceholder('一句话卖点').fill('本轮真实页面修改')
    await clickWrite(admin, 'PUT', '/products/admin/spus/' + state.spu, () => admin.getByRole('button', { name: '保存', exact: true }).first().click())
    const detail = await api('/products/admin/spus/' + state.spu, { role: 'admin' })
    assert(detail.subtitle === '本轮真实页面修改' && detail.skus[0].id === state.sku, '修改未落盘或SKU丢失')
  })
  await step('admin-products', admin, '列表上架独立商品', async () => {
    await goto(admin, '/admin/products')
    await admin.locator('#spu-keyword').fill(prefix)
    await admin.getByRole('button', { name: '查询', exact: true }).click()
    const row = admin.locator('tbody tr').filter({ hasText: prefix + '商品' }).first()
    await row.waitFor()
    await clickWrite(admin, 'PUT', '/products/admin/spus/' + state.spu + '/status', () => row.getByRole('button', { name: '上架', exact: true }).click())
    assert((await api('/products/' + state.spu)).id === state.spu, '列表上架后公开商品仍不可读')
  })
  if ((await api('/products/admin/spus/' + state.spu, { role: 'admin' })).status !== 1) await api('/products/admin/spus/' + state.spu + '/status?status=1', { method: 'PUT', role: 'admin' })
  await step('shop', buyer, '商城卡片真实收藏', async () => {
    await api('/categories/admin/' + categoryId, { method: 'PUT', role: 'admin', body: { parentId: 0, name: prefix, status: 1 } })
    try {
      await goto(buyer, '/shop?categoryId=' + categoryId)
      const card = buyer.locator('.product-card').filter({ hasText: prefix + '商品' }).first()
      await card.waitFor()
      await clickWrite(buyer, 'POST', '/favorites/' + state.spu, () => card.locator('.fav').click())
      const favorites = rows(await api('/favorites?page=0&size=100'))
      assert(favorites.some((item) => item.spuId === state.spu || item.product?.id === state.spu), '卡片收藏没落盘')
    } finally {
      await api('/categories/admin/' + categoryId, { method: 'PUT', role: 'admin', body: { parentId: 0, name: prefix, status: 0 } })
    }
  })
  if (simulateFailure) {
    state.controlledFailure = { stage: 'SHOP_WRITE_COMPLETED_BEFORE_GLOBAL_CLEANUP', observed: false }
    const error = new Error('自有夹具完成点击后的受控异常，仅用于验证finally收尾')
    error.code = 'EXPECTED_FIXTURE_CLEANUP_FAILURE'
    throw error
  }
  await step('product-detail', buyer, '商品详情真实加购规格数量', async () => {
    await goto(buyer, '/products/' + state.spu)
    for (const group of await buyer.locator('.spec').all()) await group.locator('.spec__value:not([disabled])').first().click()
    await clickWrite(buyer, 'POST', '/cart/items', () => buyer.getByRole('button', { name: '加入购物车', exact: true }).click())
    const cart = await api('/cart')
    assert(cart.length === 1 && cart[0].skuId === state.sku && cart[0].quantity === 1 && cart[0].price === 1200, '详情加购SKU/数量不符')
    state.cartId = cart[0].id
  })
  if (!(await api('/cart')).length) state.cartId = (await api('/cart/items', { method: 'POST', body: { skuId: state.sku, quantity: 1 } })).id
  await step('cart', buyer, '购物车数量真实改为2', async () => {
    await goto(buyer, '/cart')
    const input = buyer.locator('.cart__qty input').first()
    await clickWrite(buyer, 'PUT', '/cart/items/' + state.cartId, async () => { await input.fill('2'); await input.press('Tab') })
    assert((await api('/cart')).find((item) => item.id === state.cartId)?.quantity === 2, '数量未权威更新')
  })
  await step('coupons', buyer, '领券中心真实领取', async () => {
    await goto(buyer, '/coupons')
    const coupon = buyer.locator('.coupon').filter({ has: buyer.getByRole('button', { name: '立即领取', exact: true }) }).first()
    await coupon.waitFor()
    const name = await coupon.locator('.coupon__name').innerText()
    const start = buyer.__requests.length
    await coupon.getByRole('button', { name: '立即领取', exact: true }).click()
    const request = await poll(() => buyer.__requests.slice(start).find((entry) => entry.method === 'POST' && /\/coupons\/\d+\/receive$/.test(entry.path)), Boolean)
    assert(request?.code === 200 && request.requestId, '领取未发成功真实请求')
    assert((await api('/coupons/mine')).some((item) => item.name === name && item.status === 'UNUSED'), '券未进入当前买家券包')
  })
  await step('favorites', buyer, '收藏列表真实移除自有商品', async () => {
    if (!rows(await api('/favorites?page=0&size=100')).some((item) => item.spuId === state.spu)) await api('/favorites/' + state.spu, { method: 'POST' })
    await goto(buyer, '/favorites')
    const card = buyer.locator('.product-card').filter({ hasText: prefix + '商品' }).first()
    await clickWrite(buyer, 'DELETE', '/favorites/' + state.spu, () => card.locator('.fav').click())
    assert(!rows(await api('/favorites?page=0&size=100')).some((item) => item.spuId === state.spu), '收藏列表移除未落盘')
  })
  await step('checkout', buyer, '结算页面真实提交订单', async () => {
    await goto(buyer, '/checkout')
    const response = await clickWrite(buyer, 'POST', '/orders/checkout', () => buyer.getByRole('button', { name: /提交订单/ }).click())
    state.order = response.rawData
    const order = await api('/orders/' + state.order.id)
    assert(order.items.length === 1 && order.items[0].skuId === state.sku && order.items[0].quantity === 2 && order.receiverName === '页面验收', '结算意图未落权威订单')
  })
  await step('payment', buyer, '收银台真实支付与订单投影', async () => {
    assert(state.order?.id, '结算页面订单前提缺失')
    await goto(buyer, '/payment?orderId=' + state.order.id)
    const start = buyer.__requests.length
    await buyer.getByRole('button', { name: '确认支付', exact: true }).click()
    const payment = await poll(() => buyer.__requests.slice(start).find((entry) => /\/payments\/\d+\/mock-pay$/.test(entry.path)), Boolean, { timeoutMs: 30000 })
    assert(payment?.code === 200 && payment.requestId, '支付按钮未成功走真实请求')
    const paid = await poll(() => api('/orders/' + state.order.id), (order) => order.status === 'PAID', { timeoutMs: 30000 })
    assert(paid.status === 'PAID', '支付后订单未投影')
  })
  await step('orders', buyer, '订单列表真实取消自有待付款订单', async () => {
    await api('/cart/items', { method: 'POST', body: { skuId: state.sku, quantity: 1 } })
    const order = await api('/orders/checkout', { method: 'POST', body: { receiverName: '页面验收', receiverPhone: '13800000000', receiverProvince: '上海市', receiverCity: '上海市', receiverDistrict: '浦东新区', receiverDetail: '独立页面测试地址', requestId: randomUUID() } })
    await goto(buyer, '/orders')
    const card = buyer.locator('.order').filter({ hasText: order.orderNo }).first()
    await card.waitFor()
    await clickWrite(buyer, 'POST', '/orders/' + order.id + '/cancel', async () => { await card.getByRole('button', { name: '取消订单', exact: true }).click(); await messageBox(buyer) })
    assert((await api('/orders/' + order.id)).status === 'CANCELLED', '列表取消未落终态')
  })
  await step('tickets', buyer, '工单列表真实提交原文', async () => {
    await goto(buyer, '/tickets')
    await buyer.getByRole('button', { name: '发起工单', exact: true }).first().click()
    const dialog = buyer.locator('.el-dialog:visible')
    await dialog.getByPlaceholder('一句话说清问题').fill(prefix + '工单')
    await dialog.locator('textarea').fill(prefix + '真实页面问题')
    const response = await clickWrite(buyer, 'POST', '/tickets', () => dialog.getByRole('button', { name: '提交工单', exact: true }).click())
    state.ticket = response.rawData.ticket
    const detail = await api('/tickets/' + state.ticket.id)
    assert(detail.ticket.title === prefix + '工单' && detail.messages[0].content === prefix + '真实页面问题', '工单标题/原文未落盘')
  })
  await step('admin-tickets', admin, '客服队列真实回复', async () => {
    assert(state.ticket?.id, '自有工单前提缺失')
    await goto(admin, '/admin/tickets')
    const item = admin.locator('.queue__item').filter({ hasText: state.ticket.ticketNo }).first()
    await item.waitFor(); await item.click()
    await admin.getByPlaceholder('回复用户。回复会自动接手这条工单，状态推到「处理中」。').fill(prefix + '客服回复')
    await clickWrite(admin, 'POST', '/tickets/admin/tickets/' + state.ticket.id + '/reply', () => admin.locator('.compose__actions').getByRole('button', { name: '回复', exact: true }).click())
    const detail = await api('/tickets/' + state.ticket.id)
    assert(detail.ticket.status === 'PROCESSING' && detail.messages.at(-1).content === prefix + '客服回复', '客服点击回复未权威落盘')
  })
  await step('ticket-detail', buyer, '工单详情真实追加原文', async () => {
    assert(state.ticket?.id, '自有工单前提缺失')
    await goto(buyer, '/tickets/' + state.ticket.id)
    await buyer.locator('textarea').fill(prefix + '买家追加')
    await clickWrite(buyer, 'POST', '/tickets/' + state.ticket.id + '/messages', () => buyer.getByRole('button', { name: '发送', exact: true }).click())
    assert((await api('/tickets/' + state.ticket.id)).messages.at(-1).content === prefix + '买家追加', '详情追加原文未落盘')
  })
  await step('admin-users', admin, '用户列表真实禁用自有买家后恢复', async () => {
    await goto(admin, '/admin/users')
    await admin.locator('#user-keyword').fill(users.buyer.username)
    await admin.getByRole('button', { name: '查询', exact: true }).click()
    const row = admin.locator('tbody tr').filter({ hasText: users.buyer.username }).first()
    await row.getByRole('button', { name: '管理', exact: true }).click()
    await clickWrite(admin, 'PUT', '/auth/admin/users/' + users.buyer.id + '/status', async () => { await admin.locator('.el-drawer:visible').getByRole('button', { name: '禁用', exact: true }).click(); await messageBox(admin, '独立页面验收') })
    assert((await api('/auth/admin/users/' + users.buyer.id, { role: 'admin' })).user.status === 0, '真实点击禁用未落盘')
    await api('/auth/admin/users/' + users.buyer.id + '/status', { method: 'PUT', body: { status: 1 }, role: 'admin' })
  })
  await step('admin-bad-cases', admin, '自有真实反馈页面加入与移出追加集', async () => {
    const source = JSON.parse(await readFile(resolve(ROOT, '.evidence/ai-public-api-d00b57a2-7c63-418f-89b5-97b576138a83.json'), 'utf8'))
    const id = source.state.badCaseId
    assert(id && source.state.buyerId, '自有真实BadCase来源缺失')
    const before = await api('/ai/admin/bad-cases/' + id, { role: 'admin' })
    assert(before.sessionId === source.sessions.chat && before.assistantMessageId === source.state.chat.assistantMessageId && before.status === 'REVIEWED', '不能借用他人或未审核反馈；管理员userId按公开契约脱敏，归属以原真实会话/消息证明')
    await goto(admin, '/admin/bad-cases')
    const row = admin.locator('tbody tr').filter({ hasText: id }).first()
    await row.getByRole('button', { name: '加入追加集', exact: true }).click()
    const dialog = admin.locator('.el-dialog:visible')
    await dialog.getByPlaceholder('说明为什么把这条加入追加集，以及希望验证什么').fill('独立真实页面标注验收')
    await clickWrite(admin, 'POST', '/ai/admin/bad-cases/' + id + '/test-case', () => dialog.getByRole('button', { name: '确认加入', exact: true }).click())
    const fixtures = await api('/ai/admin/bad-cases/fixtures?limit=1000', { role: 'admin' })
    assert(fixtures.some((item) => item.sourceBadCaseId === id && item.annotation === '独立真实页面标注验收'), '页面标注没有真实进入独立追加集')
    await clickWrite(admin, 'DELETE', '/ai/admin/bad-cases/' + id + '/test-case', async () => { await row.getByRole('button', { name: '移出追加集', exact: true }).click(); await messageBox(admin) })
    assert(!(await api('/ai/admin/bad-cases/fixtures?limit=1000', { role: 'admin' })).some((item) => item.sourceBadCaseId === id), '移出后追加集仍残留')
  })
  await step('after-sales', buyer, '售后列表撤销自有未审核申请', async () => {
    assert(state.order?.id, '真实页面已支付订单前提缺失')
    const order = await api('/orders/' + state.order.id)
    if (order.status === 'PAID') await api('/orders/admin/orders/' + order.id + '/ship', { method: 'POST', role: 'admin', body: { carrierCode: 'SF', carrierName: '顺丰速运', trackingNo: prefix + '-UI' } })
    if ((await api('/orders/' + order.id)).status === 'SHIPPED') await api('/orders/' + order.id + '/receive', { method: 'POST' })
    const received = await api('/orders/' + order.id)
    const applied = await api('/after-sales', { method: 'POST', body: { orderItemId: received.items[0].id, type: 'RETURN_REFUND', reason: '包装破损', qualityIssue: true, description: '真实页面撤销验收' } })
    state.afterSale = applied
    await goto(buyer, '/after-sales')
    const card = buyer.locator('.record').filter({ hasText: applied.afterSaleNo }).first()
    await clickWrite(buyer, 'POST', '/after-sales/' + applied.id + '/cancel', async () => { await card.getByRole('button', { name: '撤销申请', exact: true }).click(); await messageBox(buyer) })
    assert((await api('/after-sales/' + applied.id)).afterSale.status === 'CANCELLED', '售后列表点击撤销没有落终态')
  })
  await step('order-detail', buyer, '订单详情真实发表自有订单行评价', async () => {
    assert(state.order?.id, '自有已收货订单前提缺失')
    await goto(buyer, '/orders/' + state.order.id)
    await buyer.getByRole('button', { name: '评价', exact: true }).click()
    const dialog = buyer.locator('.el-dialog:visible')
    await dialog.getByPlaceholder('说说这件商品怎么样').fill(prefix + '真实评价')
    const created = await clickWrite(buyer, 'POST', '/reviews', () => dialog.getByRole('button', { name: '发布评价', exact: true }).click())
    state.reviewId = created.rawData.id
    assert(rows(await api('/reviews/mine?orderId=' + state.order.id)).some((item) => item.id === state.reviewId && item.content === prefix + '真实评价'), '订单详情发表评价未落盘')
  })
  await step('admin-orders', admin, '订单工作台真实备注独立订单', async () => {
    assert(state.order?.id, '自有订单前提缺失')
    await goto(admin, '/admin/orders')
    await admin.getByPlaceholder('订单号 / 收货人 / 手机号 / 运单号').fill(state.order.orderNo)
    await admin.getByRole('button', { name: '查询', exact: true }).click()
    await admin.locator('tbody tr').filter({ hasText: state.order.orderNo }).getByRole('button', { name: '详情', exact: true }).click()
    await admin.locator('.el-drawer:visible').getByRole('button', { name: '编辑备注', exact: true }).click()
    const dialog = admin.locator('.el-dialog:visible')
    await dialog.getByPlaceholder('只有后台看得见，用户不会看到').fill(prefix + '管理备注')
    await clickWrite(admin, 'PUT', '/orders/admin/orders/' + state.order.id + '/remark', () => dialog.getByRole('button', { name: '保存', exact: true }).click())
    assert((await api('/orders/admin/orders/' + state.order.id, { role: 'admin' })).adminRemark === prefix + '管理备注', '工作台备注未权威落盘')
  })
  await step('admin-reviews', admin, '评价工作台真实回复自有评价', async () => {
    if (!state.reviewId) {
      const order = await receivedOrderFixture()
      state.reviewId = (await api('/reviews', { method: 'POST', body: { orderId: order.id, orderItemId: order.items[0].id, rating: 5, content: prefix + '真实评价' } })).id
    }
    await goto(admin, '/admin/reviews')
    await admin.getByPlaceholder('评价内容 / 商品名').fill(prefix + '真实评价')
    await admin.getByRole('button', { name: '查询', exact: true }).click()
    await admin.locator('tbody tr').filter({ hasText: prefix + '真实评价' }).getByRole('button', { name: '处理', exact: true }).click()
    await admin.locator('.el-drawer:visible').getByRole('button', { name: '回复评价', exact: true }).click()
    const dialog = admin.locator('.el-dialog:visible')
    await dialog.getByPlaceholder('回复会展示在商详页这条评价下面，以商家身份出现').fill(prefix + '商家回复')
    await clickWrite(admin, 'PUT', '/reviews/admin/reviews/' + state.reviewId + '/reply', () => dialog.getByRole('button', { name: '保存', exact: true }).click())
    assert((await api('/reviews/admin/reviews/' + state.reviewId, { role: 'admin' })).review.replyContent === prefix + '商家回复', '管理页回复未权威落盘')
  })
  await step('admin-after-sales', admin, '售后工作台真实驳回自有申请', async () => {
    assert(state.order?.id, '自有已收货订单前提缺失')
    const order = await api('/orders/' + state.order.id)
    const applied = await api('/after-sales', { method: 'POST', body: { orderItemId: order.items[0].id, type: 'RETURN_REFUND', reason: '包装破损', qualityIssue: true, description: '真实管理页驳回验收' } })
    await goto(admin, '/admin/after-sales')
    const row = admin.locator('tbody tr').filter({ hasText: applied.afterSaleNo }).first()
    await row.getByRole('button', { name: '处理', exact: true }).click()
    await clickWrite(admin, 'POST', '/after-sales/admin/after-sales/' + applied.id + '/audit', async () => { await admin.locator('.el-drawer:visible').getByRole('button', { name: '驳回', exact: true }).click(); await messageBox(admin, '独立页面驳回原因') })
    assert((await api('/after-sales/' + applied.id)).afterSale.status === 'REJECTED', '管理端点击驳回未落权威终态')
  })
  // 模型页及售后等需主会话协调窗口；未跑不计PASS。
  for (const routeId of ['assistant', 'admin-knowledge', 'admin-evaluations']) {
    if (only && !only.includes(routeId)) continue
    results.push({ routeId, state: 'write', status: 'BLOCKED', reason: ['assistant', 'admin-knowledge', 'admin-evaluations'].includes(routeId) ? '需要主会话明确模型/审批专项窗口，默认不自动调用模型' : '待本轮独立fixture与真实页面动作补齐，API成功不能替代点击' })
  }
  }
} catch (error) {
  if (!simulateFailure || error.code !== 'EXPECTED_FIXTURE_CLEANUP_FAILURE') throw error
  state.controlledFailure.observed = true
  console.log('EXPECTED_FAILURE 已注入点击后异常，将执行统一夹具finally收尾')
} finally {
  state.cleanupFailures = []
  try { state.hotKeywordCleanup = cleanupFixtureSearches(state.fixtureSearchLedger) } catch (error) { state.cleanupFailures.push(error.message) }
  if (state.spu && tokens.admin) {
    try {
      await api('/products/admin/spus/' + state.spu + '/status?status=0', { method: 'PUT', role: 'admin' })
      const current = await api('/products/admin/spus/' + state.spu, { role: 'admin' })
      assert(current.id === state.spu && current.status === 0, '收尾后自有商品未下架')
      state.fixtureSpuStatus = current.status
    } catch (error) { state.cleanupFailures.push(error.message) }
  }
  if (tokens.admin) {
    try {
      const ownedBrands = (await api('/brands/admin', { role: 'admin' })).filter((brand) => brand.name === prefix + '品牌')
      assert(ownedBrands.length <= 1 && (!state.brandId || ownedBrands[0]?.id === state.brandId), '品牌归属不一致，不能清理')
      if (ownedBrands.length === 1) {
        const brand = ownedBrands[0]
        state.brandId = brand.id
        await api('/brands/admin/' + brand.id, { method: 'PUT', role: 'admin', body: { name: brand.name, logo: brand.logo, description: brand.description, status: 0 } })
      }
    } catch (error) { state.cleanupFailures.push(error.message) }
    if (state.categoryId) {
      try {
        const category = (await api('/categories/admin/tree', { role: 'admin' })).find((item) => item.id === state.categoryId)
        assert(category?.name === prefix && category.parentId === 0 && !category.children?.length, '类目归属或依赖不一致，不能清理')
        await api('/categories/admin/' + category.id, { method: 'PUT', role: 'admin', body: { name: category.name, parentId: category.parentId, sort: category.sort, status: 0 } })
      } catch (error) { state.cleanupFailures.push(error.message) }
    }
    try {
      const flatten = (nodes) => nodes.flatMap((node) => [node, ...flatten(node.children ?? [])])
      assert(!flatten(await api('/categories/tree', { role: 'anonymous' })).some((category) => category.id === state.categoryId), '夹具类目仍公开可见')
      assert(!(await api('/brands', { role: 'anonymous' })).some((brand) => brand.id === state.brandId || brand.name === prefix + '品牌'), '夹具品牌仍公开可见')
      state.publicFixtureVisibility = 'NONE'
    } catch (error) { state.cleanupFailures.push(error.message) }
  }
  if (tokens.admin && users.buyer?.id) {
    try { await api('/auth/admin/users/' + users.buyer.id + '/status', { method: 'PUT', body: { status: 1 }, role: 'admin' }) } catch (error) { state.cleanupFailures.push(error.message) }
  }
  if (state.cleanupFailures.length) state.cleanupFailure = state.cleanupFailures.join('；')
  for (const context of contexts) await context.close()
  await browser.close()
  await save()
}
console.log('结果 ' + results.filter((result) => result.status === 'PASS').length + ' PASS / ' + results.filter((result) => result.status === 'FAIL').length + ' FAIL / ' + results.filter((result) => result.status === 'BLOCKED').length + ' BLOCKED\n证据=' + resolve(directory, 'report.json'))
if (results.some((result) => result.status !== 'PASS') || state.cleanupFailure) process.exitCode = 1
