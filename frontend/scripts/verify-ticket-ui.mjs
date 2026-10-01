/**
 * C 端工单前端（批次 13e）的端到端验收 —— 真浏览器 + 真接口。
 *
 * 这一批新建的是**界面**，所以下面这些断言只能在浏览器里成立，
 * 接口层的验收（`/tickets/**` 八个接口）证明不了它们：
 *
 *   一、**从订单详情能就地发起工单**，且工单里带上那一单。对话框是共用组件、
 *       三个入口各传各的订单，传漏一个不会报错，只是工单变成「哪一笔？」。
 *   二、**列表页的球权文案与真实状态对得上**。「等客服回复 / 客服已回复 / 等你确认 /
 *       已结束」是从 `status` + `lastReplyBy` 推出来的，推错时页面照样渲染得挺好看。
 *   三、**关闭之后输入框就没了**，而不是让用户打完字再吃一个 409。
 *   四、**顶栏角标只在客服回过话时出现**，且客服回复后刷新能看到它 ——
 *       这是「等你回应」这个语义第一次被端到端验证。
 *   五、**分类文案不分叉**。表单里的四个中文名与后端 `TicketCategory.text()` 是两份
 *       互相独立维护的字面量，本脚本把 `src/api/ticket.ts` 里的表读出来逐个建单比对。
 *
 * 数据影响：会以 `alice`（u1001）的身份建若干条工单，并在结束时删掉它们
 * （工单在库里没有删除接口，收尾走 SQL 直删；见 `cleanup`）。
 *
 * 前置条件：后端九个服务 + 前端 dev server（5173）已启动。
 *
 * 用法：
 *   node scripts/verify-ticket-ui.mjs
 */
import { execFileSync } from 'node:child_process'
import { mkdirSync, readFileSync } from 'node:fs'
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
const MYSQL = process.env.VERIFY_MYSQL ?? 'E:/Tool/mysql-8.0.31-winx64/bin/mysql.exe'

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

