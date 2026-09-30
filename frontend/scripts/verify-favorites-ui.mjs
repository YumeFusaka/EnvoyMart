/**
 * 商品收藏（批次 13a）的界面验收 —— 真浏览器，走真实点击。
 *
 * 接口层的验收（verify-favorites.mjs）证明不了界面上的这三件事，而它们恰恰最容易坏：
 *
 *   一、**点心形不会跳走**。整张卡片是一个撑满的链接（stretched link），心形浮在它上面。
 *       层级或定位写错时，点心形会同时收藏并跳进详情页——报不出错，只是行为不对。
 *   二、**刷新之后心形还是亮的**。状态来自服务端（/favorites/check 回填），
 *       而不是这次会话里点过什么。只测「点完变亮」的话，纯前端状态也能过。
 *   三、**收藏夹页取消收藏，条目当场消失**。心形灰了、条目还杵着，用户会怀疑没生效。
 *
 * 数据影响：会清空 alice 的收藏夹并在结束时再清一次（测试账号的私有数据）。
 *
 * 前置条件：后端九个服务 + 前端 dev server（5173）已启动。
 *
 * 用法：
 *   node scripts/verify-favorites-ui.mjs
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

const session = await (async () => {
  const res = await fetch(`${GW}/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: 'alice', password: '123456' }),
  })
  const body = await res.json()
  if (body.code !== 200) throw new Error(`登录失败：${body.msg}（后端起了吗）`)
  return body.data
})()

const authHeaders = { 'Content-Type': 'application/json', Authorization: `Bearer ${session.token}` }

async function api(path, method = 'GET') {
  const res = await fetch(`${GW}${path}`, { method, headers: authHeaders })
  return (await res.json()).data
}

/** 清空收藏夹，让这一轮从确定的起点开始 */
async function clearFavorites() {
  for (let guard = 0; guard < 10; guard += 1) {
    const page = await api('/favorites?page=0&size=50')
    if (!page.records.length) return
    for (const item of page.records) await api(`/favorites/${item.spuId}`, 'DELETE')
  }
  throw new Error('收藏清空失败：翻页没有收敛')
}

await clearFavorites()
const products = (await api('/products?size=3&sort=newest')).records
if (products.length < 3) throw new Error('商品种子数据不足 3 条')
// 先收藏三件：收藏夹页要有东西可看，也别指望界面去点三次才铺好数据
for (const p of products) await api(`/favorites/${p.id}`, 'POST')

const browser = await chromium.launch({ executablePath: CHROMIUM })
const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } })
const page = await context.newPage()
const problems = []
page.on('console', (m) => m.type() === 'error' && problems.push(m.text()))
page.on('pageerror', (e) => problems.push('pageerror: ' + e.message))
await page.addInitScript(
  ([key, value]) => localStorage.setItem(key, value),
  ['user', JSON.stringify({ token: session.token, profile: session.user })],
)

mkdirSync(OUT_DIR, { recursive: true })

// ─────────── 一、收藏夹页 ───────────
console.log('\n一、收藏夹页：三件收藏、心形是亮的、点了会消失')
await page.goto(`${BASE}/#/favorites`, { waitUntil: 'networkidle' })
await page.waitForSelector('.fav-cell', { timeout: 15000 })
const favCells = await page.locator('.fav-cell').count()
ck('收藏夹里显示 3 件商品', favCells === 3, `实得 ${favCells}`)
const heartsOn = await page.locator('.fav-cell .fav.is-on').count()
ck('每张卡的心形都是「已收藏」态', heartsOn === 3, `实得 ${heartsOn}`)
const times = await page.locator('.fav-cell__time').allTextContents()
ck(
  '每条都标了收藏时间',
  times.every((t) => /\d{4}-\d{2}-\d{2} \d{2}:\d{2} 收藏/.test(t)),
  times.join(' | '),
)
const offBadges = await page.locator('.fav-cell .product-card__badge--off').count()
ck('在售商品没有「已下架」角标', offBadges === 0, `实得 ${offBadges}`)
await page.screenshot({ path: `${OUT_DIR}/13a-favorites.png`, fullPage: true })

const firstHeart = page.locator('.fav-cell .fav').first()
await firstHeart.click()
await page.waitForTimeout(800)
const cellsAfterUnfav = await page.locator('.fav-cell').count()
ck('取消收藏后条目当场消失', cellsAfterUnfav === 2, `实得 ${cellsAfterUnfav}`)
ck('没有跳到别的页面', page.url().includes('#/favorites'), page.url())

