/**
 * 对话体验（批次 10）的界面验收。
 *
 * 这一批改的全是「看起来会动、错了也不报错」的东西，所以验的不能是"页面上有字"：
 *
 *   一、Markdown 渲染管线 —— 直接 import 应用自己的 `renderMarkdown` 断言**输出 HTML**，
 *       不走模型：表格包了滚动容器吗？代码块有复制按钮吗？角标越界时退化成原文吗？
 *       模型输出里的 `<script>`/`onerror` 真的被挡住吗？这些用真模型没法稳定复现，
 *       而它们恰恰是安全边界与渲染正确性所在。
 *   二、会话链路的**持久化真相** —— 切换会话看到的引用卡片与用量明细，
 *       必须来自 Redis 里存下的整轮响应，而不是内存残留。判据：切走再切回、
 *       刷新页面之后，`.citation` 与 `details.usage` 仍在。
 *   三、删除是**真删** —— 界面移除之后，再用接口拉一次列表对账，确认服务端也没了。
 *   四、流式态与滚动锚定 —— 生成中要有流式标记；用户上翻之后不能被自动滚动拽回底部。
 *
 * 前置条件：后端服务 + 前端 dev server 已启动。会真实调用 2 次模型对话，约 1–2 分钟。
 *
 * 用法：
 *   node scripts/verify-chat-ui.mjs
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

async function poll(fn, predicate, timeoutMs = 30000) {
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

async function apiSessions() {
  const res = await fetch(`${GW}/ai/sessions`, {
    headers: { Authorization: `Bearer ${session.token}` },
  })
  const body = await res.json()
  return body.data ?? []
}

const browser = await chromium.launch({ executablePath: CHROMIUM })
const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } })
// 复制按钮走 navigator.clipboard，无权限时 writeText 会被拒 —— 授权是为了测真实行为，
// 而不是让测试绕过它
await context.grantPermissions(['clipboard-write'], { origin: BASE })
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

// ─────────── 一、Markdown 渲染管线（确定性，不经过模型） ───────────
console.log('\n一、Markdown 渲染管线（直接断言 renderMarkdown 的输出）')
const mdChecks = await page.evaluate(async () => {
  // dev server 直接提供 TS 模块，这里用的就是应用运行时同一份代码
  const { renderMarkdown } = await import('/src/utils/markdown.ts')
  const out = {}
  const table = renderMarkdown('| 商品 | 价格 |\n| --- | --- |\n| 甲 | 9 元 |', 0)
  out.tableWrapped = table.includes('md-table-scroll') && table.includes('<table>')
  const fence = renderMarkdown('```js\nconst a = 1\n```', 0)
  out.codeWrapped = fence.includes('md-code') && fence.includes('data-code-copy')
  const list = renderMarkdown('- 甲\n- 乙\n\n1. 一\n2. 二', 0)
  out.listRendered = list.includes('<ul>') && list.includes('<ol>') && list.includes('<li>')
  out.strongRendered = renderMarkdown('**重点**', 0).includes('<strong>')
  const badge = renderMarkdown('满 199 减 30 [1]，另有店铺券 [2]。', 2)
  out.badgeOk = badge.includes('message-content__cite') && badge.includes('data-cite="1"')
  const overflow = renderMarkdown('这条编号不存在 [7]。', 2)
  out.badgeOverflowLiteral = overflow.includes('[7]') && !overflow.includes('data-cite="7"')
  const img = renderMarkdown('<img src=x onerror=alert(1)>', 0)
  out.xssImgBlocked = !img.includes('<img')
  const script = renderMarkdown('<script>alert(1)</script>', 0)
  out.xssScriptBlocked = !script.includes('<script')
  const link = renderMarkdown('[官网](https://example.com)', 0)
  out.linkSafe = link.includes('target="_blank"') && link.includes('noopener')
  return out
})
ck('表格包进横向滚动容器', mdChecks.tableWrapped)
ck('代码块带复制按钮', mdChecks.codeWrapped)
ck('有序/无序列表渲染成列表', mdChecks.listRendered)
ck('加粗渲染成 strong', mdChecks.strongRendered)
ck('有效角标 [1] 渲染成按钮', mdChecks.badgeOk)
ck('越界角标 [7] 按原文保留（不给出点不动的按钮）', mdChecks.badgeOverflowLiteral)
ck('模型输出里的 <img onerror> 被挡（html:false）', mdChecks.xssImgBlocked)
ck('模型输出里的 <script> 被挡', mdChecks.xssScriptBlocked)
ck('外链新开页且带 noopener', mdChecks.linkSafe)

// ─────────── 二、真实对话：流式态、富文本、角标 ───────────
console.log('\n二、真实对话（第一段会话）')
await page.getByRole('button', { name: /新对话/ }).click()
ck('新对话展示空态引导', await page.locator('.chat-empty').isVisible())
ck('空态没有任何消息卡片', (await page.locator('.message-card').count()) === 0)
ck('空态给出快捷提问', (await page.locator('.quick-prompts button').count()) > 0)

const question = '平台的满减活动规则是什么？请用 Markdown 列表说明，并标注依据编号'
await page.locator('.composer textarea').fill(question)
await page.getByRole('button', { name: '发送消息' }).click()
ck('用户消息是独立气泡', (await page.locator('.message-card.user').count()) === 1)

// 流式态：等模型吐出第一个 delta
await page.locator('.message-content.is-streaming').waitFor({ state: 'attached', timeout: 60000 })
ck('生成过程中带流式态标记', true)
ck('消息头显示「正在生成」', await page.locator('.message-live').isVisible())
await page.screenshot({ path: resolve(OUT_DIR, 'chat-1-streaming.png') })

// 完成信号：生成中才存在的「停止生成」按钮消失
await page.getByRole('button', { name: '停止生成' }).waitFor({ state: 'detached', timeout: 180000 })
await poll(
  () => page.locator('.message-content.is-streaming').count(),
  (c) => c === 0,
  5000,
)
ck('生成结束后流式态消失', (await page.locator('.message-content.is-streaming').count()) === 0)

const card = page.locator('.message-card.assistant').first()
ck(
  '回答以 Markdown 结构渲染（列表或标题）',
  (await card.locator('.message-content ul, .message-content ol, .message-content h2').count()) > 0,
  '回答里没有任何块级结构 —— 富文本渲染可能没生效',
)
const badgeCount = await card.locator('.message-content__cite').count()
ck('回答里带可点角标', badgeCount > 0, '一条 [n] 角标都没有')
ck('引用卡片同时渲染', (await card.locator('.citation').count()) > 0)
ck('用量块同时渲染', (await card.locator('details.usage').count()) > 0)
await page.screenshot({ path: resolve(OUT_DIR, 'chat-2-answer.png'), fullPage: true })

if (badgeCount > 0) {
  await card.locator('.message-content__cite').first().click()
  // 角标与引用卡是一一对应的：点亮的是同号那张
  const activeIndex = await page.evaluate(() => {
    const nodes = [...document.querySelectorAll('.citation')]
    return nodes.findIndex((n) => n.classList.contains('is-active'))
  })
  ck('点击角标点亮对应引用卡', activeIndex >= 0, `activeIndex=${activeIndex}`)
}

// 复制整条回答
await card.locator('.message-copy').hover()
await card.locator('.message-copy').click()
ck('复制按钮给出「已复制」反馈', (await card.locator('.message-copy').innerText()) === '已复制')

// ─────────── 三、滚动锚定 ───────────
console.log('\n三、滚动锚定')
const scroller = page.locator('.chat-scroll')
await scroller.evaluate((el) => el.scrollTo({ top: 0 }))
await page.locator('.chat-jump').waitFor({ state: 'visible', timeout: 5000 })
ck('上翻后出现「回到底部」且不强行跟随', true)
await page.locator('.chat-jump').click()
const backAtBottom = await poll(
  () =>
    scroller.evaluate((el) => el.scrollHeight - el.scrollTop - el.clientHeight < 80),
  (v) => v === true,
  10000,
)
ck('点击后回到底部', backAtBottom === true)
ck('回到底部后按钮消失', (await page.locator('.chat-jump').count()) === 0)

// ─────────── 四、多会话：侧栏、切换、刷新恢复 ───────────
console.log('\n四、多会话')
// 侧栏可能有更早批次留下的会话，等的是「这一段」出现，不是「有会话」
const titles = await poll(
  () => page.locator('.session-list__title').allInnerTexts(),
  (all) => all.some((t) => t.startsWith('平台的满减')),
  15000,
)
const firstTitle = titles.find((t) => t.startsWith('平台的满减')) ?? ''
ck('第一段会话进入侧栏', firstTitle.length > 0, `侧栏标题：${titles.join(' / ')}`)
ck('标题取自首条用户消息', firstTitle.startsWith('平台的满减'), `标题：${firstTitle}`)

await page.getByRole('button', { name: /新对话/ }).click()
ck('新对话回到空态', await page.locator('.chat-empty').isVisible())
await page.locator('.composer textarea').fill('你好，请用一句话说明你能做什么')
await page.getByRole('button', { name: '发送消息' }).click()
await page.getByRole('button', { name: '停止生成' }).waitFor({ state: 'detached', timeout: 120000 })
const titles2 = await poll(
  () => page.locator('.session-list__title').allInnerTexts(),
  (all) => all.some((t) => t.startsWith('你好')),
  15000,
)
ck(
  '第二段会话也进入侧栏',
  titles2.some((t) => t.startsWith('你好')),
  `侧栏标题：${titles2.join(' / ')}`,
)

// 切回第一段：引用卡片与用量块必须从服务端历史里恢复
await page
  .locator('.session-list__main')
  .filter({ hasText: firstTitle })
  .first()
  .click()
await poll(() => page.locator('.message-card.assistant').count(), (c) => c >= 1, 15000)
const restoredBadges = await page.locator('.message-content__cite').count()
ck('切回后历史消息恢复', (await page.locator('.message-card.assistant').count()) >= 1)
ck('历史里的角标是可点按钮', restoredBadges > 0, '恢复的正文里没有角标 —— 可能只存了纯文本')
ck('历史里的引用卡片一并恢复', (await page.locator('.citation').count()) > 0)
ck('历史里的用量块一并恢复', (await page.locator('details.usage').count()) > 0)
await page.screenshot({ path: resolve(OUT_DIR, 'chat-3-history.png'), fullPage: true })

// 刷新：会话指针要从 sessionStorage 指回同一段对话
await page.reload({ waitUntil: 'networkidle' })
await poll(() => page.locator('.message-card.assistant').count(), (c) => c >= 1, 30000)
ck('刷新后自动回到上次的会话', (await page.locator('.message-card.assistant').count()) >= 1)
ck('刷新后引用卡片仍在', (await page.locator('.citation').count()) > 0)

// ─────────── 五、删除会话（界面 + 接口双向对账） ───────────
console.log('\n五、删除会话')
const sessionsBefore = await apiSessions()
const victim = sessionsBefore.find((s) => s.title.startsWith('你好'))
ck('接口里能找到第二段会话', Boolean(victim), `当前会话：${sessionsBefore.map((s) => s.title).join(' / ')}`)
if (victim) {
  const row = page.locator('.session-list__item').filter({ hasText: '你好' }).first()
  await row.hover()
  await row.locator('.session-list__remove').click()
  // exact 限定到确认框那个「删除」：会话行自己的删除按钮 aria-label 里也含"删除"
  await page.getByRole('button', { name: '删除', exact: true }).click()
  await poll(
    () =>
      page
        .locator('.session-list__item')
        .filter({ hasText: '你好' })
        .count(),
    (c) => c === 0,
    10000,
  )
  ck(
    '删除后侧栏不再有该会话',
    (await page.locator('.session-list__item').filter({ hasText: '你好' }).count()) === 0,
  )
  const sessionsAfter = await apiSessions()
  ck(
    '接口对账：服务端也删掉了',
    !sessionsAfter.some((s) => s.sessionId === victim.sessionId),
    `删除的 id=${victim.sessionId}，接口里${sessionsAfter.some((s) => s.sessionId === victim.sessionId) ? '仍在' : '已不在'}`,
  )
  ck(
    '其余会话不受影响',
    sessionsAfter.length === sessionsBefore.length - 1,
    `删除前 ${sessionsBefore.length} 条，删除后 ${sessionsAfter.length} 条`,
  )
}

// ─────────── 六、生成中删除：服务端不许把会话写回来 ───────────
console.log('\n六、生成中删除（墓碑）')
// 删除与生成撞车是实测复现过的缺陷：SSE 断开不打断服务端那一轮，
// 用户删掉的会话会在几十秒后带着新内容重新出现在侧栏 —— 删除语义等于失效
await page.getByRole('button', { name: /新对话/ }).click()
await page.locator('.composer textarea').fill('平台的退换货政策有哪些？请分点说明')
await page.getByRole('button', { name: '发送消息' }).click()
// 第一轮跑完会话才进侧栏（空会话不落号），随后才有东西可删
await page.getByRole('button', { name: '停止生成' }).waitFor({ state: 'detached', timeout: 180000 })
const doomed = (await apiSessions()).find((s) => s.title.startsWith('平台的退换货'))
ck('新会话已进入侧栏', Boolean(doomed), `当前：${(await apiSessions()).map((s) => s.title).join(' / ')}`)
if (doomed) {
  // 第二轮故意长：趁它还在跑的时候删。**走接口删而不是点界面**——
  // 界面上删活动会话会顺带切走、中断这条流，那正好把要验的时序放跑了
  await page.locator('.composer textarea').fill('请用不少于 500 字详细展开每一条政策，并逐条标注依据编号')
  await page.getByRole('button', { name: '发送消息' }).click()
  await page.getByRole('button', { name: '停止生成' }).waitFor({ state: 'visible', timeout: 30000 })
  const del = await fetch(`${GW}/ai/sessions/${doomed.sessionId}`, {
    method: 'DELETE',
    headers: { Authorization: `Bearer ${session.token}` },
  })
  ck('生成途中删除接口回成功', (await del.json()).code === 200)

  // 等这一轮在界面上跑完 —— 这一刻服务端才落历史，也正是"还魂"会发生的那一刻
  await page.getByRole('button', { name: '停止生成' }).waitFor({ state: 'detached', timeout: 180000 })
  const back = await poll(
    () => apiSessions(),
    (list) => list.some((s) => s.sessionId === doomed.sessionId),
    30000,
  )
  ck(
    '生成结束后会话没有还魂',
    !back.some((s) => s.sessionId === doomed.sessionId),
    `删掉的 id=${doomed.sessionId} 又出现在侧栏了`,
  )
  const msgs = await (
    await fetch(`${GW}/ai/sessions/${doomed.sessionId}/messages`, {
      headers: { Authorization: `Bearer ${session.token}` },
    })
  ).json()
  ck('被删会话读不出任何消息', (msgs.data ?? []).length === 0, `实际 ${(msgs.data ?? []).length} 条`)
}

// ─────────── 七、窄屏：侧栏收进抽屉 ───────────
console.log('\n七、窄屏（500px）')
await page.setViewportSize({ width: 500, height: 900 })
ck('窄屏隐藏固定侧栏', !(await page.locator('.assistant-sidebar').isVisible()))
await page.getByRole('button', { name: '打开会话列表' }).click()
await page.locator('.assistant-drawer .session-list__title').first().waitFor({ timeout: 5000 })
ck('抽屉里能看到会话列表', true)
await page.screenshot({ path: resolve(OUT_DIR, 'chat-4-drawer.png') })
await page.keyboard.press('Escape')

// ─────────── 收尾 ───────────
const ignorable = /favicon|Download the Vue Devtools|ResizeObserver loop/
const realProblems = problems.filter((p) => !ignorable.test(p))
ck('无控制台报错', realProblems.length === 0, realProblems.slice(0, 3).join('\n        '))

console.log(`\n结果：${pass} 通过 / ${fail} 失败`)
await browser.close()
process.exit(fail === 0 ? 0 : 1)
