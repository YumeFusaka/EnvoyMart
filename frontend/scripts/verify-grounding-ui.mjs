/**
 * 溯源与防幻觉的界面验收（批次 6）。
 *
 * 为什么单开一个脚本：这条链路的每一环**在接口层都已经是绿的**——引用编号在 JSON 里、
 * 冲突在 JSON 里、`ungrounded` 也在 JSON 里。而用户看到的是另一回事：字段透传丢一个、
 * `v-if` 写错一个变量名、或者卡片渲染成一个没有标题的空壳，接口依旧 200。
 * 构建期同样暴露不出来：模板编译得过，类型也对。所以这段必须在真浏览器里跑。
 *
 * 三类问句各验一条不同的闸，且**每条都配一个反向断言**——只验「该出现的出现了」
 * 会把「永远显示」的实现判成通过：
 *   一、知识型（维生素 D）—— 引用卡片不是空壳，且**不该**出现无依据横幅
 *   二、冲突型（铁）—— 卡片渲染出双方数值，且**不该**出现在知识型问句上
 *   三、库外问题（蓝牙耳机）—— 无依据横幅出现，且**不该**在知识型问句上出现
 * 二、五两段验的是「点得动」：点 `[n]` 点亮对应卡片，点「查看原文」跳到原文并高亮出那一段。
 *
 * 前置条件：后端九个服务 + 前端 dev server 已启动。AI 回答要调模型，整轮约 40 秒。
 *
 * 用法：
 *   node scripts/verify-grounding-ui.mjs
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

async function poll(fn, predicate, timeoutMs = 90000) {
  let last
  for (let waited = 0; waited <= timeoutMs; waited += 500) {
    last = await fn()
    if (predicate(last)) return last
    await new Promise((resolve) => setTimeout(resolve, 500))
  }
  return last
}

const session = await (async () => {
  const res = await fetch(`${GW}/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: 'admin', password: '123456' }),
  })
  const body = await res.json()
  if (body.code !== 200) throw new Error(`登录失败：${body.msg}（后端起了吗）`)
  return body.data
})()

const browser = await chromium.launch({ executablePath: CHROMIUM })
const context = await browser.newContext({ viewport: { width: 1440, height: 1400 } })
const page = await context.newPage()
const problems = []
page.on('console', (m) => m.type() === 'error' && problems.push(m.text()))
page.on('pageerror', (e) => problems.push('pageerror: ' + e.message))

// 路由守卫在应用启动那一刻就读了 store，先 open 再写 localStorage 已经晚了
await page.addInitScript(
  ([key, value]) => localStorage.setItem(key, value),
  ['user', JSON.stringify({ token: session.token, profile: session.user })],
)

let turn = 0
/** 发一问，等这一问的回答落定，返回那张助手卡片 */
async function ask(text) {
  const before = await page.locator('.message-card.assistant').count()
  await page.locator('.composer textarea').fill(text)
  await page.getByRole('button', { name: /发送消息/ }).click()
  const card = page.locator('.message-card.assistant').nth(before)
  await card.waitFor({ state: 'visible', timeout: 20000 })
  // 等流式结束：按钮脱离 loading 态即为「这一轮答完了」。等状态而不是等秒数
  await poll(
    () => page.getByRole('button', { name: /发送消息/ }).isEnabled(),
    (enabled) => enabled === true,
  )
  turn += 1
  await page.screenshot({ path: resolve(OUT_DIR, `grounding-${turn}.png`), fullPage: true })
  return card
}

mkdirSync(OUT_DIR, { recursive: true })
await page.goto(`${BASE}/#/assistant`, { waitUntil: 'networkidle', timeout: 30000 })

// ─────────── 一、知识型：引用卡片要真的是卡片 ───────────
console.log('\n一、知识型问句（维生素 D）')
const knowledge = await ask('维生素D每天推荐摄入多少？成年人上限是多少？')
const citations = knowledge.locator('.citation')
ck('渲染出引用卡片', (await citations.count()) >= 2, `实际 ${await citations.count()} 张`)

const firstCite = (await citations.first().innerText()).replace(/\s+/g, ' ')
ck(
  '卡片不是空壳：带原标题或出处位置',
  /KB-\d{4}|《|速查|说明书|指南/.test(firstCite),
  `卡片首行是「${firstCite.slice(0, 60)}」`,
)
ck(
  '知识型问句不显示「无依据」横幅',
  (await knowledge.locator('.ungrounded').count()) === 0,
  '有平台依据的回答被扣上这顶帽子，用户会以为整段都是模型编的',
)
// 这一条是补上来的：原先只验了「库外问题不冒出冲突卡片」，漏了知识型这一侧。
// 而真实踩到的误报恰恰在这里——条目 1 写「10 微克（折合 400 国际单位）」、
// 条目 2 写「400IU」，模型核对后确认是同一个数，却仍起了一段【冲突】，
// 于是用户收到一张并不存在的冲突卡片。
ck(
  '知识型问句不显示「冲突」卡片',
  (await knowledge.locator('.conflicts').count()) === 0,
  '同一数值的不同写法（微克与 IU）不是冲突；这里出现卡片说明误报还在',
)

