/**
 * 结算页口径统一（批次 13f）的界面验收 —— 真浏览器，走真实点击。
 *
 * 这一批修的是一个「同一个语义写在两条路上，迟早分叉」的老毛病，分叉点有三处，
 * 每处都对应下面一组断言。接口层的单测证明不了它们，因为它们量的正是**两条路是否一致**：
 *
 *   一、**券能不能用**。曾经结算页按「订单总额 ≥ 门槛」就地算，而核销按「券作用范围内
 *       商品的小计」算 —— 一张限类目券在全是类目外商品的购物车里会被标成可用，
 *       点结算才被拒。现在可用性与理由都由服务端下发，且与提交被拒时**逐字同源**。
 *   二、**应付金额**。前端曾用正则从券面文案「满 100 减 20 元」里解数字再本地相减。
 *       这里断言页面上显示的就是服务端试算的 payAmount，切券会重新试算而不是本地减。
 *   三、**订单页签**。页签的成员状态与角标计数曾各存一份（前端按本地那份筛已加载的
 *       订单、数字是数当前页得来的）。现在文案、成员、计数都在服务端 OrderTab 里，
 *       这里断言页面上的页签与 `/orders/summary` 逐项相等，且点页签真的换了筛选。
 *
 * 数据影响：使用测试账号 bob —— 清空并重设它的购物车；领一张「营养保健」券（若尚未领过）。
 * 断言里那次下单**预期失败**（券不适用于该批商品），事务回滚，不产生订单、不消耗券。
 *
 * 前置条件：后端九个服务 + 前端 dev server（5173）已启动。
 *
 * 用法：
 *   node scripts/verify-checkout-coupon.mjs
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

/** 类目外：SKU 16 = 碳酸钙 D3 咀嚼片（SPU 10，类目 14 骨骼关节，路径 13/14） */
const SKU_OUT_OF_SCOPE = 16
/** 类目内：SKU 2 = 维生素 D3 软胶囊（SPU 1，类目 2 维生素矿物质，路径 1/2） */
const SKU_IN_SCOPE = 2
/** 「营养保健满 100 减 20」模板 id；作用域是**一级类目 1（营养保健）** */
const CATEGORY_COUPON_ID = 5

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