// ─────────── 二、商城页：点心形不跳详情 ───────────
console.log('\n二、商城页：心形浮在整卡链接之上')
await page.goto(`${BASE}/#/shop`, { waitUntil: 'networkidle' })
// 等**商城页自己的**卡片出来。等 `.product-card` 是不够的：收藏夹页也用同一个组件，
// 上一次导航的 DOM 还在时它会立刻命中，于是后面量到的是上一页的卡片数
await page.waitForFunction(
  () => document.querySelectorAll('.shop__grid .product-card').length >= 10,
  null,
  { timeout: 15000 },
)
const cardCountBefore = await page.locator('.shop__grid .product-card').count()
const unchecked = page.locator('.shop__grid .product-card .fav[aria-pressed="false"]').first()
ck(
  '商城页存在未收藏的心形',
  (await page.locator('.shop__grid .fav[aria-pressed="false"]').count()) > 0,
)

await unchecked.click()
await page.waitForTimeout(800)
ck('点心形不跳转（仍停在商城页）', page.url().includes('#/shop'), page.url())
ck(
  '商品列表没有因为点心形而重新渲染/丢失',
  (await page.locator('.shop__grid .product-card').count()) === cardCountBefore,
)
const pressedNow = await page.locator('.shop__grid .fav[aria-pressed="true"]').count()
ck('刚点的那颗变成已收藏态', pressedNow >= 1, `实得 ${pressedNow}`)
await page.screenshot({ path: `${OUT_DIR}/13a-shop.png`, fullPage: true })

// 刷新：状态必须来自服务端，而不是本地这次会话点过什么
await page.reload({ waitUntil: 'networkidle' })
await page.waitForFunction(
  () => document.querySelectorAll('.shop__grid .product-card').length >= 10,
  null,
  { timeout: 15000 },
)
const pressedAfterReload = await page.locator('.shop__grid .fav[aria-pressed="true"]').count()
ck(
  '刷新之后心形仍然是亮的（状态来自服务端）',
  pressedAfterReload >= 1,
  `实得 ${pressedAfterReload}`,
)

// ─────────── 三、详情页：带文字的收藏按钮 ───────────
console.log('\n三、商品详情页：收藏 / 已收藏 来回切')
const target = products[0]
await page.goto(`${BASE}/#/products/${target.id}`, { waitUntil: 'networkidle' })
await page.waitForSelector('.buy .fav', { timeout: 15000 })
const favTextBefore = (await page.locator('.buy .fav__text').textContent())?.trim()
ck('详情页按钮显示「已收藏」', favTextBefore === '已收藏', `实得 ${favTextBefore}`)

await page.locator('.buy .fav').click()
await page.waitForTimeout(800)
const favTextAfter = (await page.locator('.buy .fav__text').textContent())?.trim()
ck('点一下变成「收藏」', favTextAfter === '收藏', `实得 ${favTextAfter}`)
const ariaAfter = await page.locator('.buy .fav').getAttribute('aria-pressed')
ck('aria-pressed 同步为 false', ariaAfter === 'false', `实得 ${ariaAfter}`)

await page.locator('.buy .fav').click()
await page.waitForTimeout(800)
ck(
  '再点一下回到「已收藏」',
  (await page.locator('.buy .fav__text').textContent())?.trim() === '已收藏',
)
await page.screenshot({ path: `${OUT_DIR}/13a-detail.png`, fullPage: true })

// ─────────── 四、批量管理 ───────────
console.log('\n四、批量管理：全选本页 → 取消收藏 → 空态')
await page.goto(`${BASE}/#/favorites`, { waitUntil: 'networkidle' })
await page.waitForSelector('.fav-cell', { timeout: 15000 })
const beforeManage = await page.locator('.fav-cell').count()
ck('进管理前是卡片视图', beforeManage === 3, `实得 ${beforeManage}`)

await page.getByRole('button', { name: '批量管理' }).click()
await page.waitForSelector('.fav-row', { timeout: 5000 })
ck('管理态换成紧凑列表', (await page.locator('.fav-row').count()) === 3)
ck('卡片视图已收起', (await page.locator('.fav-cell').count()) === 0)
await page.screenshot({ path: `${OUT_DIR}/13a-manage.png`, fullPage: true })

