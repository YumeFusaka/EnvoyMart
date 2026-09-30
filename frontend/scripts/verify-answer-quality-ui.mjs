/**
 * 回答质量评测页的端到端验收。
 *
 * 为什么单开一个脚本：这页存在的意义是「四项质量数字可被任何人独立核对」——
 * 匿名能看、数字逐位对得上判定链、被判有问题的样本不藏、真跑真的在跑。
 * 这些全是接线问题：`pnpm build` 只证明模板能编译，接口字段透传丢一个、
 * `v-if` 写错一个变量，页面依旧「看着像是对的」。
 *
 * 分三段：
 *   一、匿名访问 —— 离线重放的四项指标、逐条明细、引用判定展开，按基线逐位断言
 *   二、管理员真跑 —— 触发、进度、跑完后的指标卡与差值徽标、逐条真实回答
 *   三、反向断言 —— 匿名/普通用户直接打管理端点必须被网关拦住
 *
 * 断言里的数字来自判定链在真实模型输出快照上的读数（grounding-fixtures.json），
 * 夹具重采后这里会挂，正好提醒「展示口径变了」。
 *
 * 前置条件：后端九个服务 + 前端 dev server 已启动。
 * 注意：第二段会**真实调用 24 次模型**（约两分钟、花配额），
 *       只想验界面就加 --offline-only。
 *
 * 用法：
 *   node scripts/verify-answer-quality-ui.mjs [--offline-only]
 */
import { mkdirSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { chromium } from 'playwright-core'

const HERE = dirname(fileURLToPath(import.meta.url))
const OUT_DIR = resolve(HERE, '../.screenshots')
const BASE = process.env.VERIFY_BASE ?? 'http://localhost:5173'
const GW = process.env.VERIFY_GW ?? 'http://localhost:8080'
const CHROMIUM =
  process.env.PLAYWRIGHT_CHROMIUM ??
  'C:/Users/j/AppData/Local/ms-playwright/chromium-1223/chrome-win64/chrome.exe'
const OFFLINE_ONLY = process.argv.includes('--offline-only')

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

async function poll(fn, predicate, timeoutMs = 20000, stepMs = 500) {
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

async function openPage(page) {
  await page.goto(`${BASE}/#/knowledge/eval/answer`, { waitUntil: 'networkidle' })
  await page.reload({ waitUntil: 'networkidle' })
  await page.locator('.case-list').waitFor({ state: 'visible', timeout: 20000 })
}

mkdirSync(OUT_DIR, { recursive: true })

// ==================== 一、匿名访问 ====================
console.log('\n一、匿名访问：四项指标与逐条明细按基线渲染')

const anon = await newPage(null)
await openPage(anon)

ck('页面标题渲染', ((await anon.locator('.quality-header h1').textContent()) ?? '').includes('回答质量'))

const blocks = anon.locator('.block')
const offlineBlock = blocks.nth(0)
const offlineText = (await offlineBlock.textContent()) ?? ''

// 四项指标与夹具基线逐位一致（判定链在 qwen-plus 快照上的读数）
ck('幻觉率 11.9%', offlineText.includes('11.9'), offlineText.replace(/\s+/g, ' ').slice(0, 200))
ck('引用准确率 100.0%', offlineText.includes('100.0'))
ck('拒答准确率 95.8%', offlineText.includes('95.8'))
ck('多跳命中率 66.7%', offlineText.includes('66.7'))

ck('四张指标卡都带门槛徽标', (await offlineBlock.locator('.metric-card').count()) === 4)
ck('四项全部过门槛', (await offlineBlock.locator('.badge--tone-ok', { hasText: '过 ·' }).count()) === 4)

const meta = (await offlineBlock.locator('.meta-strip').textContent()) ?? ''
ck('元信息含采集时间与模型', meta.includes('答案采集于') && meta.includes('qwen-plus'))

const sections = anon.locator('.block')
ck('离线重放区块标题', ((await sections.nth(0).locator('h2').textContent()) ?? '').includes('离线重放'))
ck('线上真跑区块标题', ((await sections.nth(1).locator('h2').textContent()) ?? '').includes('线上真跑'))

// 逐条明细：24 条全列，6 条被判有问题（G-07 整篇无依据 / G-10 / G-12 / G-13 / G-16 / G-17）
const rows = anon.locator('.case-row')
ck('逐条明细 24 条全列', (await rows.count()) === 24, `count=${await rows.count()}`)
const issueRows = anon.locator('.case-row.is-issue')
ck('6 条被判有问题的行已标注', (await issueRows.count()) === 6, `count=${await issueRows.count()}`)

const g12 = anon.locator('.case-row', { hasText: 'G-12' })
ck('G-12 行渲染了问题原文', ((await g12.textContent()) ?? '').includes('快递签收'))
ck('G-12 行结论含未支撑句数', ((await g12.textContent()) ?? '').includes('未支撑 2/2'))
await g12.locator('details.checks summary').click()
const checks = g12.locator('.check')
ck('G-12 引用判定展开出 2 句', (await checks.count()) === 2, `count=${await checks.count()}`)
ck(
  '两句都标了「引用指错」',
  (await g12.locator('.badge--tone-danger', { hasText: '引用指错' }).count()) === 2,
)

await anon.locator('.cases-filter').getByText('仅有问题', { exact: true }).click()
await poll(() => anon.locator('.case-row').count(), (n) => n === 6, 8000)
ck('筛选「仅有问题」后只剩 6 行', (await anon.locator('.case-row').count()) === 6)
const countText = (await anon.locator('.cases-count').textContent()) ?? ''
ck('计数文案跟随筛选', countText.includes('6 / 24'), countText)

ck(
  '匿名没有「开始真跑」按钮',
  (await anon.getByRole('button', { name: /开始真跑|重新真跑/ }).count()) === 0,
)

const liveStatus = await fetch(`${GW}/ai/eval/grounding/report`)
  .then((r) => r.json())
  .then((r) => r.data.live.status)
console.log(`  （当前 live 状态：${liveStatus}）`)
if (liveStatus === 'IDLE') {
  ck('未跑过时显示空态说明', ((await blocks.nth(1).textContent()) ?? '').includes('还没有人跑过'))
}
ck('匿名访问无控制台报错', anon.__errors.length === 0, anon.__errors.slice(0, 3).join(' | '))
await anon.screenshot({ path: resolve(OUT_DIR, 'answer-quality-anon.png'), fullPage: true })
await anon.context().close()

// ==================== 二、管理员真跑 ====================
if (!OFFLINE_ONLY) {
  console.log('\n二、管理员真跑：触发 → 进度 → 跑完后的对照')

  const adminSession = await apiLogin(ADMIN)
  const asAdmin = await newPage(adminSession)
  await openPage(asAdmin)

  const trigger = asAdmin.getByRole('button', { name: /开始真跑|重新真跑/ })
  const alreadyRunning = (await asAdmin.locator('.live-running').count()) > 0
  if (alreadyRunning) {
    console.log('  （已有一轮真跑在跑，直接等它跑完）')
  } else {
    ck('管理员可见真跑按钮', (await trigger.count()) === 1)
    await trigger.click()
  }

  // 进度：x / 24 条。真跑一轮约两分钟，等足 8 分钟
  const running = await poll(
    () => asAdmin.locator('.live-running').count(),
    (n) => n > 0,
    30000,
  )
  ck('点下后进入进行中状态（进度可见）', running > 0)
  if (running > 0) {
    const progressText = (await asAdmin.locator('.live-running').textContent()) ?? ''
    ck('进度显示 x / 24 条', /\/\s*24\s*条/.test(progressText), progressText.replace(/\s+/g, ' '))
  }

  const completed = await poll(
    () => asAdmin.locator('.live-running').count(),
    (n) => n === 0,
    8 * 60 * 1000,
    3000,
  )
  ck('真跑在时限内结束', completed === 0)

  const liveBlock = asAdmin.locator('.block').nth(1)
  ck('跑完后出现 4 张指标卡', (await liveBlock.locator('.metric-card').count()) === 4)
  ck(
    '指标卡带「与离线」差值徽标',
    (await liveBlock.locator('.badge', { hasText: '与离线' }).count()) === 4,
  )
  const liveMeta = (await liveBlock.locator('.meta-strip').textContent()) ?? ''
  ck('元信息含完成条数', /完成\s*24\s*\/\s*24\s*条/.test(liveMeta.replace(/\s+/g, ' ')), liveMeta.replace(/\s+/g, ' '))

  await liveBlock.locator('details.live-cases summary').click()
  const liveCases = liveBlock.locator('.live-case')
  ck('逐条真实回答 24 条', (await liveCases.count()) === 24, `count=${await liveCases.count()}`)
  const firstAnswer = (await liveCases.first().locator('.live-case__answer').textContent()) ?? ''
  ck('回答原文非空（指标是摘要，答案才是证据）', firstAnswer.trim().length > 10, firstAnswer.slice(0, 60))

  ck('管理员访问无控制台报错', asAdmin.__errors.length === 0, asAdmin.__errors.slice(0, 3).join(' | '))
  await asAdmin.screenshot({ path: resolve(OUT_DIR, 'answer-quality-live.png'), fullPage: true })
  await asAdmin.context().close()
}

// ==================== 三、反向断言 ====================
console.log('\n三、反向断言：接口层真的拦得住')

const anonymousRun = await fetch(`${GW}/ai/admin/eval/grounding/run`, { method: 'POST' })
ck('匿名直接 POST 真跑端点 → 401', anonymousRun.status === 401, `HTTP ${anonymousRun.status}`)

const normalSession = await apiLogin({ username: 'alice', password: '123456' })
const normalRun = await fetch(`${GW}/ai/admin/eval/grounding/run`, {
  method: 'POST',
  headers: { Authorization: `Bearer ${normalSession.token}` },
})
const normalBody = await normalRun.json()
ck('普通用户 POST 真跑端点被拒（403）', normalBody.code === 403, JSON.stringify(normalBody))

await browser.close()

console.log(`\n结果：${pass} 通过，${fail} 失败`)
process.exit(fail === 0 ? 0 : 1)
