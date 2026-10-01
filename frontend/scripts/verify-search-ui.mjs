/**
 * 搜索体验（批次 13b）的界面验收 —— 真浏览器，真实按键与点击。
 *
 * 接口层的验收（curl 打 /products/suggest）证明不了这个批次真正要解决的问题：
 * 联想、历史、热门词、URL 筛选是**一套交互**，拆开每一条都能过，合起来却可能是散的。
 * 这里钉的是四件最容易坏、且坏了不报错的事：
 *
 *   一、**输入两个字出联想**，且 ↑↓/Enter/Esc 能真正操作它。键盘不可用的话，
 *       鼠标用户看不出任何异常，键盘用户则完全用不了。
 *   二、**带筛选项的链接打开就是筛好的结果**。筛选条件住进 URL 之后，
 *       「刷新丢了」「发给别人打开是另一批商品」这两个经典故障都由此而来。
 *   三、**刷新后筛选不丢**，且「已选条件」上写的就是 URL 上写的。
 *   四、**搜索历史**写入本地、去重、可清空 —— 它是纯前端状态，服务端一条日志都不会留。
 *
 * 数据影响：会写入若干条热门搜索词（Redis ZSET）。这是**真实行为**，不是污染——
 * 热门词本来就该由真实搜索累积出来。也正因如此，这里搜的都是真实商品词而不是
 * 「测试词1、测试词2」：编造的词一样会被记进排行榜，几轮跑下来首页的「热门搜索」
 * 就成了一排测试垃圾。搜索历史写在浏览器的临时 profile 里，用完即弃。
 *
 * 前置条件：后端九个服务 + 前端 dev server（5173）已启动。
 *
 * 用法：
 *   node scripts/verify-search-ui.mjs
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

const browser = await chromium.launch({ executablePath: CHROMIUM })
// 不登录：搜索与商品浏览都是公开的，匿名状态下能用才算数
const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } })
const page = await context.newPage()
const problems = []
page.on('console', (m) => m.type() === 'error' && problems.push(m.text()))
page.on('pageerror', (e) => problems.push('pageerror: ' + e.message))

mkdirSync(OUT_DIR, { recursive: true })

const searchInput = page.locator('.search-box__input')
const panel = page.locator('.search-box__panel')

/**
 * 输入并等联想回来。
 *
 * 用 fill 而不是逐键 type（防抖会把逐键输入的量测等成随机数），
 * 等 `waitForResponse` 而不是 sleep：联想是防抖 + 网络两段延迟，
 * 睡固定时长在快机器上过、在慢机器上偶发失败——而偶发失败的用例比没有用例更糟，
 * 它会让整条流水线的红灯变得不可信。
 */
async function typeQuery(text) {
  const arrived = page.waitForResponse(
    (r) => r.url().includes('/products/suggest') && r.url().includes(encodeURIComponent(text)),
    { timeout: 10000 },
  )
  await searchInput.click()
  await searchInput.fill(text)
  await arrived.catch(() => null)
  // 让 Vue 把响应渲染进面板
  await page.waitForTimeout(200)
}

// ─────────── 一、两行顶栏与搜索框 ───────────
console.log('\n一、顶栏：搜索框在第一排，导航在第二排')
await page.goto(`${BASE}/#/shop`, { waitUntil: 'networkidle' })
await page.waitForSelector('.shop__grid .product-card', { timeout: 15000 })
ck('搜索框在顶栏可见', await searchInput.isVisible())
ck('搜索框在导航之前（第一排）', await page.evaluate(() => {
  const box = document.querySelector('.search-box')
  const nav = document.querySelector('.app-nav')
  return !!box && !!nav && box.getBoundingClientRect().top < nav.getBoundingClientRect().top
}))
// 顶栏是两行，页面里所有「吸顶偏移 = 顶栏高度」的地方都吃这个变量，
// 它一旦与实际高度对不上，表现是侧栏吸顶时被顶栏切掉一截
const headerMath = await page.evaluate(() => {
  const header = document.querySelector('.app-header')
  const layout = document.querySelector('.app-layout')
  const declared = getComputedStyle(layout).getPropertyValue('--layout-header-height').trim()
  const probe = document.createElement('div')
  probe.style.height = declared
  layout.appendChild(probe)
  const declaredPx = probe.getBoundingClientRect().height
  probe.remove()
  return { actual: header.getBoundingClientRect().height, declaredPx }
})
ck(
  '--layout-header-height 与实际顶栏高度一致',
  Math.abs(headerMath.actual - headerMath.declaredPx) < 1,
  `实际 ${headerMath.actual}px / 声明 ${headerMath.declaredPx}px`,
)
ck('未登录也显示登录入口而不是空白', await page.locator('.app-login').isVisible())