/** 多行 SQL 要折成一行：cmd.exe 会在第一个换行处截断带引号的 -e 参数（ERROR 1064） */
function sql(statement) {
  const flat = statement.replace(/\s+/g, ' ').trim()
  return execFileSync(
    MYSQL,
    [
      '-h127.0.0.1',
      '-P3306',
      '-uyumefusaka',
      '-pj',
      '-N',
      '--default-character-set=utf8mb4',
      'envoymart_order',
      '-e',
      flat,
    ],
    { encoding: 'utf8' },
  ).trim()
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

const adminSession = await (async () => {
  const res = await fetch(`${GW}/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: 'admin', password: '123456' }),
  })
  const body = await res.json()
  if (body.code !== 200) throw new Error(`管理员登录失败：${body.msg}`)
  return body.data
})()

const authHeaders = { 'Content-Type': 'application/json', Authorization: `Bearer ${session.token}` }
const adminHeaders = {
  'Content-Type': 'application/json',
  Authorization: `Bearer ${adminSession.token}`,
}

/** 接口调用：**不抛异常**，连业务码一起回，让断言自己去判 */
async function api(path, { method = 'GET', body, asAdmin = false } = {}) {
  const res = await fetch(`${GW}${path}`, {
    method,
    headers: asAdmin ? adminHeaders : authHeaders,
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  return res.json()
}

/** 本轮建出来的工单号，收尾时按号删除（不留测试垃圾在演示库里） */
const createdIds = []

async function createTicket(payload) {
  const result = await api('/tickets', { method: 'POST', body: payload })
  if (result.code !== 200) throw new Error(`建单失败：${result.msg}`)
  createdIds.push(result.data.ticket.id)
  return result.data
}

mkdirSync(OUT_DIR, { recursive: true })
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

// ─────────── 一、订单详情页发起工单 ───────────
console.log('\n一、订单详情页：就地发起工单并带上这一单')
// `/orders` 直接回数组（它不分页，见 OrderController），`/tickets` 才是 PageResult
// `/orders` 自批次 13f 起也回 PageResult（订单页签收口把分页加了进去），
// 不再直接回数组——脚本创建于 13e，此处当时是对的，13e 之后没再跑过全量回归，
// 漏掉了这个跨批接缝：接口换形态，脚本也是消费方
const orders = (await api('/orders')).data?.records ?? []
if (!orders.length) throw new Error('alice 名下没有订单，无法从订单详情发起工单')
const order = orders[0]

await page.goto(`${BASE}/#/orders/${order.id}`, { waitUntil: 'networkidle' })
await page.waitForSelector('.bar__actions', { timeout: 15000 })
const supportBtn = page.getByRole('button', { name: '联系客服' })
ck('订单详情底栏有「联系客服」', (await supportBtn.count()) === 1)

await supportBtn.click()
await page.waitForSelector('.el-dialog', { timeout: 5000 })
ck('对话框弹出', await page.locator('.el-dialog').isVisible())
ck(
  '对话框里显示了关联订单号',
  (await page.locator('.el-dialog .linked strong').textContent())?.trim() === order.orderNo,
)
ck(
  '带订单进来时默认选中「订单问题」',
  (await page.locator('.el-dialog .cat.is-active .cat__label').textContent()) === '订单问题',
)

await page.locator('.el-dialog input[placeholder="一句话说清问题"]').fill('13e 验收：订单问题')
await page
  .locator('.el-dialog textarea')
  .fill('这条工单由验收脚本发起，用来验证「订单页就地发起」这条链路。')
await page.getByRole('button', { name: '提交工单' }).click()
await page.waitForURL(/#\/tickets\/\d+/, { timeout: 15000 })
const createdFromOrder = Number(page.url().match(/#\/tickets\/(\d+)/)[1])
createdIds.push(createdFromOrder)
ck('提交后跳进这条工单的会话页', Number.isInteger(createdFromOrder), page.url())

const detailFromOrder = (await api(`/tickets/${createdFromOrder}`)).data
ck(
  '工单挂上了这张订单',
  detailFromOrder.ticket.orderId === order.id,
  `实得 ${detailFromOrder.ticket.orderId}`,
)
ck('订单号也带上了（客服不用再问是哪一笔）', detailFromOrder.ticket.orderNo === order.orderNo)
ck(
  '第一条消息就是刚写的描述',
  detailFromOrder.messages.length === 1 && detailFromOrder.messages[0].senderType === 'USER',
)
await page.screenshot({ path: `${OUT_DIR}/13e-from-order.png`, fullPage: true })

// ─────────── 二、分类文案不分叉 ───────────
console.log('\n二、分类文案：前端表单的四个中文名 vs 后端下发的 categoryText')
const apiSource = readFileSync(resolve(HERE, '../src/api/ticket.ts'), 'utf8')
const optionBlock = apiSource.slice(apiSource.indexOf('TICKET_CATEGORY_OPTIONS'))
const frontendLabels = [...optionBlock.matchAll(/value: '([A-Z]+)', label: '([^']+)'/g)].map(
  (m) => [m[1], m[2]],
)
ck('从前端常量里读到四个分类', frontendLabels.length === 4, JSON.stringify(frontendLabels))

for (const [value, label] of frontendLabels) {
  const created = await createTicket({
    category: value,
    title: `13e 验收：${label}`,
    content: `${label} 分类文案比对`,
  })
  ck(
    `${value} 的中文名一致（${label}）`,
    created.ticket.categoryText === label,
    `后端回的是 ${created.ticket.categoryText}`,
  )
}
// 非法分类：服务端必须 400 而不是静默落到「其他」——静默落的话，前端拼错的分类
// 会在客服那边显示成「其他」，没人会发现
const bad = await api('/tickets', {
  method: 'POST',
  body: { category: 'NOPE', title: 'x', content: 'y' },
})
ck('非法分类被 400 拒绝（不静默落到「其他」）', bad.code === 400, `实得 ${bad.code} ${bad.msg}`)

// ─────────── 三、列表页 ───────────
console.log('\n三、列表页：全部分类都在、球权文案与状态对得上')
await page.goto(`${BASE}/#/tickets`, { waitUntil: 'networkidle' })
await page.waitForSelector('.item', { timeout: 15000 })
const summaryBefore = (await api('/tickets/summary')).data
const tabTexts = await page.locator('.tickets__tab').allTextContents()
ck(
  '五个页签的括号数字与 /tickets/summary 一致',
  tabTexts.join('|').replace(/\s/g, '') ===
    `全部${summaryBefore.total}|待处理${summaryBefore.open}|处理中${summaryBefore.processing}|已解决${summaryBefore.resolved}|已关闭${summaryBefore.closed}`,
  tabTexts.join(' | '),
)
// 按工单号定位刚建的那条，而不是取第一条：列表按 updatedAt 倒序，
// 而本节上面又建了四条分类工单，第一条必然不是它
const ownItem = page.locator('.item').filter({ hasText: detailFromOrder.ticket.ticketNo })
ck('刚建的工单出现在列表里', (await ownItem.count()) === 1)
ck(
  '列表上标着「关联订单」',
  (await ownItem.locator('.item__order').textContent())?.includes(order.orderNo),
  await ownItem.locator('.item__order').textContent(),
)
ck(
  '没人回过话时球权文案是「等客服回复」',
  (await ownItem.locator('.item__turn').textContent())?.trim() === '等客服回复',
  await ownItem.locator('.item__turn').textContent(),
)

// 按状态筛选：切到「待处理」应只剩 OPEN 的
await page.getByRole('button', { name: /^待处理/ }).click()
await page.waitForTimeout(800)
const openRows = await page.locator('.item').count()
// 列表一页十条，OPEN 有十几条时只能看到第一页——所以比的是 min(计数, 页大小)
ck(
  '「待处理」页签筛出的是 OPEN 工单',
  openRows === Math.min(summaryBefore.open, 10),
  `列表 ${openRows} 条 / 计数 ${summaryBefore.open}`,
)
const rowStatuses = await page.locator('.item .el-tag--warning').count()
ck(
  '筛出来的每一条状态都是「待处理」',
  rowStatuses === openRows,
  `待处理标签 ${rowStatuses} / 行数 ${openRows}`,
)
await page.screenshot({ path: `${OUT_DIR}/13e-list.png`, fullPage: true })

// ─────────── 四、会话页：追加说明 → 球权翻转 ───────────
console.log('\n四、会话页：追加说明后球权到客服那边')
await page.goto(`${BASE}/#/tickets/${createdFromOrder}`, { waitUntil: 'networkidle' })
await page.waitForSelector('.compose textarea', { timeout: 15000 })
ck('会话流里有一条「我」的消息', (await page.locator('.bubble--user').count()) === 1)
ck(
  '消息脚下写了「我」',
  (await page.locator('.bubble__who').first().textContent())?.trim() === '我',
)

await page.locator('.compose textarea').fill('补充一句：配送地址不方便收件。')
await page.getByRole('button', { name: '发送' }).click()
await page.waitForFunction(() => document.querySelectorAll('.bubble').length === 2, null, {
  timeout: 15000,
})
ck('追加说明后消息流变成两条', (await page.locator('.bubble').count()) === 2)

// 客服回话：状态推到 PROCESSING，球权到用户这边
const replied = await api(`/tickets/admin/tickets/${createdFromOrder}/reply`, {
  method: 'POST',
  body: { content: '收到，正在帮你联系配送站。' },
  asAdmin: true,
})
ck('客服回复成功', replied.code === 200, replied.msg)

await page.reload({ waitUntil: 'networkidle' })
await page.waitForSelector('.bubble--admin', { timeout: 15000 })
ck('刷新后客服那条出现了（靠左）', (await page.locator('.bubble--admin').count()) === 1)
ck(
  '客服消息不显示内部账号 id',
  (await page.locator('.bubble--admin .bubble__who').textContent())?.trim() === '客服',
)
ck(
  '状态标签推到了「处理中」',
  (await page.locator('.head__tags .el-tag').first().textContent())?.trim() === '处理中',
  await page.locator('.head__tags .el-tag').first().textContent(),
)
await page.screenshot({ path: `${OUT_DIR}/13e-conversation.png`, fullPage: true })

// ─────────── 五、顶栏角标 ───────────
console.log('\n五、顶栏角标：客服回过话才亮，且刷新能看到')
const summaryAfterReply = (await api('/tickets/summary')).data
ck(
  '接口侧 awaitingMe 计到了这条',
  summaryAfterReply.awaitingMe >= 1,
  `实得 ${summaryAfterReply.awaitingMe}`,
)

await page.goto(`${BASE}/#/shop`, { waitUntil: 'networkidle' })
await page.waitForSelector('.app-nav', { timeout: 15000 })
// 顶栏那一行；抽屉里的同一块角标在下面单独看
const badge = page.locator('.app-header__sub .app-nav__link:has-text("我的工单") .app-nav__badge')
// 等角标出现而不是立刻读：`refresh()` 是挂载后异步发的，读到的是上一帧
await badge.waitFor({ state: 'visible', timeout: 15000 }).catch(() => {})
ck('商城页顶栏的「我的工单」挂上了角标', await badge.isVisible().catch(() => false))
ck(
  '角标数字与接口一致',
  (await badge.textContent())?.trim() === String(summaryAfterReply.awaitingMe),
  `角标 ${await badge.textContent()} / 接口 ${summaryAfterReply.awaitingMe}`,
)
// 抽屉里的文字角标走另一条渲染路径，一起看一眼
await page.setViewportSize({ width: 820, height: 1000 })
await page.locator('.app-menu-toggle').click()
await page.waitForSelector('.app-nav--drawer', { timeout: 5000 })
const drawerBadge = page.locator('.app-nav--drawer .app-nav__badge').first()
ck('抽屉里也带角标', await drawerBadge.isVisible().catch(() => false))
await page.setViewportSize({ width: 1440, height: 1000 })
await page.keyboard.press('Escape')
await page.screenshot({ path: `${OUT_DIR}/13e-badge.png` })

// ─────────── 六、用户关闭 → 输入框消失 ───────────
console.log('\n六、关闭工单：界面立刻不给打字，而不是打完再吃 409')
await page.goto(`${BASE}/#/tickets/${createdFromOrder}`, { waitUntil: 'networkidle' })
await page.waitForSelector('.compose textarea', { timeout: 15000 })
await page.getByRole('button', { name: '关闭工单' }).click()
await page.waitForSelector('.el-message-box', { timeout: 5000 })
await page.locator('.el-message-box__btns .el-button--primary').click()
await page.waitForSelector('.notice--closed', { timeout: 15000 })
ck('出现「工单已关闭」提示', await page.locator('.notice--closed').isVisible())
ck('输入框消失', (await page.locator('.compose textarea').count()) === 0)
ck(
  '状态标签变成「已关闭」',
  (await page.locator('.head__tags .el-tag').first().textContent())?.trim() === '已关闭',
)
// 限定在操作区里数：确认框那一下也带「关闭工单」四个字，且它关掉后仍留在 DOM 里
ck(
  '关闭后操作区不再显示关闭按钮',
  (await page.locator('.head__actions button:has-text("关闭工单")').count()) === 0,
)
ck(
  '操作区只剩「刷新」',
  (await page.locator('.head__actions button').count()) === 1,
  await page.locator('.head__actions').innerText(),
)
await page.screenshot({ path: `${OUT_DIR}/13e-closed.png`, fullPage: true })

const closedDetail = (await api(`/tickets/${createdFromOrder}`)).data
ck(
  '服务端也是 CLOSED 且记了原因',
  closedDetail.ticket.status === 'CLOSED' && !!closedDetail.ticket.closeReason,
  closedDetail.ticket.closeReason ?? '',
)

// 关掉之后硬发一条：必须 409，且是那句能读懂的话（不是「服务暂时不可用」）
const blocked = await api(`/tickets/${createdFromOrder}/messages`, {
  method: 'POST',
  body: { content: '还能说话吗' },
})
ck('关闭后追加消息被 409 挡住', blocked.code === 409, `实得 ${blocked.code} ${blocked.msg}`)
ck('错误信息是给用户看的那句', (blocked.msg ?? '').includes('已关闭'), blocked.msg ?? '')

// ─────────── 七、客服标记解决 → 用户重开 ───────────
console.log('\n七、已解决 → 「还有问题」重开回到处理队列')
const second = await createTicket({
  category: 'REFUND',
  title: '13e 验收：重开链路',
  content: '退款还没到账。',
})
const resolved = await api(`/tickets/admin/tickets/${second.ticket.id}/resolve`, {
  method: 'POST',
  body: { content: '已为你加急，预计今天到账。' },
  asAdmin: true,
})
ck('客服标记已解决', resolved.code === 200, resolved.msg)

await page.goto(`${BASE}/#/tickets/${second.ticket.id}`, { waitUntil: 'networkidle' })
await page.waitForSelector('.notice--resolved', { timeout: 15000 })
ck('出现「已解决」提示条', await page.locator('.notice--resolved').isVisible())
const actionNames = await page.locator('.head__actions button').allTextContents()
ck(
  '操作区同时给出「确认解决」与「还有问题」',
  actionNames.join('|').includes('确认解决') && actionNames.join('|').includes('还有问题'),
  actionNames.join(' '),
)

await page.getByRole('button', { name: '还有问题' }).click()
await page.waitForSelector('.el-message-box', { timeout: 5000 })
await page.locator('.el-message-box textarea, .el-message-box input').first().fill('退款仍未到账。')
await page.locator('.el-message-box__btns .el-button--primary').click()
await page.waitForFunction(
  () => document.querySelector('.head__tags .el-tag')?.textContent?.trim() === '处理中',
  null,
  { timeout: 15000 },
)
ck('重开后状态回到「处理中」', true)
const reopened = (await api(`/tickets/${second.ticket.id}`)).data
ck(
  '重开时写下的那句话进了消息流',
  reopened.messages.some((m) => m.content === '退款仍未到账。'),
)
ck(
  '球权回到用户这边（客服队列里看得见）',
  reopened.ticket.lastReplyBy === 'USER',
  reopened.ticket.lastReplyBy ?? '',
)
await page.screenshot({ path: `${OUT_DIR}/13e-reopened.png`, fullPage: true })

// ─────────── 八、未登录态 ───────────
console.log('\n八、未登录态：工单是私事，挡在登录页')
const anonContext = await browser.newContext({ viewport: { width: 1440, height: 1000 } })
const anonPage = await anonContext.newPage()
anonPage.on('pageerror', (e) => problems.push(`[匿名] pageerror: ${e.message}`))
await anonPage.goto(`${BASE}/#/tickets`, { waitUntil: 'networkidle' })
await anonPage.waitForSelector('.el-form, .page', { timeout: 15000 })
ck('未登录访问工单列表被挡在登录页', anonPage.url().includes('#/login'), anonPage.url())
ck(
  '未登录时顶栏不显示「我的工单」',
  (await anonPage.locator('.app-nav__link:has-text("我的工单")').count()) === 0,
)
await anonContext.close()

// ─────────── 九、控制台 ───────────
console.log('\n九、控制台')
const realProblems = problems.filter((p) => !p.includes('favicon'))
ck('没有控制台报错', realProblems.length === 0, realProblems.slice(0, 3).join(' | '))

// ─────────── 收尾 ───────────
// 工单没有删除接口（客服侧的作废走的是状态机），测试数据只能直删库。
// 只删本轮 createdIds 里的：脚本跑在演示库上，误删一条演示工单是要出事的
if (createdIds.length) {
  sql(`delete from support_ticket_message where ticket_id in (${createdIds.join(',')})`)
  sql(`delete from support_ticket where id in (${createdIds.join(',')})`)
  console.log(`\n（已清理本轮建的 ${createdIds.length} 条测试工单）`)
}

console.log(`\n===== 工单界面验收：${pass} 通过 / ${fail} 失败 =====`)
await browser.close()
process.exit(fail ? 1 : 0)
