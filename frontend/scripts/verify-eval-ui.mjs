/**
 * 检索评测页的端到端验收。
 *
 * 为什么单开一个脚本：这页存在的全部意义是「数字可被任何人独立核对」——
 * 匿名能看、数字逐位对得上夹具、失败样本不藏。这三条全是接线问题：
 * pnpm build 只证明模板能编译，页面看着也永远「像是对的」；只有把真实接口
 * 接上去、按真实数字断言，才能证明它不是一页好看的摆设。
 *
 * 分三段：
 *   一、匿名访问 —— 指标、三档、逐条明细按基线数字渲染，筛选真的在筛
 *   二、权限面 —— 匿名没有重跑按钮（且接口层真的拦住），管理员有且能跑
 *   三、反向断言 —— 匿名直接打管理端点必须 401（前端隐藏拦不住改地址栏的人）
 *
 * 数字与 CI 门禁同源（RetrievalFixtures），所以断言写在脚本里是安全的：
 * 夹具变了，这里挂掉，正好提醒「展示口径变了」。
 *
 * 前置条件：后端九个服务 + 前端 dev server 已启动。
 *
 * 用法：
 *   node scripts/verify-eval-ui.mjs
 */
import { chromium } from 'playwright-core'

const BASE = process.env.VERIFY_BASE ?? 'http://localhost:5173'
const GW = process.env.VERIFY_GW ?? 'http://localhost:8080'
const CHROMIUM =
  process.env.PLAYWRIGHT_CHROMIUM ??
  'C:/Users/j/AppData/Local/ms-playwright/chromium-1223/chrome-win64/chrome.exe'

const ADMIN = { username: 'admin', password: '123456' }

let pass = 0
let fail = 0
function ck(name, condition, detail = '') {
  if (condition) {
    console.log(`  \x1b[32mPASS\x1b[0m ${name}`)
    pass += 1
  } else {
    console.log(`  \x1b[31mFAIL\x1b[0m ${name}${detail ? `\n        ${detail}` : ''}`)
    fail += 1
  }
}

async function poll(fn, predicate, timeoutMs = 15000, stepMs = 400) {
  let last
  for (let waited = 0; waited <= timeoutMs; waited += stepMs) {
    last = await fn()
    if (predicate(last)) {
      return last
    }
    await new Promise((resolve) => setTimeout(resolve, stepMs))
  }
  return last
}

