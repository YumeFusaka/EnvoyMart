/**
 * 高危操作确认链路的端到端验收。
 *
 * 为什么单开一个脚本：这条链路的正确性横跨四层——模型要决定调 order_cancel、
 * 服务端要在**执行前**拦下、前端要渲染出带参数的确认卡片、确认之后要真的执行。
 * 单元测试（ReactToolLoopTest / AgentGraphApprovalTest）只覆盖了中间两层的桩行为，
 * 而「真实模型会不会把工具要出来」「前端点确认之后订单到底动没动」这两头，
 * 只有把整条路跑通才算数。
 *
 * 分两段：
 *   一、API 链路 —— 未确认时拦截（订单必须原封不动），确认后真的取消
 *   二、UI 链路 —— 确认卡片出现、点「确认执行」后订单真的变成已取消
 *
 * 数据影响：每跑一轮新建两笔订单并取消掉（一笔给 API 段、一笔给 UI 段）。
 * 订单表本来就是只增不减的流水，取消是终态，不还原。
 *
 * 前置条件：后端九个服务 + 前端 dev server 已启动，DeepSeek API key 有效
 * （这条链路至少要两次真实模型调用）。
 *
 * 用法：
 *   node scripts/verify-approval-chain.mjs
 */
import { chromium } from 'playwright-core'
import { randomUUID } from 'node:crypto'

const BASE = process.env.VERIFY_BASE ?? 'http://localhost:5173'
const GW = process.env.VERIFY_GW ?? 'http://localhost:8080'
const CHROMIUM =
  process.env.PLAYWRIGHT_CHROMIUM ??
  'C:/Users/j/AppData/Local/ms-playwright/chromium-1223/chrome-win64/chrome.exe'

const buyerCredentials = { username: 't1approval_' + randomUUID().slice(0, 8), password: 'T1' + randomUUID().replaceAll('-', '').slice(0, 24), nickname: '审批链路独立验收' }
const APPROVAL_REJECTED = '原审批签名或用户会话不匹配'

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

/** 轮询到条件成立为止。等的是「状态真的变了」，不是「大概过了多久」 */
async function poll(fn, predicate, timeoutMs = 15000, stepMs = 500) {
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

const login = await (await fetch(`${GW}/auth/register`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify(buyerCredentials),
})).json()
if (login.code !== 200) {
  console.error(`独立验收账号注册失败：${login.msg}`)
  process.exit(1)
}
const auth = { Authorization: `Bearer ${login.data.token}` }

async function api(path, { method = 'GET', body, token, expectedCode = 200, expectedMessage } = {}) {
  const res = await fetch(`${GW}${path}`, {
    method,
    headers: {
      ...auth,
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
    },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  })
  const payload = await res.json()
  if (res.status !== 200 || payload.code !== expectedCode
      || (expectedMessage !== undefined && payload.msg !== expectedMessage)) {
    throw new Error(`${method} ${path} 契约不符：HTTP=${res.status} code=${payload.code} msg=${payload.msg}，期望 HTTP=200 code=${expectedCode}${expectedMessage === undefined ? '' : ` msg=${expectedMessage}`}`)
  }
  return expectedCode === 200 ? payload.data : payload
}

/**
 * 造一笔未支付订单：清空购物车勾选 → 加购 1 号 SKU → 只勾它 → 结算。
 * 结算只结算勾选的条目，先全不勾就与购物车里的历史残留无关。
 */
async function placeUnpaidOrder(tag) {
  const detail = await api('/products/1')
  const skuId = detail.skus[0].id

  try {
    await api('/cart/selection', { method: 'PUT', body: { selected: false } })
  } catch {
    /* 空购物车时该接口可能无事可做，失败不影响后面 */
  }
  const item = await api('/cart/items', { method: 'POST', body: { skuId, quantity: 1 } })
  await api(`/cart/items/${item.id}/selected`, { method: 'PUT', body: { selected: true } })

  const order = await api('/orders/checkout', {
    method: 'POST',
    body: {
      receiverName: '验收脚本',
      receiverPhone: '13800000000',
      receiverProvince: '浙江省',
      receiverCity: '杭州市',
      receiverDistrict: '西湖区',
      receiverDetail: `自动化验收（${tag}）`,
    },
  })
  return order.id
}

const orderStatus = async (id) => (await api(`/orders/${id}`)).status

