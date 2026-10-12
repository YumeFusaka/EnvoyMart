/** T1逐页状态回归：真实router与组件，业务传输全部拦截，不调用模型、不写业务库。 */
import { chromium } from 'playwright-core'
import { mkdir, readFile, readdir, writeFile } from 'node:fs/promises'
import { randomUUID, createHash } from 'node:crypto'
import { resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const BASE = process.env.VERIFY_BASE ?? 'http://localhost:5173'
const CHROMIUM = process.env.PLAYWRIGHT_CHROMIUM ?? 'C:/Program Files/Google/Chrome/Application/chrome.exe'
const matrix = JSON.parse(await readFile(new URL('./fixtures/platform-feature-matrix.json', import.meta.url), 'utf8'))
const only = process.argv.find((arg) => arg.startsWith('--only='))?.slice(7).split(',')
const runId = randomUUID()
const startedAt = new Date().toISOString()
const sourceFiles = ['scripts/verify-platform-states-ui.mjs']
async function collectSources(directory, prefix) {
  for (const file of await readdir(directory, { withFileTypes: true })) {
    if (file.isDirectory()) await collectSources(resolve(directory, file.name), prefix + '/' + file.name)
    else if (/\.(?:vue|ts|scss|css)$/.test(file.name)) sourceFiles.push(prefix + '/' + file.name)
  }
}
await collectSources(fileURLToPath(new URL('../src/', import.meta.url)), 'src')
const sourceHashes = Object.fromEntries(await Promise.all(sourceFiles.sort().map(async (path) => [path, createHash('sha256').update(await readFile(new URL('../' + path, import.meta.url))).digest('hex')])))
const directory = fileURLToPath(new URL('../.evidence/platform-states-' + runId + '/', import.meta.url))
await mkdir(directory, { recursive: true })
const emptyPage = { records: [], total: 0, page: 0, size: 20, hasNext: false }
const summary = { total: 0, open: 0, processing: 0, resolved: 0, closed: 0, awaitingMe: 0, created: 0, paid: 0, shipped: 0, received: 0, completed: 0, cancelled: 0, refunding: 0, refunded: 0 }
const product = { id: 1, name: '状态验收商品', categoryId: 1, categoryName: '状态验收类目', spuCode: 'STATE-1', images: [], tags: [], specs: [], attributes: [], skus: [{ id: 1, skuId: 1, skuCode: 'STATE-SKU', specText: '唯一规格', price: 100, stock: 5, status: 1 }], status: 1, minPrice: 100, maxPrice: 100, sales: 0, reviewCount: 0, ratingAvg: 0 }
const order = { id: 1, orderNo: 'STATE-ORDER', status: 'CREATED', statusText: '待付款', items: [], statusLogs: [], totalAmount: 100, payAmount: 100, discountAmount: 0, receiverName: '验收', receiverPhone: '13800000000', receiverProvince: '上海市', receiverCity: '上海市', receiverDistrict: '浦东新区', receiverDetail: '测试地址', expireAt: new Date(Date.now() + 86400000).toISOString() }
const ticket = { ticket: { id: 1, ticketNo: 'STATE-TICKET', title: '状态验收工单', category: 'OTHER', status: 'OPEN', createdAt: new Date().toISOString() }, messages: [] }
const document = { docNo: 'KB-STATE', title: '状态验收文档', content: '用于页面状态验收的原文。', chunks: [], tags: '', source: 'PLATFORM_POLICY', scope: 'GENERAL', version: 'state-v1', status: 1 }

// 主接口按页面明确指定，详情不存在使用404业务契约，不塞入非法200/null夹具。
const plans = {
  shop: ['/shop', '/products', '没有找到符合条件的商品'],
  'product-detail': ['/products/1', '/products/1', '商品不存在', product],
  cart: ['/cart', '/cart', '购物车'],
  checkout: ['/checkout', '/auth/addresses', '收货地址'],
  orders: ['/orders', '/orders', '订单'],
  'order-detail': ['/orders/1', '/orders/1', '订单', order],
  favorites: ['/favorites', '/favorites', '收藏夹还是空的'],
  reviews: ['/reviews', '/reviews/mine', '还没有发表过评价'],
  coupons: ['/coupons', '/coupons/available', '券'],
  'my-coupons': ['/coupons/mine', '/coupons/mine', '券'],
  'after-sales': ['/after-sales', '/after-sales', '售后'],
  tickets: ['/tickets', '/tickets', '工单'],
  'ticket-detail': ['/tickets/1', '/tickets/1', '工单', ticket],
  payment: ['/payment?orderId=1', '/orders/1', '订单', order],
  assistant: ['/assistant', '/ai/sessions', '对话'],
  knowledge: ['/knowledge', '/knowledge/documents', '文档'],
  'knowledge-graph': ['/knowledge/graph?root=状态验收实体', '/knowledge/graph/entity', '没有收录'],
  'knowledge-eval': ['/knowledge/eval', '/ai/eval/retrieval/report', '快照'],
  'knowledge-eval-answer': ['/knowledge/eval/answer', '/ai/eval/grounding/report', '快照'],
  profile: ['/profile', '/auth/addresses', '还没有收货地址'],
  'admin-dashboard': ['/admin', '/orders/admin/orders', '暂无待办'],
  'admin-products': ['/admin/products', '/products/admin/spus', '没有符合条件的商品'],
  'admin-product-create': ['/admin/products/new', '/categories/admin/tree', '规格'],
  'admin-product-edit': ['/admin/products/1/edit', '/products/admin/spus/1', '商品', product],
  'admin-catalog': ['/admin/catalog', '/categories/admin/tree', '还没有类目'],
  'admin-orders': ['/admin/orders', '/orders/admin/orders', '没有符合条件的订单'],
  'admin-after-sales': ['/admin/after-sales', '/after-sales/admin/after-sales', '没有符合条件的售后单'],
  'admin-reviews': ['/admin/reviews', '/reviews/admin/reviews', '没有符合条件的评价'],
  'admin-users': ['/admin/users', '/auth/admin/users', '没有符合条件的用户'],
  'admin-tickets': ['/admin/tickets', '/tickets/admin/tickets', '工单'],
  'admin-knowledge': ['/admin/knowledge', '/knowledge/documents', '还没有知识文档'],
  'admin-graph-diagnostics': ['/admin/knowledge/graph-diagnostics', '/knowledge/admin/graph/failures', '暂无诊断记录'],
  'admin-evaluations': ['/admin/knowledge/evaluations', '/ai/eval/retrieval/report', 'NEVER_RUN'],
  'knowledge-doc': ['/knowledge/KB-STATE', '/knowledge/documents/KB-STATE', '文档', document],
  'admin-bad-cases': ['/admin/bad-cases', '/ai/admin/bad-cases', '暂无用户反馈'],
  login: ['/login', '/auth/login', '登录'],
  register: ['/register', '/auth/register', '注册'],
  'not-found': ['/state-nonexistent-page', null, '页面不存在'],
}
const publicIds = new Set(['shop', 'product-detail', 'knowledge', 'knowledge-graph', 'knowledge-eval', 'knowledge-eval-answer', 'knowledge-doc', 'login', 'register', 'not-found'])
const arrayPaths = ['/cart', '/auth/addresses', '/after-sales', '/coupons/available', '/coupons/mine', '/products/suggest', '/products/hot-keywords', '/categories/tree', '/categories/admin/tree', '/brands', '/brands/admin', '/knowledge/documents', '/knowledge/graph/entity', '/knowledge/graph/search', '/ai/sessions', '/ai/admin/bad-cases', '/ai/admin/bad-cases/fixtures', '/knowledge/admin/graph/failures']
function defaultData(path) {
  if (path === '/auth/login' || path === '/auth/register') return { token: 'state-alice', user: { id: 'state-alice', username: 'state_user', nickname: '状态验收', roleName: 'USER' } }
  if (path.endsWith('/stream')) return null
  if (path.endsWith('/summary')) return summary
  if (path.endsWith('/statistics')) return { total: 0, average: 0, ratings: {}, withImageCount: 0 }
  if (path.endsWith('/attributes') || path.endsWith('/documents') || path.endsWith('/bindings') || path.endsWith('/messages') || path.endsWith('/bad-cases')) return []
  if (path === '/ai/eval/retrieval/report') return { status: 'NEVER_RUN', cases: [], totalCases: 70, completedCases: 0 }
  if (path === '/ai/eval/grounding/report') return { live: { status: 'NEVER_RUN', cases: [], totalCases: 36, completedCases: 0 } }
  if (path === '/knowledge/graph/stats') return { available: true, entities: 0, relations: 0, byRelation: {} }
  if (path === '/knowledge/admin/graph/failures/stats') return { failed: 0, retryable: 0, batches: 0, byStage: {}, byReason: {} }
  if (path === '/ai/admin/knowledge/coverage') return { available: true, totalSpu: 0, coveredSpu: 0, uncoveredSpu: [] }
  if (path === '/ai/admin/knowledge/status') return { running: false, result: { documentCount: 0, chunkCount: 0, graphEdges: 0 } }
  if (path === '/orders/preview') return { items: [], coupons: [], totalAmount: 0, payAmount: 0, discountAmount: 0 }
  if (path === '/favorites/check') return {}
  if (path.endsWith('/logistics')) return { steps: [], status: 'CREATED' }
  if (arrayPaths.includes(path)) return []
  return emptyPage
}
const results = []
const browser = await chromium.launch({ executablePath: CHROMIUM })
async function pageFor(route, role, mode) {
  const plan = plans[route.id]
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, reducedMotion: 'reduce' })
  if (role !== 'anonymous') await context.addInitScript((role) => {
    localStorage.setItem('user', JSON.stringify({ token: 'state-' + role, profile: { id: role === 'admin' ? 'state-admin' : 'state-alice', username: role, nickname: '状态验收', roleName: role === 'admin' ? 'ADMIN' : 'USER' } }))
  }, role)
  const page = await context.newPage()
  const record = { routeId: route.id, role, mode, requests: [], errors: [], held: [], released: false }
  page.on('pageerror', (error) => record.errors.push(error.message))
  await context.route('**/*', async (requestRoute) => {
    const request = requestRoute.request()
    const url = new URL(request.url())
    if (!['xhr', 'fetch', 'eventsource'].includes(request.resourceType()) && !/^\/(?:auth|cart|orders|after-sales|tickets|products|categories|brands|coupons|favorites|reviews|knowledge|ai)(?:\/|$)/.test(url.pathname)) return requestRoute.continue()
    if (url.pathname.includes('/src/') || url.pathname.endsWith('.ts') || url.pathname.endsWith('.vue')) return requestRoute.continue()
    const isPrimary = url.pathname === plan[1]
    const entry = { method: request.method(), path: url.pathname, requestId: request.headers()['x-request-id'] ?? null, isPrimary, intercepted: true }
    record.requests.push(entry)
    if (url.pathname.endsWith('/stream')) return requestRoute.abort()
    const respond = async () => {
      const failing = isPrimary && mode === 'error' && !record.released
      const missing = isPrimary && mode === 'empty' && plan[3]
      entry.businessCode = failing ? 503 : missing ? 404 : 200
      let data = isPrimary && plan[3] ? plan[3] : defaultData(url.pathname)
      // 结算页须有选中购物车才能进入，地址空态仍真实按空数组渲染。
      if (route.id === 'checkout' && url.pathname === '/cart') data = [{ id: 1, skuId: 1, spuId: 1, name: '状态验收商品', quantity: 1, selected: true, price: 100, subtotal: 100, available: true }]
      if (route.id === 'admin-product-create' && isPrimary) data = []
      await requestRoute.fulfill({ status: 200, contentType: 'application/json', headers: { 'Access-Control-Allow-Origin': '*', 'X-Request-Id': entry.requestId ?? 'state-request' }, body: JSON.stringify({ code: failing ? 503 : missing ? 404 : 200, msg: failing ? '页面状态验收：服务暂时不可用' : missing ? plan[2] + '不存在或无权访问' : '成功', data: failing || missing ? null : data }) })
    }
    if (isPrimary && mode === 'loading' && !record.released) record.held.push(respond)
    else await respond()
  })
  await page.goto(BASE + '/#' + plan[0], { waitUntil: 'domcontentloaded' })
  return { page, context, record, release: async () => { record.released = true; await Promise.all(record.held.splice(0).map((respond) => respond())) } }
}
async function visibleAny(page, selector) {
  return page.locator(selector).evaluateAll((elements) => elements.some((element) => element.getBoundingClientRect().width > 0 && element.getBoundingClientRect().height > 0)).catch(() => false)
}
async function eventually(predicate, duration = 6000) {
  const until = Date.now() + duration
  do { if (await predicate()) return true; await new Promise((done) => setTimeout(done, 100)) } while (Date.now() < until)
  return false
}
async function testState(route, state) {
  const role = route.surface === 'admin' ? 'admin' : publicIds.has(route.id) ? 'anonymous' : 'alice'
  const test = { routeId: route.id, path: route.path, state, pass: false, status: 'FAIL', details: {} }
  let fixture
  try {
    fixture = await pageFor(route, role, state)
    const { page, record } = fixture
    if (route.id === 'not-found') {
      if (state !== 'permission') { test.status = 'NOT_APPLICABLE'; test.pass = true; test.reason = '真实404静态组件没有业务请求、异步加载和业务写入；独立permission断言匿名可达' }
      else test.pass = await eventually(() => visibleAny(page, '.not-found'))
    } else if (state === 'permission') {
      await fixture.context.close(); fixture = null
      const checks = []
      for (const identity of ['anonymous', 'alice', 'admin']) {
        const current = await pageFor(route, identity, 'permission')
        try {
          const protectedPage = !publicIds.has(route.id)
          const rejected = protectedPage && (identity === 'anonymous' || route.surface === 'admin' && identity === 'alice')
          const authRedirect = ['login', 'register'].includes(route.id) && identity !== 'anonymous'
          const allowed = await eventually(async () => {
            const rendered = (await current.page.title()).includes('EnvoyMart') && (await current.page.locator('#app').innerText()).trim().length > 0
            return rendered && (rejected ? identity === 'anonymous' ? current.page.url().includes('#/login?redirect=') : current.page.url().includes('#/shop') : authRedirect ? current.page.url().includes('#/shop') : decodeURIComponent(current.page.url()).includes('#' + plans[route.id][0]))
          })
          checks.push({ role: identity, rejected, allowed, url: current.page.url(), requests: current.record.requests })
        } finally { await current.context.close() }
      }
      test.pass = checks.every((check) => check.allowed); test.details = checks
    } else if (['login', 'register'].includes(route.id)) {
      await page.locator('input').nth(0).fill('state_user')
      await page.locator('input[type="password"]').nth(0).fill('State123456')
      if (route.id === 'register') {
        await page.getByPlaceholder('展示给其他人的名字').fill('状态验收')
        const passwords = page.locator('input[type="password"]')
        if (await passwords.count() > 1) await passwords.nth(1).fill('State123456')
      }
      if (state === 'empty') {
        await page.locator('input').nth(0).fill('')
        await page.locator('button[type="submit"], .el-button--primary').first().click()
        test.pass = await eventually(() => visibleAny(page, '.el-form-item__error')) && !record.requests.some((request) => request.isPrimary)
      } else {
        await page.locator('button[type="submit"], .el-button--primary').first().click()
        if (state === 'loading') { test.pass = await eventually(() => visibleAny(page, 'button.is-loading')); await fixture.release() }
        else test.pass = await eventually(() => visibleAny(page, '.el-message--error'))
      }
    } else if (state === 'loading') {
      const primary = await eventually(() => record.held.length > 0)
      const loadingSelector = '.el-loading-mask, .el-skeleton, .graph-stage__loading, .stage__skeleton, .session-list__skeleton'
      test.pass = primary && await eventually(() => visibleAny(page, loadingSelector))
      await fixture.release()
      const finished = await eventually(async () => !await visibleAny(page, loadingSelector))
      test.details = { primaryHeld: primary, loadingVisible: test.pass, finished }
      test.pass = test.pass && finished
    } else if (state === 'empty') {
      test.pass = await eventually(async () => {
        const text = await page.locator('body').innerText()
        return record.requests.some((request) => request.isPrimary) && (plans[route.id][3] ? await visibleAny(page, '.error-state, .error') : await visibleAny(page, '.el-empty, .admin-empty, .chat-empty, .empty-state, .graph-stage__empty, .session-list__empty') || (route.id === 'admin-bad-cases' && await visibleAny(page, '.empty')) || (route.id === 'admin-graph-diagnostics' && await visibleAny(page, '.el-table__empty-text') && text.includes('暂无诊断记录')) || /暂无待办|NEVER_RUN/.test(text))
      })
      test.details.text = (await page.locator('body').innerText()).slice(-2500)
    } else if (state === 'error') {
      const errorVisible = await eventually(() => visibleAny(page, '.error-state, .stage-alert, .empty-state--error, .session-list__error'))
      const retry = page.locator('.error-state, .stage-alert, .empty-state--error, .session-list__error').getByRole('button', { name: /重试|重新加载|刷新/ }).first()
      const retryVisible = await retry.isVisible().catch(() => false)
      record.released = true
      if (retryVisible) await retry.click()
      const restored = retryVisible && await eventually(async () => !await visibleAny(page, '.error-state, .stage-alert, .empty-state--error, .session-list__error'))
      const failureObserved = record.requests.some((request) => request.isPrimary && request.businessCode === 503)
      const recoveryObserved = record.requests.some((request) => request.isPrimary && request.businessCode === 200)
      test.pass = errorVisible && retryVisible && restored && failureObserved && recoveryObserved
      test.details = { errorVisible, retryVisible, restored, failureObserved, recoveryObserved, text: (await page.locator('body').innerText()).slice(-2500) }
    }
    if (fixture) {
      test.requests = record.requests
      test.errors = record.errors
      if (record.errors.length && state !== 'error') test.pass = false
      if (!test.pass) { const screenshot = resolve(directory, route.id + '-' + state + '.png'); await page.screenshot({ path: screenshot, fullPage: true }); test.screenshot = screenshot }
    }
    if (test.status !== 'NOT_APPLICABLE') test.status = test.pass ? 'PASS' : 'FAIL'
  } catch (error) { test.reason = error.message }
  finally { if (fixture) await fixture.context.close() }
  results.push(test)
  console.log(test.status + ' ' + route.id + '/' + state + (test.reason ? ' ' + test.reason : ''))
}
try {
  for (const route of matrix.routes) {
    if (only && !only.includes(route.id)) continue
    if (!plans[route.id]) throw new Error('缺页面状态计划：' + route.id)
    for (const state of ['loading', 'empty', 'error', 'permission']) await testState(route, state)
  }
} finally { await browser.close() }
const report = { runId, sourceHashes, startedAt, mode: 'REAL_UI_INTERCEPTED_TRANSPORT', finishedAt: new Date().toISOString(), scope: '真实router/组件状态，不证明真实API业务写入、模型质量或gateway鉴权；全部业务传输拦截', results, summary: { pass: results.filter((result) => result.status === 'PASS').length, fail: results.filter((result) => !result.pass).length, notApplicable: results.filter((result) => result.status === 'NOT_APPLICABLE').length } }
await writeFile(resolve(directory, 'report.json'), JSON.stringify(report, null, 2) + '\n', 'utf8')
console.log(JSON.stringify(report.summary) + '\n证据=' + resolve(directory, 'report.json'))
if (report.summary.fail) process.exitCode = 1