// ─────────── 二、热门搜索词（面板空输入态） ───────────
console.log('\n二、空输入：热门搜索词')
await searchInput.click()
await page.waitForTimeout(800)
const hotCount = await page.locator('.search-box__option').count()
ck('聚焦即展开面板且有内容', (await panel.isVisible()) && hotCount > 0, `实得 ${hotCount} 项`)
const hotWords = await page.locator('.search-box__text').allTextContents()
ck(
  '热门词来自接口而不是写死在前端',
  JSON.stringify(hotWords) ===
    JSON.stringify(
      (
        await (await fetch(`${GW}/products/hot-keywords?limit=8`)).json()
      ).data,
    ),
  hotWords.join(' | '),
)

// ─────────── 三、输入两个字出联想 + 键盘操作 ───────────
console.log('\n三、联想：输入两个字出候选，键盘能操作')
await typeQuery('乳清')
const suggestTexts = await page.locator('.search-box__text').allTextContents()
ck('输入「乳清」出联想候选', suggestTexts.length > 0, suggestTexts.join(' | '))
const hitParts = await page.locator('.search-box__text .is-hit').count()
ck('候选里的命中片段被高亮', hitParts > 0, `高亮片段 ${hitParts} 个`)

await searchInput.press('ArrowDown')
await page.waitForTimeout(150)
const firstActive = await page.locator('.search-box__option.is-active .search-box__text').textContent()
ck('↓ 选中第一项', !!firstActive, `实得「${firstActive}」`)
const activeId = await page.evaluate(() => document.activeElement?.getAttribute('aria-activedescendant'))
const optionIds = await page.locator('.search-box__option').evaluateAll((els) => els.map((e) => e.id))
ck(
  'aria-activedescendant 指向真实存在的选项',
  !!activeId && optionIds.includes(activeId),
  `aria-activedescendant=${activeId}`,
)
ck(
  'combobox 的 aria-expanded 为真',
  (await searchInput.getAttribute('aria-expanded')) === 'true',
)
const listboxRole = await page.locator('.search-box__list').getAttribute('role')
ck('候选列表是 listbox', listboxRole === 'listbox', `实得 ${listboxRole}`)

await searchInput.press('ArrowDown')
await page.waitForTimeout(150)
const secondActive = await page.locator('.search-box__option.is-active .search-box__text').textContent()
ck('再按一下 ↓ 移到第二项', secondActive !== firstActive, `第一项「${firstActive}」第二项「${secondActive}」`)

await searchInput.press('Escape')
await page.waitForTimeout(200)
ck('Esc 收起面板', !(await panel.isVisible()))
ck('Esc 后输入内容仍在', (await searchInput.inputValue()) === '乳清')

// 重新展开并按 Enter，应该跳走（要么进详情，要么进商城筛选）
await searchInput.press('ArrowDown')
await page.waitForTimeout(150)
await page.screenshot({ path: `${OUT_DIR}/13b-suggest.png` })
const pickText = await page.locator('.search-box__option.is-active .search-box__text').textContent()
await searchInput.press('Enter')
await page.waitForTimeout(900)
const landed = page.url()
ck(
  'Enter 采纳候选项并跳转',
  /#\/(products\/\d+|shop)/.test(landed),
  `选中的是「${pickText}」，落点 ${landed}`,
)

// ─────────── 四、关键词搜索与 URL ───────────
console.log('\n四、关键词搜索：条件写进 URL')
await page.goto(`${BASE}/#/shop`, { waitUntil: 'networkidle' })
await typeQuery('鱼油')
await searchInput.press('Enter')
await page.waitForTimeout(900)
ck('搜索后 URL 带上了关键词', decodeURIComponent(page.url()).includes('keyword=鱼油'), page.url())
await page.waitForSelector('.shop__grid .product-card, .shop__empty', { timeout: 15000 })
ck('搜索结果非空', (await page.locator('.shop__grid .product-card').count()) > 0)
ck('已选条件里有这个词', (await page.locator('.shop__chip').allTextContents()).some((t) => t.includes('鱼油')))