// ─────────── 二、点引用角标 → 点亮对应卡片 ───────────
console.log('\n二、引用角标的跳转与高亮')
const citeButton = knowledge.locator('.message-content__cite', { hasText: /^1$/ }).first()
if ((await citeButton.count()) === 0) {
  ck('正文里有可点的引用角标', false, '正文中没找到 [1] 角标，跳转链路无从触发')
} else {
  await citeButton.click()
  const active = await poll(() => citations.first().getAttribute('class'), (c) => c?.includes('is-active'))
  ck('点 [1] 后第 1 张卡片被点亮', Boolean(active?.includes('is-active')), `class=${active}`)
}

// ─────────── 三、冲突型：真矛盾要露出来 ───────────
console.log('\n三、冲突型问句（铁）')
const conflict = await ask('成年女性每天应该摄入多少铁？')
const conflictCard = conflict.locator('.conflict')
ck('渲染出冲突卡片', (await conflictCard.count()) >= 1, '库里 KB-0009 与 KB-0016 是 20mg / 18mg 的硬矛盾')
if ((await conflictCard.count()) >= 1) {
  const text = (await conflictCard.first().innerText()).replace(/\s+/g, ' ')
  ck('卡片写明了双方各自的数值', /20/.test(text) && /18/.test(text), `卡片内容「${text.slice(0, 80)}」`)
  ck('冲突卡片带得上回原文的引用编号', (await conflictCard.first().locator('.conflict__ref').count()) >= 2)
}

// ─────────── 四、库外问题：无依据横幅 ───────────
console.log('\n四、库外问题（蓝牙耳机）')
const outside = await ask('蓝牙耳机连接不上手机怎么办？')
ck(
  '整篇无依据的横幅出现了',
  (await outside.locator('.ungrounded').count()) === 1,
  '模型会先声明「不在服务范围」再补一段通用知识——那段没有平台依据，必须标出来',
)
ck(
  '冲突卡片没有跟过来',
  (await outside.locator('.conflicts').count()) === 0,
  '这段回答里没有两份资料打架，冒出冲突卡片是误报',
)

// ─────────── 五、点引用 → 跳原文并高亮 ───────────
// 验收标准写的是「点引用跳原文**并高亮**」。前四段只验到了卡片这一侧，
// 高亮那半句一直是靠人眼看截图判断的——而「跳过去了但没高亮」正是最容易悄悄发生的一种：
// 路由接错了参数名、`?chunk=` 在跳转时丢了、偏移量算成了字符数而不是 UTF-16 码元，
// 三种都不会报错，页面照样打得开，只是没高亮。
console.log('\n五、跳原文并高亮')
const citedCard = conflict.locator('.citation').first()
const citedText = (await citedCard.locator('.citation__content').innerText()).trim()
await citedCard.locator('.citation__link').click()
await page.waitForURL(/knowledge\//, { timeout: 20000 })
const marked = await poll(
  () => page.locator('.doc__mark').count(),
  (n) => n > 0,
  20000,
)
ck('点「查看原文」跳到了知识页', /knowledge\//.test(page.url()), page.url())
// 参数名对不上时 vue-router 不会报错，只是 query 为空、高亮静默消失
ck('URL 上带着 chunk 参数', /[?&]chunk=/.test(page.url()), page.url())
ck('正文里渲染出高亮段', marked > 0, `mark.doc__mark 命中 ${marked} 个`)
const markText = marked > 0 ? (await page.locator('.doc__mark').first().innerText()).trim() : ''
ck(
  '高亮段就是引用卡片里那一段',
  markText.length > 0 &&
    (markText.slice(0, 20) === citedText.slice(0, 20) ||
      markText.includes(citedText.slice(0, 20)) ||
      citedText.includes(markText.slice(0, 20))),
  `高亮「${markText.slice(0, 40)}」/ 卡片「${citedText.slice(0, 40)}」`,
)

// 反向断言：不带 chunk 参数打开同一篇文档，一个高亮都不该有。
// 少这一条，「永远高亮第一段」的实现照样能过上面三关。
const docNo = page.url().match(/knowledge\/([^?#]+)/)?.[1] ?? ''
await page.goto(`${BASE}/#/knowledge/${docNo}`)
await poll(() => page.locator('.doc-content').count(), (n) => n > 0, 20000)
ck(
  '不带 chunk 参数打开时没有高亮',
  (await page.locator('.doc__mark').count()) === 0,
  `docNo=${docNo}，高亮是参数驱动的，不是天然就有`,
)

ck('全程无控制台报错', problems.length === 0, problems.join('\n        '))

console.log(`\n${fail === 0 ? '\x1b[32m' : '\x1b[31m'}${pass} 项通过，${fail} 项失败\x1b[0m`)
console.log(`截图：${OUT_DIR}/grounding-*.png`)
await browser.close()
process.exit(fail === 0 ? 0 : 1)
