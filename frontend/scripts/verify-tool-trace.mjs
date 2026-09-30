/**
 * 工具调用轨迹的界面验收（批次 7）。
 *
 * 为什么单开一个脚本：轨迹这条链路上，**接口层的字段早就是对的**——`success`、`latencyMs`、
 * `noData` 都在 JSON 里。而用户看到的是另一回事：徽标按 `success` 二态渲染、
 * 耗时格式化成一个读不出的数字、summary 上什么都没有。接口依旧 200，构建期也不报错。
 * 所以「数据对不对」和「渲染对不对」要分开验，这个脚本两段都做。
 *
 * 最要紧的一条是**第三态**：关键词查空时 `success=true, noData=true`——
 * 二态渲染会在「没有找到与「钛合金登山杖」相关的商品」旁边亮一个绿标「成功」。
 * 所以本脚本每条正向断言都配了反向：
 *   一、接口契约（不走浏览器，快且确定）
 *   二、查得到 —— 徽标是「成功」，且**不是**「无结果」
 *   三、查不到 —— 徽标是「无结果」，且**不是**「成功」（这条在二态渲染下必失败）
 *   四、不调工具 —— **不该**出现轨迹（防「永远渲染一个假轨迹」）
 *
 * 前置条件：后端九个服务 + 前端 dev server 已启动。每一问都要调模型，整轮约 30–60 秒。
 *
 * 用法：
 *   node scripts/verify-tool-trace.mjs
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

/** 直接问一次接口。轨迹的字段对不对不该等浏览器渲染完才知道 */
async function askApi(message) {
  const res = await fetch(`${GW}/ai/chat`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${session.token}` },
    body: JSON.stringify({ sessionId: `verify-trace-${Date.now()}`, message }),
  })
  const body = await res.json()
  if (body.code !== 200) throw new Error(`对话失败：${body.msg}`)
  return body.data
}

// ─────────── 一、接口契约 ───────────
console.log('\n一、接口契约')
const hit = await askApi('帮我推荐几款乳清蛋白粉')
const hitCall = (hit.toolCalls ?? [])[0]
ck('命中的检索带回了轨迹', Boolean(hitCall), JSON.stringify(hit.toolCalls))
ck('带 success 字段且为 true', hitCall?.success === true, JSON.stringify(hitCall))
ck('带 noData 字段且为 false', hitCall?.noData === false, JSON.stringify(hitCall))
ck(
  '带 latencyMs 且非负',
  typeof hitCall?.latencyMs === 'number' && hitCall.latencyMs >= 0,
  `latencyMs=${hitCall?.latencyMs}`,
)

const miss = await askApi('帮我搜一下钛合金登山杖')
const missCall = (miss.toolCalls ?? [])[0]
ck(
  '查空的那次是 success=true 且 noData=true',
  missCall?.success === true && missCall?.noData === true,
  `success=${missCall?.success} noData=${missCall?.noData}（二态渲染会把这一条画成绿标「成功」）`,
)
ck(
  '查空的返回文本确实是「没有找到」',
  (missCall?.output ?? '').includes('没有找到'),
  missCall?.output,
)

// ─────────── 浏览器部分 ───────────
const browser = await chromium.launch({ executablePath: CHROMIUM })
const context = await browser.newContext({ viewport: { width: 1440, height: 1400 } })
const page = await context.newPage()
const problems = []
page.on('console', (m) => m.type() === 'error' && problems.push(m.text()))
page.on('pageerror', (e) => problems.push('pageerror: ' + e.message))

await page.addInitScript(
  ([key, value]) => localStorage.setItem(key, value),
  ['user', JSON.stringify({ token: session.token, profile: session.user })],
)

let turn = 0
async function ask(text) {
  const before = await page.locator('.message-card.assistant').count()
  await page.locator('.composer textarea').fill(text)
  await page.getByRole('button', { name: /发送消息/ }).click()
  const card = page.locator('.message-card.assistant').nth(before)
  await card.waitFor({ state: 'visible', timeout: 20000 })
  await poll(
    () => page.getByRole('button', { name: /发送消息/ }).isEnabled(),
    (enabled) => enabled === true,
  )
  turn += 1
  await page.screenshot({ path: resolve(OUT_DIR, `tool-trace-${turn}.png`), fullPage: true })
  return card
}

/** 展开轨迹并读出每条的状态徽标与耗时 */
async function traceOf(card) {
  const details = card.locator('details.trace')
  if ((await details.count()) === 0) {
    return null
  }
  await details.locator('summary').click()
  const badges = await details.locator('.trace__badge').allInnerTexts()
  const times = await details.locator('.trace__ms').allInnerTexts()
  const names = await details.locator('.trace__name > span:first-child').allInnerTexts()
  const outputs = await details.locator('.trace__io--output').allInnerTexts()
  return { summary: await details.locator('summary').innerText(), badges, times, names, outputs }
}

mkdirSync(OUT_DIR, { recursive: true })
await page.goto(`${BASE}/#/assistant`, { waitUntil: 'networkidle', timeout: 30000 })

// ─────────── 二、查得到 ───────────
console.log('\n二、查得到的那一轮')
const okCard = await ask('帮我推荐几款乳清蛋白粉')
const okTrace = await traceOf(okCard)
ck('渲染出工具轨迹', okTrace !== null, '卡片里没有 details.trace')
if (okTrace) {
  ck('工具名翻成了中文', okTrace.names.some((n) => /商品检索/.test(n)), okTrace.names.join('/'))
  ck(
    '徽标是「成功」',
    okTrace.badges.includes('成功'),
    `实际徽标：${okTrace.badges.join('/')}`,
  )
  ck(
    '且不是「无结果」',
    !okTrace.badges.includes('无结果'),
    '查到了却标无结果 —— 说明 noData 被当成了 success 的反面',
  )
  ck(
    '每条调用都显示耗时',
    okTrace.times.length > 0 && okTrace.times.every((t) => /(\d+ ms|\d+\.\d s|<1 ms)/.test(t)),
    `实际：${okTrace.times.join('/')}`,
  )
  ck('summary 上有总耗时', /共\s*[\d.]+\s*(ms|s)/.test(okTrace.summary), okTrace.summary)
  ck(
    '展开后有入参和返回，不是空壳',
    okTrace.outputs.length > 0 && okTrace.outputs.every((o) => o.trim().length > 10),
    `返回文本：${JSON.stringify(okTrace.outputs)}`,
  )
}

// ─────────── 三、查不到 ───────────
console.log('\n三、查不到的那一轮')
const missCard = await ask('帮我搜一下钛合金登山杖')
const missTrace = await traceOf(missCard)
ck('这一轮也有轨迹', missTrace !== null, '卡片里没有 details.trace')
if (missTrace) {
  ck(
    '徽标是「无结果」',
    missTrace.badges.includes('无结果'),
    `实际徽标：${missTrace.badges.join('/')} —— 二态渲染会把这一条画成绿标「成功」`,
  )
  ck(
    '且不是「成功」',
    !missTrace.badges.includes('成功'),
    '绿标「成功」和旁边的「没有找到相关商品」自相矛盾',
  )
  ck(
    'summary 上点出无结果次数',
    /无结果/.test(missTrace.summary),
    `收起时看不到，等于默认看不到：${missTrace.summary}`,
  )
}

// ─────────── 四、不调工具 ───────────
console.log('\n四、不调工具的那一轮')
const plainCard = await ask('你好')
await poll(() => page.getByRole('button', { name: /发送消息/ }).isEnabled(), (e) => e === true)
ck(
  '纯寒暄不该出现工具轨迹',
  (await plainCard.locator('details.trace').count()) === 0,
  '没有调用工具却渲染了轨迹 —— 轨迹是「永远渲染」的，前两段的正向断言就都失效了',
)

ck('全程无控制台报错', problems.length === 0, problems.slice(0, 3).join(' | '))

await browser.close()

console.log(`\n${pass} 通过 / ${fail} 失败`)
console.log(`截图：${OUT_DIR}`)
process.exit(fail === 0 ? 0 : 1)