async function apiLogin(credentials) {
  const res = await fetch(`${GW}/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(credentials),
  })
  const body = await res.json()
  if (body.code !== 200) {
    throw new Error(`登录 ${credentials.username} 失败：${body.msg}（后端起了吗）`)
  }
  return body.data
}

const browser = await chromium.launch({ executablePath: CHROMIUM })

async function newPage(session) {
  const context = await browser.newContext({ viewport: { width: 1600, height: 1200 } })
  const page = await context.newPage()
  page.__errors = []
  page.on('console', (m) => m.type() === 'error' && page.__errors.push(m.text()))
  page.on('pageerror', (e) => page.__errors.push('pageerror: ' + e.message))
  if (session) {
    await page.addInitScript(
      ([key, value]) => localStorage.setItem(key, value),
      ['user', JSON.stringify({ token: session.token, profile: session.user })],
    )
  }
  return page
}

async function openEval(page) {
  await page.goto(`${BASE}/#/knowledge/eval`, { waitUntil: 'networkidle' })
  await page.reload({ waitUntil: 'networkidle' })
  await page.locator('.case-list').waitFor({ state: 'visible', timeout: 20000 })
}

// ==================== 一、匿名访问 ====================
console.log('\n一、匿名访问：数字与明细按基线渲染')

const anon = await newPage(null)
await openEval(anon)

ck('页面标题渲染', (await anon.locator('.eval-header h1').textContent())?.includes('评测'))

const hero = (await anon.locator('.metric-card--hero').textContent()) ?? ''
ck('Hit Rate@3 = 63.3%（与夹具基线逐位一致）', hero.includes('63.3'), hero.replace(/\s+/g, ' ').trim())

const metrics = (await anon.locator('.metric-grid').textContent()) ?? ''
ck('MRR@3 = 0.565', metrics.includes('0.565'))
ck('NDCG@3 = 0.572', metrics.includes('0.572'))
ck('Hit Rate@5 = 67.5%', metrics.includes('67.5'))

const meta = (await anon.locator('.eval-meta').textContent()) ?? ''
ck('语料口径 = 90 篇 × 120 条', meta.includes('90') && meta.includes('120'), meta.replace(/\s+/g, ' ').trim())

const strata = anon.locator('.stratum')
ck('三档分层齐备', (await strata.count()) === 3, `count=${await strata.count()}`)
const strataText = (await anon.locator('.strata-panel').textContent()) ?? ''
ck('字面档 97.5%', strataText.includes('97.5'))
ck('口语档 70.0%', strataText.includes('70.0'))
ck('难例档 22.5%', strataText.includes('22.5'))

const bars = anon.locator('.stratum__bar')
const barLabels = await bars.evaluateAll((nodes) => nodes.map((n) => n.getAttribute('aria-label')))
ck(
  '三档进度条带可访问名称',
  barLabels.every((label) => label?.startsWith('Hit Rate')),
  JSON.stringify(barLabels),
)

const rows = anon.locator('.case-row')
ck('逐条明细 120 条全列', (await rows.count()) === 120, `count=${await rows.count()}`)
const missRows = anon.locator('.case-row.is-miss')
ck('未命中 44 条且已标注', (await missRows.count()) === 44, `count=${await missRows.count()}`)

await anon.locator('.cases-filter').getByText('仅未命中', { exact: true }).click()
await poll(() => anon.locator('.case-row').count(), (n) => n === 44, 8000)
ck('筛选「仅未命中」后只剩未命中行', (await anon.locator('.case-row').count()) === 44)
ck(
  '计数文案跟随筛选',
  ((await anon.locator('.cases-count').textContent()) ?? '').includes('44 / 120'),
  (await anon.locator('.cases-count').textContent()) ?? '',
)

await anon.locator('.cases-filter').getByText('字面重合', { exact: true }).click()
await poll(() => anon.locator('.case-row').count(), (n) => n === 40, 8000)
ck('按档筛选「字面重合」后 40 条', (await anon.locator('.case-row').count()) === 40)

ck(
  '匿名没有「重新运行」按钮',
  (await anon.getByRole('button', { name: '重新运行' }).count()) === 0,
)
ck('匿名访问无控制台报错', anon.__errors.length === 0, anon.__errors.slice(0, 3).join(' | '))
await anon.context().close()

// ==================== 二、管理员 ====================
console.log('\n二、管理员：可现场重跑')

const adminSession = await apiLogin(ADMIN)
const asAdmin = await newPage(adminSession)
await openEval(asAdmin)

const rerunBtn = asAdmin.getByRole('button', { name: '重新运行' })
ck('管理员可见「重新运行」', (await rerunBtn.count()) === 1)

await rerunBtn.click()
const message = asAdmin.locator('.el-message')
await poll(() => message.count(), (n) => n > 0, 20000)
ck('重跑给出成功提示', ((await message.first().textContent()) ?? '').includes('重跑完成'))

const metaAfter = await poll(
  () => asAdmin.locator('.eval-meta').textContent(),
  (text) => (text ?? '').includes('手动重跑'),
  10000,
)
ck('快照来源变为「手动重跑」', (metaAfter ?? '').includes('手动重跑'), (metaAfter ?? '').replace(/\s+/g, ' ').trim())
ck('管理员访问无控制台报错', asAdmin.__errors.length === 0, asAdmin.__errors.slice(0, 3).join(' | '))

// ==================== 三、反向断言 ====================
console.log('\n三、反向断言：接口层真的拦得住')

const anonymousRerun = await fetch(`${GW}/knowledge/admin/eval/run`, { method: 'POST' })
ck('匿名直接 POST 管理端点 → 401', anonymousRerun.status === 401, `HTTP ${anonymousRerun.status}`)

const normalSession = await apiLogin({ username: 'alice', password: '123456' })
const normalRerun = await fetch(`${GW}/knowledge/admin/eval/run`, {
  method: 'POST',
  headers: { Authorization: `Bearer ${normalSession.token}` },
})
const normalBody = await normalRerun.json()
ck(
  '普通用户 POST 管理端点被拒（403）',
  normalBody.code === 403,
  `HTTP ${normalRerun.status} body=${JSON.stringify(normalBody)}`,
)

await browser.close()

console.log(`\n结果：${pass} 通过，${fail} 失败`)
process.exit(fail === 0 ? 0 : 1)