// ==================== 一、API 链路 ====================
console.log('\n一、API 链路：未确认拦截，确认后执行')

const apiOrderId = await placeUnpaidOrder('api')
ck(`准备未支付订单 #${apiOrderId}`, (await orderStatus(apiOrderId)) === 'CREATED')

const sessionId = `verify-approval-api-${Date.now()}`
const first = await api('/ai/chat', {
  method: 'POST',
  body: { sessionId, message: `帮我取消订单 ${apiOrderId}` },
})

ck(
  '未确认时请求被拦下并下发待确认操作',
  Array.isArray(first.pendingActions) && first.pendingActions.length > 0,
  `pendingActions=${JSON.stringify(first.pendingActions)}，reply=${(first.reply ?? '').slice(0, 120)}`,
)
ck(
  '待确认描述带参数（能看出取消的是哪一单）',
  (first.pendingActions ?? []).some((a) => a.includes(`orderId=${apiOrderId}`)),
  `pendingActions=${JSON.stringify(first.pendingActions)}`,
)
ck('同时下发确认令牌', typeof first.approvalToken === 'string' && first.approvalToken.length > 0)
ck(
  '拦截发生在执行之前：订单状态原封不动',
  (await orderStatus(apiOrderId)) === 'CREATED',
  `status=${await orderStatus(apiOrderId)}`,
)

// 令牌绑定动作：改过的令牌一条都不执行。先试一次被改坏的，再走正常路径——
// 顺序不能反，正常路径会把订单真的取消掉
const tampered = await api('/ai/chat', {
  method: 'POST',
  expectedCode: 400,
  expectedMessage: APPROVAL_REJECTED,
  body: {
    sessionId,
    message: '确认执行',
    // 改一个字符：签名对不上，服务端必须在执行之前停住
    approvalToken: (first.approvalToken[0] === 'A' ? 'B' : 'A') + first.approvalToken.slice(1),
  },
})
ck(
  '被改过一字的令牌一条都不执行',
  (await orderStatus(apiOrderId)) === 'CREATED' &&
    tampered.code === 400,
  `code=${tampered.code}，status=${await orderStatus(apiOrderId)}`,
)

// 换一个会话交回同一张令牌：令牌里签着签发它的会话，此处必须被拒
const crossSession = await api('/ai/chat', {
  method: 'POST',
  expectedCode: 400,
  expectedMessage: APPROVAL_REJECTED,
  body: { sessionId: `${sessionId}-other`, message: '确认执行', approvalToken: first.approvalToken },
})
ck(
  '换个会话交回同一张令牌也不执行',
  (await orderStatus(apiOrderId)) === 'CREATED' &&
    crossSession.code === 400,
  `code=${crossSession.code}，status=${await orderStatus(apiOrderId)}`,
)

const confirmed = await api('/ai/chat', {
  method: 'POST',
  body: { sessionId, message: '确认执行', approvalToken: first.approvalToken },
})
ck(
  '确认之后不再要求二次确认',
  !confirmed.pendingActions || confirmed.pendingActions.length === 0,
  `pendingActions=${JSON.stringify(confirmed.pendingActions)}`,
)

const afterConfirm = await poll(() => orderStatus(apiOrderId), (s) => s === 'CANCELLED', 20000)
ck('确认之后订单真的被取消', afterConfirm === 'CANCELLED', `status=${afterConfirm}`)

// 订单终态相同不足以证明没有重复执行，管理端流水同时核对实际取消次数。
const admin = await api('/auth/login', {
  method: 'POST', body: { username: 'admin', password: '123456' },
})
if (!admin?.token) throw new Error('管理员登录未返回令牌，无法核对取消流水')
const beforeRepeat = await api(`/orders/admin/orders/${apiOrderId}`, { token: admin.token })
ck('首次确认只产生一条取消流水',
  Array.isArray(beforeRepeat.statusLogs)
    && beforeRepeat.statusLogs.filter((entry) => entry.toStatus === 'CANCELLED').length === 1)
const repeated = await api('/ai/chat', {
  method: 'POST',
  body: { sessionId, message: '确认执行', approvalToken: first.approvalToken },
})
ck('重复确认返回原操作已提交回执且不调用模型', repeated.approvalStatus === 'CONFIRMED'
  && repeated.usage == null && repeated.toolCalls?.length === 1
  && repeated.toolCalls[0].operationState === 'COMMITTED' && repeated.toolCalls[0].receiptReplayed === true)
