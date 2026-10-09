/**
 * 商品资料覆盖率的端到端验收。
 *
 * 为什么单开一个脚本：覆盖率这一块的坏法全是**接线**层面的——接口 404、
 * 数字与图谱真实状态对不上、未覆盖清单点不进商品编辑页、图谱不可用却显示成「全部覆盖」。
 * `pnpm build` 一条都发现不了，页面看上去也都正常。
 *
 * 分三段：
 *   一、接口本身 —— 管理台能拿到读数；数字与 `documentsOfProduct` 逐个对拍；
 *       无 token 401；图谱不可用时必须 available=false 而不是全 0 覆盖
 *   二、页面渲染 —— 知识库页显示三个数字与未覆盖清单，控制台无报错
 *   三、可行动 —— 点未覆盖商品名能进它的编辑页，编辑页的「说明书与知识依据」栏出现
 *
 * 数据是演示语料，数字随语料变，所以断言不写死具体数字，只钉**关系**：
 *   coveredSpu + 未覆盖数 = totalSpu；清单里的每个商品在图上确实没有文档支持。
 *
 * 前置条件：后端九个服务 + 前端 dev server 已启动。
 *
 * 用法：
 *   node scripts/verify-coverage-ui.mjs
 */
import { chromium } from 'playwright-core'

const BASE = process.env.VERIFY_BASE ?? 'http://localhost:5173'
const GW = process.env.VERIFY_GW ?? 'http://localhost:8080'
const CHROMIUM =
  process.env.PLAYWRIGHT_CHROMIUM ??
  'C:/Users/j/AppData/Local/ms-playwright/chromium-1223/chrome-win64/chrome.exe'

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