// 点的是 Element Plus 的标签层：它把原生 input 藏起来做样式，直接点 input 会「不可见」
await page.locator('.fav-bar .el-checkbox').click()
await page.getByRole('button', { name: '取消收藏' }).click()
await page.waitForSelector('.el-message-box', { timeout: 5000 })
ck('弹出确认框', await page.locator('.el-message-box').isVisible())
await page.locator('.el-message-box__btns .el-button--primary').click()
await page.waitForSelector('.el-empty', { timeout: 15000 })
ck('清空后回到空态', await page.locator('.el-empty').isVisible())

const remaining = await api('/favorites?page=0&size=20')
ck('服务端也真的空了', remaining.total === 0, `实得 ${remaining.total}`)

// ─────────── 五、未登录态：能逛，不能收藏 ───────────
console.log('\n五、未登录态：商品浏览公开，收藏引导登录')
// 未登录必须换一个 context：addInitScript 注册的注入脚本会在**每个新文档**创建时重放，
// 同一个 page 上无论怎么清 localStorage，reload 之后那张令牌都会回来
const anonContext = await browser.newContext({ viewport: { width: 1440, height: 1000 } })
const anonPage = await anonContext.newPage()
anonPage.on('console', (m) => m.type() === 'error' && problems.push(`[匿名] ${m.text()}`))
anonPage.on('pageerror', (e) => problems.push(`[匿名] pageerror: ${e.message}`))

await anonPage.goto(`${BASE}/#/favorites`, { waitUntil: 'networkidle' })
await anonPage.waitForSelector('.el-form, .page', { timeout: 15000 })
ck('未登录访问收藏页被挡在登录页', anonPage.url().includes('#/login'), anonPage.url())
ck(
  '登录页带上了要回跳的地址',
  decodeURIComponent(anonPage.url()).includes('redirect=/favorites'),
  anonPage.url(),
)

await anonPage.goto(`${BASE}/#/shop`, { waitUntil: 'networkidle' })
await anonPage.waitForFunction(
  () => document.querySelectorAll('.shop__grid .product-card').length >= 10,
  null,
  { timeout: 15000 },
)
const anonCards = await anonPage.locator('.shop__grid .product-card').count()
ck('未登录也能逛商城（商品卡渲染出来了）', anonCards >= 10, `实得 ${anonCards}`)
const anonHeartsOn = await anonPage.locator('.shop__grid .fav[aria-pressed="true"]').count()
ck(
  '未登录时心形一律是灰的（没有对着服务端问过收藏状态）',
  anonHeartsOn === 0,
  `实得 ${anonHeartsOn}`,
)
await anonPage.screenshot({ path: `${OUT_DIR}/13a-anon-shop.png`, fullPage: true })

await anonPage.locator('.shop__grid .fav').first().click()
await anonPage.waitForTimeout(800)
ck('点收藏被引导到登录页', anonPage.url().includes('#/login'), anonPage.url())
ck(
  '登录后能回到刚才那一页',
  decodeURIComponent(anonPage.url()).includes('redirect=/shop'),
  anonPage.url(),
)

await anonPage.goto(`${BASE}/#/products/${products[0].id}`, { waitUntil: 'networkidle' })
await anonPage.waitForSelector('.detail', { timeout: 15000 })
ck(
  '未登录也能打开商品详情页（没有被守卫弹走）',
  anonPage.url().includes('#/products/'),
  anonPage.url(),
)
await anonPage.waitForSelector('.detail .section-title', { timeout: 15000 })
const anonReviewError = await anonPage.locator('.detail .error-state').count()
ck(
  '游客能看见评价区（评价是商品详情的一部分，不该落失败态）',
  anonReviewError === 0,
  `失败了 ${anonReviewError} 个区块`,
)
await anonContext.close()

// ─────────── 六、收尾 ───────────
console.log('\n六、控制台')
const realProblems = problems.filter((p) => !p.includes('favicon'))
ck('没有控制台报错', realProblems.length === 0, realProblems.slice(0, 3).join(' | '))

await clearFavorites()
console.log(`\n===== 收藏界面验收：${pass} 通过 / ${fail} 失败 =====`)
await browser.close()
process.exit(fail ? 1 : 0)