// ─────────── 五、带筛选条件的链接直接打开 ───────────
console.log('\n五、可分享链接：打开就是筛好的结果')
await page.goto(`${BASE}/#/shop?keyword=${encodeURIComponent('蛋白')}&minPrice=100&maxPrice=400`, {
  waitUntil: 'networkidle',
})
await page.waitForSelector('.shop__grid .product-card, .shop__empty', { timeout: 15000 })
const apiExpected = (
  await (
    await fetch(
      `${GW}/products/search?keyword=${encodeURIComponent('蛋白')}&minPrice=10000&maxPrice=40000&page=0&size=12`,
    )
  ).json()
).data
const shownNames = await page.locator('.shop__grid .product-card__name').allTextContents()
ck(
  '链接打开后列表与接口结果一致',
  shownNames.length === apiExpected.records.length && shownNames.length > 0,
  `页面 ${shownNames.length} 条 / 接口 ${apiExpected.records.length} 条`,
)
const priceInputs = await page.locator('.shop__price-input input').evaluateAll((els) =>
  els.map((e) => e.value),
)
ck('价格输入框回填成链接里的价钱', JSON.stringify(priceInputs) === JSON.stringify(['100', '400']), priceInputs.join(' - '))
const chipTexts = await page.locator('.shop__chip').allTextContents()
ck(
  '已选条件列出关键词与价格区间',
  chipTexts.some((t) => t.includes('蛋白')) && chipTexts.some((t) => t.includes('100') && t.includes('400')),
  chipTexts.join(' | '),
)

// 每条结果都该落在价格区间里（SPU 价格是区间，取「沾边」语义，所以判的是不相交）
const prices = await page.locator('.shop__grid .product-card__price').allTextContents()
const outside = prices.filter((text) => {
  const nums = [...text.matchAll(/[\d.]+/g)].map(Number)
  if (!nums.length) return false
  const low = nums[0] * 100
  const high = (nums.length > 1 ? nums[1] : nums[0]) * 100
  return high < 10000 || low > 40000
})
ck('结果的价格区间都与筛选沾边', outside.length === 0, outside.join(' | '))

// ─────────── 六、刷新不丢 ───────────
console.log('\n六、刷新：筛选条件不丢')
await page.reload({ waitUntil: 'networkidle' })
await page.waitForSelector('.shop__grid .product-card, .shop__empty', { timeout: 15000 })
const afterReloadInputs = await page.locator('.shop__price-input input').evaluateAll((els) =>
  els.map((e) => e.value),
)
ck(
  '刷新后价格框仍是 100 - 400',
  JSON.stringify(afterReloadInputs) === JSON.stringify(['100', '400']),
  afterReloadInputs.join(' - '),
)
const afterReloadChips = await page.locator('.shop__chip').allTextContents()
ck('刷新后已选条件仍在', afterReloadChips.length === 2, afterReloadChips.join(' | '))

// ─────────── 七、后退 / 清空 ───────────
console.log('\n七、后退与清空')
await page.locator('.shop__chip').nth(1).click()
await page.waitForTimeout(800)
ck(
  '点 chip 的叉取消该项筛选',
  !decodeURIComponent(page.url()).includes('minPrice'),
  page.url(),
)
await page.goBack()
await page.waitForTimeout(900)
const backInputs = await page.locator('.shop__price-input input').evaluateAll((els) =>
  els.map((e) => e.value),
)
ck('浏览器后退回到上一个筛选', JSON.stringify(backInputs) === JSON.stringify(['100', '400']), backInputs.join(' - '))

if ((await page.locator('.shop__chip-clear').count()) > 0) {
  await page.locator('.shop__chip-clear').click()
  await page.waitForTimeout(900)
  ck('清空全部后 URL 上没有筛选参数', !page.url().includes('keyword=') && !page.url().includes('minPrice='), page.url())
  ck('清空全部后条件条消失', (await page.locator('.shop__chip').count()) === 0)
} else {
  ck('已选条件多于一条时出现「清空全部」', false, '页面上没有 .shop__chip-clear')
}

// ─────────── 八、侧栏类目与品牌走 URL ───────────
console.log('\n八、侧栏筛选也写进 URL')
await page.goto(`${BASE}/#/shop`, { waitUntil: 'networkidle' })
await page.waitForSelector('.cat-list__item', { timeout: 15000 })
const catName = await page.locator('.cat-list__sub .cat-list__item').first().textContent()
await page.locator('.cat-list__sub .cat-list__item').first().click()
await page.waitForTimeout(900)
ck('点类目后 URL 带 categoryId', page.url().includes('categoryId='), page.url())
const catChip = await page.locator('.shop__chip').allTextContents()
ck(
  '类目 chip 显示的是类目名而不是 id',
  catChip.some((t) => t.includes(catName.trim())),
  catChip.join(' | '),
)
const brandName = await page.locator('.brand-list__item').first().textContent()
await page.locator('.brand-list__item').first().click()
await page.waitForTimeout(900)
ck('点品牌后 URL 带 brandId', page.url().includes('brandId='), page.url())
await page.screenshot({ path: `${OUT_DIR}/13b-shop-filters.png`, fullPage: true })

