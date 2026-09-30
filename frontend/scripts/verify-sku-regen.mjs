/**
 * 规格组合重算的回归验证 —— 盯住「改出去再改回来，SKU 的 id 还在不在」。
 *
 * 为什么需要它：`regenerateSkus` 曾经只从**当前工作集**里建池子、也只按当前工作集算
 * 「会消失的规格」。于是「手滑删掉一个规格值、又加回来」之后，那个 SKU 的 id 找不回来，
 * 提交时后端按「请求里没有的就删」把旧行删掉、同一组合再以新 id 建一遍。
 * 界面不报错、不发警告、预览看不出任何差别 —— 只有 id 变了，而被订单与评价引用的正是 id。
 * 这类缺陷 `pnpm build` 和肉眼看界面都发现不了，所以它有一条可运行的检查。
 *
 * 检查方式分两步，先看前端**要发什么**，再看后端**收下之后库里变了没有**：
 *   1. 拦下保存请求，直接读请求体里的 `skus[].id`（丢掉不发）
 *   2. 放开拦截，真保存一次，比对库里每个组合的 id、价格、库存是否与开始时逐字一致
 * 第一步决定了它不依赖后端，第二步决定了它不冤枉后端。
 *
 * 前置条件（缺一样都直接报错，不会静默通过）：
 *   - 后端九个服务已启动（`backend/run-local.sh`）
 *   - 前端 dev server 已启动（`pnpm dev`，默认 5173）
 *   - 目标 SPU 的 SKU **恰好铺满**全部规格组合（没有「缺组合」的空档）。
 *     有空洞的话，重算会补出需要现填价格的新行，保存必然被前端校验拦下，测不到想测的东西
 *
 * 用法：
 *   node scripts/verify-sku-regen.mjs        # 默认 SPU 5（益生菌粉，2 个 SKU 铺满 2 个组合）
 *   node scripts/verify-sku-regen.mjs 7      # 换一个满足前置条件的 SPU
 *
 * 实验过程中会出现一次「保存会删除 1 个规格」的告警 —— 那是**故意**制造的中间态，随后撤销。
 * 脚本结束时库里的数据与开始时完全一致（不是「应该」，是比对过）。
 */
import { chromium } from 'playwright-core'

const BASE = process.env.VERIFY_BASE ?? 'http://localhost:5173'
const GW = process.env.VERIFY_GW ?? 'http://localhost:8080'
const SPU_ID = Number(process.argv[2] ?? 5)
const ADMIN = { username: 'admin', password: '123456' }

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