async function loginAs(username) {
  const res = await fetch(`${GW}/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password: '123456' }),
  })
  const body = await res.json()
  if (body.code !== 200) throw new Error(`登录失败：${body.msg}（后端起了吗）`)
  return body.data
}

const session = await loginAs('bob')

const authHeaders = { 'Content-Type': 'application/json', Authorization: `Bearer ${session.token}` }

async function api(path, method = 'GET', body) {
  const init = { method, headers: authHeaders }
  if (body !== undefined && method !== 'GET' && method !== 'HEAD') {
    init.body = JSON.stringify(body)
  }
  const res = await fetch(`${GW}${path}`, init)
  return res.json()
}
/** 只要 data，非 200 直接抛 —— 这些是准备数据的调用，失败就该中断 */
async function data(path, method = 'GET', body) {
  const body_ = await api(path, method, body)
  if (body_.code !== 200) throw new Error(`${method} ${path} 失败：${body_.msg}`)
  return body_.data
}

async function setCart(spec) {
  for (const item of await data('/cart')) await data(`/cart/items/${item.id}`, 'DELETE')
  for (const [skuId, quantity] of spec) {
    await data('/cart/items', 'POST', { skuId, quantity })
  }
}

const preview = (userCouponId) => data('/orders/preview', 'POST', { userCouponId: userCouponId ?? null })

// ─────────── 准备：让 bob 持有一张限类目券 ───────────
const receive = await api(`/coupons/${CATEGORY_COUPON_ID}/receive`, 'POST')
if (receive.code === 200) {
  console.log(`（已为 bob 领取「营养保健」券，userCouponId=${receive.data.id}）`)
} else if (receive.code !== 409) {
  throw new Error(`领券失败：${receive.msg}`)
}
const categoryCoupon = (await data('/coupons/mine', 'GET')).find(
  (c) => c.name === '营养保健满 100 减 20' && c.status === 'UNUSED',
)
if (!categoryCoupon) {
  throw new Error('bob 手上没有未使用的「营养保健」券，无法验证限类目口径（先确认券模板 5 的作用域是类目 1）')
}
console.log(`（限类目券 userCouponId=${categoryCoupon.id}）`)

// ─────────── 一、类目外的商品：券应判不可用，理由与提交被拒逐字一致 ───────────
console.log('\n一、只用类目外商品：券判不可用，页面上写的理由就是提交会收到的理由')
await setCart([[SKU_OUT_OF_SCOPE, 1]])
const previewOut = await preview(null)
const targetOut = previewOut.coupons.find((c) => c.id === categoryCoupon.id)
ck(
  '服务端试算：限类目券对类目外商品判不可用',
  targetOut?.usable === false,
  JSON.stringify(targetOut),
)
ck(
  '理由说明了是「不适用」而不是「差多少钱」',
  targetOut?.unusableReason?.includes('不适用'),
  String(targetOut?.unusableReason),
)

const browser = await chromium.launch({ executablePath: CHROMIUM })
const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } })
const page = await context.newPage()
const problems = []
const calls = []
page.on('console', (m) => m.type() === 'error' && problems.push(m.text()))
page.on('pageerror', (e) => problems.push('pageerror: ' + e.message))
page.on('request', (r) => {
  const url = r.url()
  if (url.includes('/orders') || url.includes('/coupons')) calls.push(`${r.method()} ${url}`)
})
await page.addInitScript(
  ([key, value]) => localStorage.setItem(key, value),
  ['user', JSON.stringify({ token: session.token, profile: session.user })],
)

mkdirSync(OUT_DIR, { recursive: true })

await page.goto(`${BASE}/#/checkout`, { waitUntil: 'networkidle' })
await page.waitForSelector('.coupon-pick', { timeout: 15000 })

// 券卡片上显示的是**规则文案**（「满 100 元减 20 元」）而不是券名，
// 所以按规则文案定位，再读它旁边那行理由
const RULE_TEXT = '满 100 元减 20 元'
const disabledPick = page.locator('.coupon-pick.is-disabled', { hasText: RULE_TEXT }).first()
ck(
  '结算页把这张券画成不可用',
  (await disabledPick.count()) === 1,
  `不可用券：${(await page.locator('.coupon-pick.is-disabled').allTextContents()).join(' | ')}`,
)
const rendered = (await disabledPick.textContent()) ?? ''
ck(
  '页面上写的理由与服务端试算逐字一致',
  rendered.includes(targetOut.unusableReason),
  `页面「${rendered}」 vs 服务端「${targetOut.unusableReason}」`,
)
ck('不可用券点不动（disabled）', (await disabledPick.isDisabled()) === true)
const totalText = await page.locator('.summary__total dd').textContent()
ck(
  '应付金额是服务端试算值，没有被本地减掉',
  totalText.trim() === `¥${(previewOut.payAmount / 100).toFixed(2)}`,
  `页面 ${totalText} vs 服务端 ¥${(previewOut.payAmount / 100).toFixed(2)}`,
)
ck('没有出现优惠券抵扣行', (await page.locator('.summary__discount').count()) === 0)
await page.screenshot({ path: `${OUT_DIR}/13f-checkout-unusable.png`, fullPage: true })

// 真正提交一次：预期 409，且 msg 与页面上的理由一字不差
const rejected = await api('/orders/checkout', 'POST', {
  receiverName: '张三',
  receiverPhone: '13800000001',
  receiverProvince: '上海市',
  receiverCity: '上海市',
  receiverDistrict: '徐汇区',
  receiverDetail: '验收脚本',
  userCouponId: categoryCoupon.id,
})
ck('提交被拒（不是 500）', rejected.code === 409, `code=${rejected.code} msg=${rejected.msg}`)
ck(
  '被拒文案与结算页写的理由逐字一致',
  rejected.msg === targetOut.unusableReason,
  `提交「${rejected.msg}」 vs 预览「${targetOut.unusableReason}」`,
)

// ─────────── 二、一级类目券覆盖子类目：加上类目内商品后当场变可用 ───────────
console.log('\n二、加一件子类目商品：一级类目券经祖先链命中，切券由服务端重新试算')
await setCart([
  [SKU_OUT_OF_SCOPE, 1],
  [SKU_IN_SCOPE, 2],
])
const previewMixed = await preview(null)
const targetMixed = previewMixed.coupons.find((c) => c.id === categoryCoupon.id)
ck(
  '服务端试算：一级类目券对子类目商品判可用',
  targetMixed?.usable === true,
  JSON.stringify(targetMixed),
)
ck(
  '抵扣只按作用域内商品算（-¥20.00）',
  targetMixed?.deductAmount === 2000,
  `deductAmount=${targetMixed?.deductAmount}`,
)

// 必须 reload 而不是 goto：当前 URL 已经是 #/checkout，再 goto 同一个地址
// 只换 hash、不重新加载文档，页面还停在上一步的试算结果上
await page.reload({ waitUntil: 'networkidle' })
await page.waitForSelector('.coupon-pick', { timeout: 15000 })
const usablePick = page.locator('.coupon-pick', { hasText: RULE_TEXT }).first()
ck('这张券现在可点了', (await usablePick.isDisabled()) === false)
ck(
  '券面直接标出本单能抵多少',
  (await usablePick.textContent()).includes(`-¥${(targetMixed.deductAmount / 100).toFixed(2)}`),
  await usablePick.textContent(),
)

calls.length = 0
await usablePick.click()
await page.waitForTimeout(1200)
ck(
  '切券触发了服务端重新试算（而不是本地相减）',
  calls.some((c) => c.includes('/orders/preview')),
  calls.join(' | ') || '没有发出试算请求',
)
const previewSelected = await preview(categoryCoupon.id)
const totalAfter = await page.locator('.summary__total dd').textContent()
ck(
  '选中后的应付 == 服务端试算的 payAmount',
  totalAfter.trim() === `¥${(previewSelected.payAmount / 100).toFixed(2)}`,
  `页面 ${totalAfter} vs 服务端 ¥${(previewSelected.payAmount / 100).toFixed(2)}`,
)
ck(
  '优惠行显示的抵扣 == 服务端算的抵扣',
  (await page.locator('.summary__discount').textContent()).includes(
    `-¥${(previewSelected.discountAmount / 100).toFixed(2)}`,
  ),
  await page.locator('.summary__discount').textContent(),
)
ck(
  '应付 = 商品金额 − 抵扣（自洽）',
  totalAfter.trim() === `¥${((previewSelected.totalAmount - previewSelected.discountAmount) / 100).toFixed(2)}`,
)
await page.screenshot({ path: `${OUT_DIR}/13f-checkout-usable.png`, fullPage: true })

// ─────────── 三、订单页签与分页：文案、计数、筛选三者同源 ───────────
//
// 换 alice 来做这一段：她的订单量足够同时验证「按状态筛」与「翻页」，
// 而 bob 只有一单，页签全是 0/1 —— 那样的数据下「筛选生效」和「筛选没生效」
// 看起来一模一样，断言等于没断言。用一个装满数据的账号，才有得量。
console.log('\n三、订单页：页签来自服务端，计数与筛选同一口径')
const alice = await loginAs('alice')
const aliceAuth = { 'Content-Type': 'application/json', Authorization: `Bearer ${alice.token}` }
const summary = (
  await (await fetch(`${GW}/orders/summary`, { headers: aliceAuth })).json()
).data

const ordersContext = await browser.newContext({ viewport: { width: 1440, height: 1000 } })
const ordersPage = await ordersContext.newPage()
const ordersProblems = []
const orderCalls = []
ordersPage.on('console', (m) => m.type() === 'error' && ordersProblems.push(m.text()))
ordersPage.on('pageerror', (e) => ordersProblems.push('pageerror: ' + e.message))
ordersPage.on('request', (r) => {
  const url = r.url()
  if (url.includes('/orders')) orderCalls.push(`${r.method()} ${url}`)
})
await ordersPage.addInitScript(
  ([key, value]) => localStorage.setItem(key, value),
  ['user', JSON.stringify({ token: alice.token, profile: alice.user })],
)
await ordersPage.goto(`${BASE}/#/orders`, { waitUntil: 'networkidle' })
await ordersPage.waitForSelector('.tabs__item', { timeout: 15000 })

const labels = await ordersPage.locator('.tabs__item').allTextContents()
ck(
  '页签数量与服务端一致',
  labels.length === summary.length,
  `页面 ${labels.length} 个 vs 服务端 ${summary.length} 个`,
)
for (const tab of summary) {
  const text = labels.find((t) => t.startsWith(tab.label))
  if (!text) {
    ck(`页签「${tab.label}」存在`, false, labels.join(' | '))
    continue
  }
  const shown = tab.count > 0 ? text.replace(tab.label, '').trim() : ''
  ck(
    `页签「${tab.label}」计数为 ${tab.count}`,
    shown === (tab.count > 0 ? String(tab.count) : ''),
    `页面显示「${shown}」`,
  )
}

const paidTab = summary.find((t) => t.tab === 'PAID')
if (paidTab && paidTab.count > 0) {
  orderCalls.length = 0
  await ordersPage.locator('.tabs__item', { hasText: paidTab.label }).first().click()
  await ordersPage.waitForTimeout(1200)
  ck(
    '点页签把 tab 带到了服务端（不是前端过滤）',
    orderCalls.some((c) => c.includes('tab=PAID')),
    orderCalls.join(' | ') || '没有发出带 tab 的请求',
  )
  const tags = await ordersPage.locator('.order .el-tag').allTextContents()
  ck(
    `「${paidTab.label}」下列表只有该状态的订单`,
    tags.length > 0 && tags.every((t) => t.trim() === '待发货'),
    tags.join(' | '),
  )
  const shown = await ordersPage.locator('.order').count()
  ck(
    '列表条数与角标一致（都 ≤ 一页）',
    shown === Math.min(paidTab.count, 10),
    `列表 ${shown} 条 vs 角标 ${paidTab.count}`,
  )
} else {
  ck('有待发货订单可供筛选验证', false, '数据前提不成立')
}

// 翻页：切到「全部」，超过一页就能验证翻页确实换了内容、且不重复
await ordersPage.locator('.tabs__item', { hasText: '全部' }).first().click()
await ordersPage.waitForTimeout(1200)
const pager = ordersPage.locator('.pager')
if ((await pager.count()) > 0) {
  const allTotal = summary.find((t) => t.tab === 'ALL').count
  const firstPageNos = await ordersPage.locator('.order__no').allTextContents()
  ck('首页满页（10 条）', firstPageNos.length === 10, `实得 ${firstPageNos.length}`)
  await pager.locator('.el-pager li', { hasText: '2' }).first().click()
  await ordersPage.waitForTimeout(1200)
  const secondPageNos = await ordersPage.locator('.order__no').allTextContents()
  ck(
    '翻页换了内容',
    secondPageNos.length > 0 && !secondPageNos.some((n) => firstPageNos.includes(n)),
    `第 1 页 ${firstPageNos.length} 条 / 第 2 页 ${secondPageNos.length} 条`,
  )
  ck('翻页没有重复条目', new Set(secondPageNos).size === secondPageNos.length)

  // 最后一页：条数应等于 total 除不尽剩下的余数，且非空 —— 这能抓住
  // 「总页数按 total 算、但最后一页查询越界返回空」这一类 off-by-one
  const lastPage = Math.ceil(allTotal / 10)
  const expectLast = allTotal - (lastPage - 1) * 10
  await pager.locator('.el-pager li', { hasText: String(lastPage) }).first().click()
  await ordersPage.waitForTimeout(1200)
  const lastNos = await ordersPage.locator('.order__no').allTextContents()
  ck(
    `最后一页（第 ${lastPage} 页）有 ${expectLast} 条`,
    lastNos.length === expectLast,
    `实得 ${lastNos.length} 条`,
  )
  await ordersPage.screenshot({ path: `${OUT_DIR}/13f-orders-paged.png`, fullPage: true })
} else {
  ck('订单超过一页，能验证翻页', false, '数据前提不成立（订单不足 11 条）')
}
await ordersContext.close()

// ─────────── 四、收尾 ───────────
console.log('\n四、控制台')
const realProblems = [...problems, ...ordersProblems].filter((p) => !p.includes('favicon'))
ck('没有控制台报错', realProblems.length === 0, realProblems.slice(0, 3).join(' | '))

await setCart([])
console.log(`\n===== 结算页口径统一验收：${pass} 通过 / ${fail} 失败 =====`)
await browser.close()
process.exit(fail ? 1 : 0)
