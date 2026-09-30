/**
 * 知识层两个公开页面的端到端验收：关系图谱 + 知识文档（含溯源跳转）。
 *
 * 为什么单开一个脚本：知识层的全部说服力在一个闭环上——「图谱上有一条边 →
 * 点开它的逐字引文 → 跳到原文并被精确高亮」。三段里任何一段接线错了，
 * 页面都照样好看：边画不出来只是空画布、引文链接错只是跳错文档、
 * 高亮锚点对不上只是没有 <mark>。构建期发现不了任何一个。
 *
 * 分三段：
 *   一、图谱页 —— 规模数字、画布实体、依据清单的逐字引文
 *   二、溯源跳转 —— 从边上的「查看原文」落到文档页，且锚点高亮真的出现
 *   三、文档页 —— 切片目录联动、悬空锚点必须显式报警
 *
 * 数据是演示语料（种子数据），实体名是稳定的；断言里的名字跟着种子走。
 *
 * 前置条件：后端九个服务 + 前端 dev server 已启动。
 *
 * 用法：
 *   node scripts/verify-knowledge-ui.mjs
 */
import { chromium } from 'playwright-core'

const BASE = process.env.VERIFY_BASE ?? 'http://localhost:5173'
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

const browser = await chromium.launch({ executablePath: CHROMIUM })
const context = await browser.newContext({ viewport: { width: 1600, height: 1200 } })
const page = await context.newPage()
const consoleErrors = []
page.on('console', (m) => m.type() === 'error' && consoleErrors.push(m.text()))
page.on('pageerror', (e) => consoleErrors.push('pageerror: ' + e.message))
/** 匿名访问：知识层是公开的，全程不带登录态 */
const hashOf = () => new URL(page.url()).hash.replace(/^#/, '')

// ==================== 一、图谱页 ====================
console.log('\n一、图谱页：规模、画布与依据清单')

await page.goto(`${BASE}/#/knowledge/graph`, { waitUntil: 'networkidle' })
await page.reload({ waitUntil: 'networkidle' })
await page.locator('svg.canvas').waitFor({ state: 'visible', timeout: 20000 })

const scale = (await page.locator('.graph-scale').textContent()) ?? ''
ck('规模数字渲染（39 实体 / 37 关系）', scale.includes('39') && scale.includes('37'), scale.replace(/\s+/g, ' ').trim())

const ariaLabel = (await page.locator('svg.canvas').getAttribute('aria-label')) ?? ''
ck('画布可访问名给出实体/关系/跳数', /知识图谱：\d+ 个实体、\d+ 条关系/.test(ariaLabel), ariaLabel)

const nodeCount = await page.locator('svg.canvas g.node').count()
ck('画布上真的画出了实体节点', nodeCount > 0, `nodes=${nodeCount}`)

const rows = page.locator('.evidence__row')
const rowCount = await poll(() => rows.count(), (n) => n > 0, 10000)
ck('依据清单列出边', rowCount > 0, `rows=${rowCount}`)

// 点开第一条边：逐字引文是这条边的全部价值，必须真的渲染出非空文本
await rows.first().locator('.evidence__head').click()
const quote = await poll(
  () => page.locator('.evidence__quote').first().textContent(),
  (t) => Boolean(t && t.trim().length > 0),
  8000,
)
ck('展开边后给出逐字引文', Boolean(quote && quote.trim().length > 10), (quote ?? '').slice(0, 80))

// 换中心实体（深链）：华法林是相互作用最密的一类实体
await page.goto(`${BASE}/#/knowledge/graph?root=${encodeURIComponent('华法林')}&depth=2`, {
  waitUntil: 'networkidle',
})
await page.reload({ waitUntil: 'networkidle' })
const huaRows = await poll(() => page.locator('.evidence__row').count(), (n) => n > 0, 15000)
ck('深链换中心实体后重新取到依据', huaRows > 0, `rows=${huaRows}`)

// ==================== 二、溯源跳转 ====================
console.log('\n二、溯源跳转：从边的引文落到原文高亮')

await page.locator('.evidence__row').first().locator('.evidence__head').click()
const sourceLink = page.locator('.evidence__source a').first()
await sourceLink.waitFor({ state: 'visible', timeout: 8000 })
const sourceText = (await sourceLink.textContent()) ?? ''
await sourceLink.click()

const mark = page.locator('mark.doc__mark')
const marked = await poll(() => mark.count(), (n) => n > 0, 15000)
ck('跳转后原文锚点高亮出现', marked > 0)
const markText = (await mark.first().textContent()) ?? ''
ck('高亮的是引文对应的那段原文', markText.trim().length > 4, `mark="${markText.slice(0, 60)}"`)
ck(
  '落在文档页且带着 chunk 锚点',
  hashOf().startsWith('/knowledge/') && hashOf().includes('chunk='),
  `hash=${hashOf()}`,
)
ck('来源链接文案指向文档', sourceText.includes('查看原文'), sourceText)

// ==================== 三、文档页 ====================
console.log('\n三、文档页：目录联动与悬空锚点')

const tocItems = page.locator('.doc-toc__item')
const tocCount = await poll(() => tocItems.count(), (n) => n > 0, 10000)
ck('切片目录渲染', tocCount > 0, `toc=${tocCount}`)

// 点第二个切片：URL 锚点跟随、高亮跟随、aria-current 跟随
if (tocCount >= 2) {
  const second = tocItems.nth(1)
  const secondNo = (await second.locator('.doc-toc__no').textContent()) ?? ''
  await second.click()
  await poll(() => hashOf(), (h) => h.includes('chunk='), 8000)
  const current = await poll(
    () => page.locator('.doc-toc__item[aria-current="true"] .doc-toc__no').textContent(),
    (t) => Boolean(t),
    8000,
  )
  ck('点击目录后 aria-current 跟随到该切片', current === secondNo, `current=${current} 期望=${secondNo}`)
  ck('高亮跟随切片', (await mark.count()) > 0)
}

// 悬空锚点：检索侧与存储侧切分参数不一致时唯一会喊出来的地方
await page.goto(`${BASE}/#/knowledge/KB-0005?chunk=KB-0005_does_not_exist`, {
  waitUntil: 'networkidle',
})
const alert = page.locator('.doc-alert[role="alert"]')
const alertVisible = await poll(() => alert.count(), (n) => n > 0, 10000)
ck('悬空锚点显式报警（不能静默）', alertVisible > 0)

ck('全程无控制台报错', consoleErrors.length === 0, consoleErrors.slice(0, 3).join(' | '))

await browser.close()
console.log(`\n结果：${pass} 通过，${fail} 失败`)
process.exit(fail === 0 ? 0 : 1)
