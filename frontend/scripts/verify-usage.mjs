/**
 * token 与成本的界面验收（批次 7-C）。
 *
 * 这条链路最容易"看起来对"：界面上一串数字，接口 200，构建不报错。
 * 所以这里验的不是"有没有数字"，而是四个**错了也不会报错**的性质：
 *
 *   一、是整轮的，不是最后一次调用的 —— 检索的重排与向量化必须在明细里。
 *       只统计对话模型的话，数字会小一大截，而它看起来照样精确。
 *   二、拆得开 —— 按模型分账，否则「钱花在哪儿」无从判断。
 *   三、算得对 —— 这里要的是**与日志对账**：响应里的总额必须等于本次请求
 *       所有 [LLM]/[Rerank]/[Embed] 行的 token 之和。响应内部的
 *       「总额 = 分项之和」是白送的（它由同一份数据算出），抓不到漏记一类的问题；
 *       日志是每次调用各自写的，与账本相互独立，两边的数对得上才算真的没漏。
 *   四、说得清 —— 金额必须带「≈」和估算说明；没配单价的模型必须点名而不是算成 0。
 *
 * 前置条件：后端九个服务 + 前端 dev server 已启动。每一问都要调模型，整轮约 30–60 秒。
 *
 * 用法：
 *   node scripts/verify-usage.mjs
 */
import { mkdirSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { chromium } from 'playwright-core'
import { logLines } from './lib/logs.mjs'

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
    await new Promise((r) => setTimeout(r, 500))
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

/** 请求标识带上，事后才能把这一次请求在日志里的每一行捞出来对账 */
async function askApi(message, requestId) {
  const res = await fetch(`${GW}/ai/chat`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${session.token}`,
      'X-Request-Id': requestId,
    },
    body: JSON.stringify({ sessionId: `verify-usage-${Date.now()}`, message }),
  })
  const body = await res.json()
  if (body.code !== 200) throw new Error(`对话失败：${body.msg}`)
  return body.data
}

/**
 * 日志行里的 token 加总：模型调用认 promptTokens/completionTokens，检索侧认 tokens=。
 *
 * 「本轮用量」那一行必须跳过 —— 它是这一轮所有调用的汇总，与逐条明细是同一批数字的两种粒度。
 * 一起加进去，对账结果会不多不少正好翻倍，而两倍这个数看起来还挺像个合理的 token 数。
 */
function tokensIn(lines) {
  let total = 0
  for (const line of lines) {
    if (line.includes('本轮用量')) {
      continue
    }
    const llm = line.match(/promptTokens=(\d+) completionTokens=(\d+)/)
    if (llm) {
      total += Number(llm[1]) + Number(llm[2])
      continue
    }
    const other = line.match(/tokens=(\d+)/)
    if (other) {
      total += Number(other[1])
    }
  }
  return total
}

// ─────────── 一、接口契约 ───────────
console.log('\n一、接口契约（知识型问题，会走检索）')
const usageRequestId = `verifyusage-${Date.now()}`
const hit = await askApi('帮我推荐几款乳清蛋白粉', usageRequestId)
const usage = hit.usage
ck('返回里带 usage', Boolean(usage), JSON.stringify(hit.usage))
ck('总 token 大于零', usage?.totalTokens > 0, `totalTokens=${usage?.totalTokens}`)
ck(
  '金额大于零',
  typeof usage?.costCny === 'number' && usage.costCny > 0,
  `costCny=${usage?.costCny}`,
)

const models = (usage?.models ?? []).map((m) => m.model)
ck('按模型拆开明细', models.length > 1, models.join('/'))
ck(
  '只统计对话模型是不够的 —— 检索侧的重排必须在明细里',
  models.some((m) => /rerank/.test(m)),
  `实际明细：${models.join('/')}。缺了它说明这个数字只覆盖了模型调用链的一部分`,
)
ck(
  '向量化用量也在明细里',
  models.some((m) => /embedding/.test(m)),
  `实际明细：${models.join('/')}`,
)

// ─────────── 二、与日志对账（真正的证据在这里） ───────────
console.log('\n二、与日志对账')
const lines = await logLines('ai-service', usageRequestId, 6000)
ck('拿到了这次请求的日志行', lines.length > 0, `requestId=${usageRequestId}，一行都没捞到`)
const llmLines = lines.filter((l) => l.includes('[LLM]'))
const rerankLines = lines.filter((l) => l.includes('[Rerank]'))
const embedLines = lines.filter((l) => l.includes('[Embed]'))
const fromLog = tokensIn(lines)
ck(
  '账本总额等于日志里每次调用之和',
  fromLog === usage?.totalTokens,
  `日志合计 ${fromLog}（[LLM] ${llmLines.length} 行 / [Rerank] ${rerankLines.length} 行 / [Embed] ${embedLines.length} 行）`
    + ` vs 响应里的 ${usage?.totalTokens}。差额为正说明有调用没进账本（数字偏小且看不出来）`,
)
ck(
  '检索侧的两类调用都留下了日志',
  rerankLines.length > 0 && embedLines.length > 0,
  `[Rerank] ${rerankLines.length} 行 / [Embed] ${embedLines.length} 行`,
)

// ─────────── 浏览器部分 ───────────
const browser = await chromium.launch({ executablePath: CHROMIUM })
const context = await browser.newContext({ viewport: { width: 1440, height: 1200 } })
const page = await context.newPage()
const problems = []
page.on('console', (m) => m.type() === 'error' && problems.push(m.text()))
page.on('pageerror', (e) => problems.push('pageerror: ' + e.message))

await page.addInitScript(
  ([key, value]) => localStorage.setItem(key, value),
  ['user', JSON.stringify({ token: session.token, profile: session.user })],
)

mkdirSync(OUT_DIR, { recursive: true })
await page.goto(`${BASE}/#/assistant`, { waitUntil: 'networkidle', timeout: 30000 })

console.log('\n三、界面')
const before = await page.locator('.message-card.assistant').count()
await page.locator('.composer textarea').fill('帮我推荐几款乳清蛋白粉')
await page.getByRole('button', { name: /发送消息/ }).click()
const card = page.locator('.message-card.assistant').nth(before)
await card.waitFor({ state: 'visible', timeout: 20000 })
// 等生成结束：判据是流式指示点消失。发送按钮不能用 —— 它带 `!input.trim()` 禁用条件，
// 输入框发送后就被清空，流结束了按钮仍然是禁用的
const live = page.locator('.message-live')
await live.waitFor({ state: 'visible', timeout: 15000 }).catch(() => {})
await live.waitFor({ state: 'detached', timeout: 180000 })
await page.screenshot({ path: resolve(OUT_DIR, 'usage-1.png'), fullPage: true })

const details = card.locator('details.usage')
ck('渲染出用量块', (await details.count()) > 0, '卡片里没有 details.usage')
if ((await details.count()) > 0) {
  const summary = await details.locator('summary').innerText()
  ck('收起时就能看到 token 数', /tokens/.test(summary), `summary：${summary}`)
  ck(
    '金额带「≈」',
    /≈\s*¥/.test(summary),
    `summary：${summary} —— 不带 ≈ 的金额会被当成事实引用，而它只是按配置单价估的`,
  )

  await details.locator('summary').click()
  const rows = await details.locator('.usage__row').allInnerTexts()
  ck('展开后有按模型的明细', rows.length > 0, `明细行：${JSON.stringify(rows)}`)
  const notes = await details.locator('.usage__note').allInnerTexts()
  ck(
    '写明是估算，以服务商账单为准',
    notes.some((n) => /估算/.test(n) && /账单/.test(n)),
    `说明文字：${JSON.stringify(notes)}`,
  )
  await page.screenshot({ path: resolve(OUT_DIR, 'usage-2.png'), fullPage: true })
}

ck('全程无控制台报错', problems.length === 0, problems.slice(0, 3).join(' | '))

await browser.close()

console.log(`\n${pass} 通过 / ${fail} 失败`)
console.log(`截图：${OUT_DIR}`)
process.exit(fail === 0 ? 0 : 1)