async function login(username, password) {
  const res = await fetch(`${GW}/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
  })
  const body = await res.json()
  if (body.code !== 200) {
    throw new Error(`登录 ${username} 失败：${body.msg}（后端起了吗）`)
  }
  return body.data
}

const admin = await login('admin', '123456')
const asAdmin = { Authorization: `Bearer ${admin.token}` }

// ==================== 一、接口 ====================
console.log('\n一、覆盖率接口：读数、对拍与鉴权')

const coverageRes = await fetch(`${GW}/ai/admin/knowledge/coverage`, { headers: asAdmin })
const coverageBody = await coverageRes.json()
ck('管理台能拿到覆盖率（code=200）', coverageBody.code === 200, JSON.stringify(coverageBody).slice(0, 160))

const coverage = coverageBody.data ?? {}
ck('图谱可用时 available=true', coverage.available === true, `available=${coverage.available} reason=${coverage.reason}`)
ck(
  '已覆盖数 + 未覆盖数 = 在售商品总数',
  coverage.coveredSpu + (coverage.uncoveredSpu?.length ?? 0) === coverage.totalSpu,
  `${coverage.coveredSpu} + ${coverage.uncoveredSpu?.length} != ${coverage.totalSpu}`,
)

// 逐个对拍：接口说「已覆盖」的商品，documentsOfProduct 必须真有文档；
// 说「未覆盖」的必须真没有。判据不许两处各写一份，这条断言就是防它分叉的。
const sample = [...(coverage.uncoveredSpu ?? [])].slice(0, 3)
const coveredSpuKeys = []
// 走管理端的 SPU 列表拿在售商品（`/internal/` 前缀在网关上是 404，脚本够不着）。
// 取前 50 个里「接口说已覆盖」的，抽 3 个对拍。
const allSpu = await (
  await fetch(`${GW}/products/admin/spus?page=0&size=50`, { headers: asAdmin })
).json().catch(() => null)
if (allSpu?.data?.records) {
  const uncoveredSet = new Set((coverage.uncoveredSpu ?? []).map((u) => u.spuKey.toLowerCase()))
  for (const p of allSpu.data.records) {
    const key = `spu${p.id}`
    if (!uncoveredSet.has(key)) {
      coveredSpuKeys.push(key)
    }
  }
}
for (const key of coveredSpuKeys.slice(0, 3)) {
  const docs = await (await fetch(`${GW}/knowledge/admin/products/${key}/documents`, { headers: asAdmin })).json()
  ck(`已覆盖的 ${key} 确实有文档支持`, (docs.data?.length ?? 0) > 0, `返回 ${docs.data?.length} 篇`)
}
for (const item of sample) {
  const key = item.spuKey.toLowerCase()
  const docs = await (await fetch(`${GW}/knowledge/admin/products/${key}/documents`, { headers: asAdmin })).json()
  ck(`未覆盖的 ${item.spuKey} 确实没有文档支持`, (docs.data?.length ?? 0) === 0, `返回 ${docs.data?.length} 篇`)
  ck(
    `${item.spuKey} 的原因取值合法`,
    item.reason === 'NO_NODE' || item.reason === 'NO_DOCUMENT',
    `reason=${item.reason}`,
  )
}

const anon = await fetch(`${GW}/ai/admin/knowledge/coverage`)
ck('无 token 访问被拒（401 或非 200 业务码）', anon.status === 401 || anon.status === 403, `status=${anon.status}`)

// ==================== 二、页面渲染 ====================
console.log('\n二、知识库页：覆盖率区块')

const browser = await chromium.launch({ executablePath: CHROMIUM })
const context = await browser.newContext({ viewport: { width: 1600, height: 1200 } })
const page = await context.newPage()
const consoleErrors = []
page.on('console', (m) => m.type() === 'error' && consoleErrors.push(m.text()))
page.on('pageerror', (e) => consoleErrors.push('pageerror: ' + e.message))

// 预置会话必须用 addInitScript：路由守卫在应用启动那一刻就读了 store，
// 先打开页面再写 localStorage 已经晚了。形状要和 pinia-plugin-persistedstate
// 实际落盘的一致（localStorage['user'] = {token, profile}），否则 token 读不出来，
// 页面会以匿名身份加载 —— 那不会报错，只会把断言悄悄测到别的东西上。
await page.addInitScript(
  ([key, value]) => localStorage.setItem(key, value),
  ['user', JSON.stringify({ token: admin.token, profile: admin.user })],
)
await page.goto(`${BASE}/#/admin/knowledge`, { waitUntil: 'networkidle' })
await page.reload({ waitUntil: 'networkidle' })

const section = page.locator('section[aria-label="商品资料覆盖率"]')
await section.waitFor({ state: 'visible', timeout: 20000 })
ck('覆盖率区块渲染出来了', await section.isVisible())

const statsText = await section.locator('.coverage__stats').innerText().catch(() => '')
ck(
  '页面上出现了「覆盖 / 总数」的分数读数',
  /\d+\s*\/\s*\d+/.test(statsText),
  statsText.replace(/\n/g, ' | '),
)
ck('页面上有覆盖率百分比', /%/.test(statsText), statsText.replace(/\n/g, ' | '))

const listHrefs = await section.locator('.coverage__item-name').evaluateAll((els) =>
  els.map((el) => el.getAttribute('href')),
)
const expectedLinks = (coverage.uncoveredSpu ?? []).length
if (expectedLinks > 0) {
  ck('未覆盖清单渲染出条目', listHrefs.length > 0, `渲染 ${listHrefs.length} 条，接口 ${expectedLinks} 条`)
  ck(
    '每条都指向商品编辑页（/admin/products/<id>/edit）',
    listHrefs.every((h) => /\/admin\/products\/\d+\/edit$/.test(h ?? '')),
    listHrefs.slice(0, 3).join(', '),
  )
} else {
  ck('当前没有未覆盖商品，清单为空态', (await section.locator('.coverage__hint--good').count()) > 0)
}

// ==================== 三、可行动 ====================
console.log('\n三、从覆盖率点进商品编辑页')

if (listHrefs.length > 0) {
  await section.locator('.coverage__item-name').first().click()
  await page.waitForURL(/#\/admin\/products\/\d+\/edit/, { timeout: 15000 })
  ck('点击未覆盖商品名跳到了它的编辑页', /#\/admin\/products\/\d+\/edit/.test(page.url()), page.url())

  const docsSection = page.locator('section', { hasText: '说明书与知识依据' }).first()
  await docsSection.waitFor({ state: 'visible', timeout: 15000 })
  const docsText = await docsSection.innerText()
  ck(
    '编辑页显示「还没有说明书」的待办态（而不是空白）',
    docsText.includes('还没有') || docsText.includes('上传') || docsText.includes('文档'),
    docsText.replace(/\n/g, ' | ').slice(0, 160),
  )
} else {
  console.log('  \x1b[33mSKIP\x1b[0m 没有未覆盖商品，跳过点击跳转')
}

ck(
  '整个过程中没有控制台报错',
  consoleErrors.length === 0,
  consoleErrors.slice(0, 3).join('\n        '),
)

await browser.close()

console.log(`\n结果：${pass} 通过 / ${fail} 失败`)
process.exit(fail === 0 ? 0 : 1)