async function apiLogin() {
  const res = await fetch(`${GW}/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(ADMIN),
  })
  const body = await res.json()
  if (body.code !== 200) {
    throw new Error(`管理员登录失败：${body.msg}（需要后端已启动）`)
  }
  // 整个 data 都塞进 store：路由守卫看的是 profile.roleName，只给 token 会被挡回登录页
  return body.data
}

/** 组合的规范化键：规格名排序后拼接，与页面里 comboKey 的口径一致但独立实现 */
function comboKey(specValues) {
  return Object.entries(specValues)
    .sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0))
    .map(([name, value]) => `${name}=${value}`)
    .join('|')
}

/** 库里当前这一批 SKU */
async function currentSkus(token) {
  const res = await fetch(`${GW}/products/admin/spus/${SPU_ID}`, {
    headers: { Authorization: `Bearer ${token}` },
  })
  const body = await res.json()
  if (body.code !== 200) {
    throw new Error(`读取 SPU ${SPU_ID} 失败：${body.msg}`)
  }
  const byId = new Map()
  const byCombo = new Map()
  for (const sku of body.data.skus) {
    byId.set(sku.id, sku)
    byCombo.set(comboKey(sku.specValues), sku.id)
  }
  return { byId, byCombo, specs: body.data.specs }
}

const session = await apiLogin()
const token = session.token
const before = await currentSkus(token)

// 前置条件：SKU 恰好铺满全部组合，不多不少
const comboTotal = before.specs.reduce((acc, spec) => acc * spec.values.length, 1)
if (before.byId.size !== comboTotal) {
  throw new Error(
    `SPU ${SPU_ID} 有 ${comboTotal} 个规格组合但只有 ${before.byId.size} 个 SKU —— ` +
      '重算会补出要现填价格的新行，保存会被前端校验拦下。换一个铺满的 SPU 再跑',
  )
}
console.log(`SPU ${SPU_ID}：${before.byId.size} 个 SKU 铺满 ${comboTotal} 个组合`)
for (const [key, id] of before.byCombo) console.log(`  id=${id}  ${key}`)

// 挑一个「正被某个 SKU 用着」的规格值来删：只有删得掉真实 SKU，
// 才会制造出「工作集里少了它」的中间态，而这正是当年丢 id 的入口
const target = (() => {
  for (const [index, spec] of before.specs.entries()) {
    // 值全删光会让组合数归零，测的就不是同一件事了；只挑「删一个还剩值」的规格
    if (spec.values.length < 2) {
      continue
    }
    const value = spec.values[0].value
    const used = [...before.byCombo.keys()].some((key) => key.includes(`${spec.name}=${value}`))
    if (used) {
      return { specIndex: index, specName: spec.name, value }
    }
  }
  return null
})()
if (!target) {
  throw new Error('没找到可删的规格值（需要「至少两个值、且其中一个被 SKU 用着」的规格）')
}
console.log(`\n实验对象：规格「${target.specName}」的值「${target.value}」\n`)

const browser = await chromium.launch({ executablePath: CHROMIUM })
const page = await browser.newPage({ viewport: { width: 1600, height: 1200 } })
const consoleErrors = []
page.on('console', (m) => m.type() === 'error' && consoleErrors.push(m.text()))
page.on('pageerror', (e) => consoleErrors.push('pageerror: ' + e.message))

// 直接往 localStorage 里塞会话：走登录表单要额外处理一次跳转，与本次要验证的东西无关。
// 必须用 addInitScript 而不是 goto 之后再 evaluate —— 路由守卫在应用启动那一刻就读了 store，
// 先打开页面再写 localStorage 已经晚了（只改 hash 的 goto 也不会重新加载文档）
await page.addInitScript(
  ([key, value]) => localStorage.setItem(key, value),
  ['user', JSON.stringify({ token, profile: session.user })],
)

await page.goto(`${BASE}/#/admin/products/${SPU_ID}/edit`, { waitUntil: 'networkidle' })
// 规格行的顺序与接口返回的 specs 一致（页面按数组顺序渲染），按序号取比按文本匹配稳 ——
// el-input 的 v-model 写的是 DOM 的 value 属性，`input[value="…"]` 这种属性选择器匹配不到
const specRow = page.locator('.spec-row').nth(target.specIndex)
try {
  await specRow.waitFor({ state: 'visible', timeout: 15000 })
} catch {
  await page
    .screenshot({ path: '.screenshots/verify-sku-regen-fail.png', fullPage: true })
    .catch(() => {})
  throw new Error(`规格行没渲染出来，当前地址 ${page.url()}，截图见 .screenshots/verify-sku-regen-fail.png`)
}

const skuTable = page.locator('.admin-table').filter({ hasText: '规格组合' }).first()
const warning = page.locator('.sku-warning')
const regenerate = page.getByRole('button', { name: /重算组合/ })
const rowCount = () => skuTable.locator('tbody tr').count()
/** 告警条的完整文字：标题是「保存会删除 N 个规格」，具体是哪几个在描述里 */
const warningText = async () =>
  (await warning.count()) === 0 ? '' : (await warning.innerText()).replace(/\s+/g, ' ').trim()
const newRows = () => page.locator('.admin-cell--tiny', { hasText: '本次新增' }).count()

console.log('开始')
ck('初始没有「保存会删除」告警', (await warning.count()) === 0, await warningText())
ck('初始 SKU 行数与库里一致', (await rowCount()) === before.byId.size, `实际 ${await rowCount()} 行`)

// ---- 第一步：删掉一个正被 SKU 用着的规格值 ----
await specRow.locator('.el-tag', { hasText: target.value }).first().locator('.el-tag__close').click({ force: true })
await regenerate.click()
await page.waitForTimeout(400)

const afterRemove = await warningText()
ck(
  '删掉规格值后如实警告会删除 SKU',
  afterRemove.includes('保存会删除 1 个规格') && afterRemove.includes(target.value),
  `实际告警：${afterRemove || '（无）'}`,
)
ck('此时只剩铺满的组合，没有新增行', (await newRows()) === 0, `新增行 ${await newRows()}`)

// ---- 第二步：加回来，再重算 ----
// 全场关键。旧实现此时会把那个 SKU 的 id 丢掉，而且告警同时消失 ——
// 界面上一片正常，保存下去却删旧建新
const valuesSelect = specRow.locator('.spec-row__values')
await valuesSelect.click()
await valuesSelect.locator('input').first().fill(target.value)
// 点下拉里那一项，而不是按回车：allow-create 的下拉里确实有「新建」项，但回车不提交
//（Element Plus 2.13 实测），点了才真的进 values
await page
  .locator('.el-select-dropdown:visible .el-select-dropdown__item', { hasText: target.value })
  .first()
  .click()
await page.keyboard.press('Escape')
await regenerate.click()
await page.waitForTimeout(400)

ck('加回规格值后告警消失', (await warning.count()) === 0, await warningText())
ck('SKU 行数恢复到初始值', (await rowCount()) === before.byId.size, `实际 ${await rowCount()} 行`)
ck('没有「本次新增」的残留行', (await newRows()) === 0, `新增行 ${await newRows()}`)

// ---- 第三步：拦下保存请求，直接读它要发的 id（不发出去，数据零风险）----
let sent = null
const intercept = async (route) => {
  sent = route.request().postDataJSON()
  await route.fulfill({
    status: 200,
    contentType: 'application/json;charset=UTF-8',
    body: JSON.stringify({ code: 200, data: null, msg: '拦截验证，未真正保存' }),
  })
}
await page.route(`**/products/admin/spus/${SPU_ID}`, intercept)
await page.getByRole('button', { name: '保存', exact: true }).first().click()
await page.waitForTimeout(1200)
await page.unroute(`**/products/admin/spus/${SPU_ID}`, intercept)

const sentIds = new Map((sent?.skus ?? []).map((sku) => [comboKey(sku.specValues), sku.id]))
ck('保存请求发得出去（前端校验通过）', sent !== null, sent ? '' : '没抓到 PUT 请求')
ck(
  '**请求体里每个组合都带着原来的 id**（本次修复的核心）',
  sentIds.size === before.byCombo.size &&
    [...before.byCombo].every(([key, id]) => sentIds.get(key) === id),
  `期望 ${[...before.byCombo.entries()].map(([k, v]) => `${k}→${v}`).join(' ')}\n        实发 ${[...sentIds.entries()].map(([k, v]) => `${k}→${v}`).join(' ')}`,
)

// ---- 第四步：放开拦截真保存一次，库里应当逐字不变 ----
await page.getByRole('button', { name: '保存', exact: true }).first().click()
await page.waitForTimeout(2500)

const after = await currentSkus(token)
const unchanged =
  after.byId.size === before.byId.size &&
  [...before.byCombo].every(([key, id]) => after.byCombo.get(key) === id) &&
  [...before.byId].every(([id, sku]) => {
    const now = after.byId.get(id)
    return now && now.price === sku.price && now.stock === sku.stock && now.skuCode === sku.skuCode
  })
ck(
  '真保存后每个组合的 id、价格、库存与开始时逐字一致',
  unchanged,
  `保存前 ${[...before.byCombo.entries()].map(([k, v]) => `${k}→${v}`).join(' ')}\n        保存后 ${[...after.byCombo.entries()].map(([k, v]) => `${k}→${v}`).join(' ')}`,
)

await browser.close()

const realErrors = consoleErrors.filter((text) => !/favicon|DevTools|Failed to load resource/i.test(text))
if (realErrors.length) {
  console.log(`\n浏览器控制台报错：\n  ${realErrors.join('\n  ')}`)
}
console.log(`\n== pass=${pass} fail=${fail} ==`)
process.exit(fail === 0 && realErrors.length === 0 ? 0 : 1)