const afterRepeat = await api(`/orders/admin/orders/${apiOrderId}`, { token: admin.token })
ck('重复确认后权威订单状态仍为已取消',
  (await orderStatus(apiOrderId)) === 'CANCELLED' && afterRepeat.order?.status === 'CANCELLED')
ck('重复确认未新增流水或改动订单',
  JSON.stringify(afterRepeat.statusLogs) === JSON.stringify(beforeRepeat.statusLogs)
    && JSON.stringify(afterRepeat.order) === JSON.stringify(beforeRepeat.order))

// ==================== 二、UI 链路 ====================
console.log('\n二、UI 链路：确认卡片渲染与点击执行')

const uiOrderId = await placeUnpaidOrder('ui')
ck(`准备未支付订单 #${uiOrderId}`, (await orderStatus(uiOrderId)) === 'CREATED')

const browser = await chromium.launch({ executablePath: CHROMIUM })
const context = await browser.newContext({ viewport: { width: 1600, height: 1200 } })
const page = await context.newPage()
const consoleErrors = []
page.on('console', (m) => m.type() === 'error' && consoleErrors.push(m.text()))
page.on('pageerror', (e) => consoleErrors.push('pageerror: ' + e.message))
await page.addInitScript(
  ([key, value]) => localStorage.setItem(key, value),
  ['user', JSON.stringify({ token: login.data.token, profile: login.data.user })],
)

await page.goto(`${BASE}/#/assistant`, { waitUntil: 'networkidle' })
// 首屏会恢复最近会话；本轮必须独立，不能把 API 段的历史审批卡当成 UI 结果。
await page.getByRole('button', { name: '新对话' }).click()
await page.locator('.chat-empty').waitFor()

// 定位必须是聊天输入框本身：顶栏改造后页面上多了一个排在前面的全局搜索框
// （`input.search-box__input`），`'textarea, input[type="text"]'` 的 `.first()` 会稳定地
// 填进搜索框——聊天框仍空，按钮因 `!input.trim()` 保持 disabled，看起来像前端坏了
await page.locator('.composer textarea').fill(`帮我取消订单 ${uiOrderId}`)
await page.getByRole('button', { name: '发送消息' }).click()

// 流式回答 + 两次真实模型调用，给足时间；等的是确认卡片出现
const card = page.getByRole('region', { name: '交易操作确认', exact: true })
const confirmButton = card.getByRole('button', { name: '确认执行', exact: true })
await confirmButton.waitFor({ state: 'visible', timeout: 120000 })
ck('唯一确认卡片出现且可以确认', (await card.count()) === 1 && await confirmButton.isEnabled())
const orderIdRow = card.locator('dt').filter({ hasText: /^订单内部 ID$/ }).locator('+ dd')
ck(
  '卡片显示的是要取消的那一单',
  (await orderIdRow.count()) === 1 && (await orderIdRow.textContent()) === String(uiOrderId),
  `卡片文本=${((await card.first().textContent()) ?? '').slice(0, 200)}`,
)
ck(
  '卡片出现时订单还没动',
  (await orderStatus(uiOrderId)) === 'CREATED',
  `status=${await orderStatus(uiOrderId)}`,
)

await confirmButton.click()
const uiAfter = await poll(() => orderStatus(uiOrderId), (s) => s === 'CANCELLED', 30000)
ck('点「确认执行」后订单真的被取消', uiAfter === 'CANCELLED', `status=${uiAfter}`)

const remainingWriteButtons = await poll(() => card.getByRole('button', { name: /^(确认执行|继续确认|停止后续操作|取消)/ }).count(), (n) => n === 0, 15000)
ck('确认后保留只读查询的原历史卡且没有继续写入口', remainingWriteButtons === 0
  && (await card.count()) === 1
  && (await card.getAttribute('class')).includes('approval--stale')
  && (await card.locator('.approval__title').textContent()) === '已提交确认的操作'
  && (await orderIdRow.textContent()) === String(uiOrderId)
  && (await card.getByRole('button', { name: '查询执行结果', exact: true }).count()) === 1)

ck('全程没有控制台报错', consoleErrors.length === 0, consoleErrors.slice(0, 3).join(' | '))

await browser.close()

console.log(`\n结果：${pass} 通过，${fail} 失败`)
process.exit(fail === 0 ? 0 : 1)