// ─────────── 九、搜索历史 ───────────
console.log('\n九、搜索历史：本地留存、去重、可清空')
await page.evaluate(() => localStorage.removeItem('envoymart:search-history'))
const words = ['鱼油', '益生菌', '鱼油']
for (const word of words) {
  await page.goto(`${BASE}/#/shop`, { waitUntil: 'networkidle' })
  await typeQuery(word)
  await searchInput.press('Enter')
  await page.waitForTimeout(800)
}
const stored = await page.evaluate(() => JSON.parse(localStorage.getItem('envoymart:search-history') ?? '[]'))
ck('历史写入本地存储', Array.isArray(stored) && stored.length > 0, JSON.stringify(stored))
ck('重复搜索的词只占一条', stored.filter((w) => w === '鱼油').length === 1, JSON.stringify(stored))
ck('最近一次排在最前', stored[0] === '鱼油', JSON.stringify(stored))

// 灌满 12 条，看是否按上限截断。
// 用真实商品词而不是「测试词1、测试词2」：每次搜索都会进热门词排行（真实行为），
// 用编造的词跑几轮，首页的「热门搜索」就变成一排测试垃圾——本不该被用户看到的东西
// 被自己的验收脚本摆上了首页
for (const word of [
  '叶黄素',
  '辅酶Q10',
  '胶原蛋白',
  '钙片',
  '乳清蛋白',
  '蛋白粉',
  '维生素C',
  '葡萄糖胺',
  '深海鱼油',
  '复合维生素',
  '氨糖',
  '膳食纤维',
]) {
  await page.goto(`${BASE}/#/shop`, { waitUntil: 'networkidle' })
  await typeQuery(word)
  await searchInput.press('Enter')
  await page.waitForTimeout(600)
}
const capped = await page.evaluate(() => JSON.parse(localStorage.getItem('envoymart:search-history') ?? '[]'))
ck('历史最多留 10 条', capped.length === 10, `实得 ${capped.length} 条`)

await page.goto(`${BASE}/#/shop`, { waitUntil: 'networkidle' })
await searchInput.click()
await page.waitForTimeout(800)
const historyOptions = await page.locator('.search-box__kind[data-kind="HISTORY"]').count()
ck('空输入时面板里能看到历史', historyOptions === 10, `实得 ${historyOptions} 条`)
const clearBtn = page.locator('.search-box__clear')
ck('有「清空历史」按钮', await clearBtn.isVisible())
await clearBtn.click()
await page.waitForTimeout(300)
const cleared = await page.evaluate(() => localStorage.getItem('envoymart:search-history'))
ck('清空后本地存储里没有历史', cleared === null || JSON.parse(cleared).length === 0, String(cleared))
await page.screenshot({ path: `${OUT_DIR}/13b-search-history.png` })

// ─────────── 十、联想直达品牌 / 类目 ───────────
console.log('\n十、联想里的品牌与类目可以直达')
await page.goto(`${BASE}/#/shop`, { waitUntil: 'networkidle' })
await typeQuery('肌')
const brandOption = page.locator('.search-box__option', {
  has: page.locator('.search-box__kind[data-kind="BRAND"]'),
})
const brandOptionCount = await brandOption.count()
ck('输入「肌」能联想出品牌', brandOptionCount > 0, `实得 ${brandOptionCount} 个品牌候选`)
if (brandOptionCount > 0) {
  const brandText = await brandOption.first().locator('.search-box__text').textContent()
  await brandOption.first().click()
  await page.waitForTimeout(900)
  ck(
    '点品牌候选跳到按品牌筛选的商城页',
    page.url().includes('brandId='),
    `选的品牌「${brandText}」，落点 ${page.url()}`,
  )
  // 搜索框只反映**当前生效的关键词**：点品牌落地后生效的是品牌筛选（显示在「已选」里），
  // 不是一个关键词。两处口径不一致的话，「输入框里写着『肌』、结果却按品牌筛」
  // 会让人以为搜索坏了
  ck(
    '已选条件条上写明按哪个品牌筛',
    (await page.locator('.shop__chip').allTextContents()).some((t) => t.includes(brandText)),
    (await page.locator('.shop__chip').allTextContents()).join(' | '),
  )
  ck(
    '搜索框里不留关键词（生效的不是关键词）',
    (await searchInput.inputValue()) === '',
    `输入框里是「${await searchInput.inputValue()}」`,
  )
}

// ─────────── 收尾 ───────────
const realProblems = problems.filter((p) => !/favicon|ResizeObserver/.test(p))
ck('浏览器控制台没有报错', realProblems.length === 0, realProblems.slice(0, 3).join('\n        '))

await browser.close()

console.log(`\n通过 ${pass} / 失败 ${fail}`)
process.exit(fail === 0 ? 0 : 1)
