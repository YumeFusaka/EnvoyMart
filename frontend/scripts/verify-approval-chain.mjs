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

const BASE = process.env.VERIFY_BASE ?? 'http://localhost:5173'
const GW = process.env.VERIFY_GW ?? 'http://localhost:8080'
const CHROMIUM =
  process.env.PLAYWRIGHT_CHROMIUM ??
  'C:/Users/j/AppData/Local/ms-playwright/chromium-1223/chrome-win64/chrome.exe'

const ALICE = { username: 'alice', password: '123456' }

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

const login = await (await fetch(`${GW}/auth/login`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify(ALICE),
})).json()
if (login.code !== 200) {
  console.error(`登录 alice 失败：${login.msg}（后端起了吗）`)
  process.exit(1)
}
const auth = { Authorization: `Bearer ${login.data.token}` }

async function api(path, { method = 'GET', body } = {}) {
  const res = await fetch(`${GW}${path}`, {
    method,
    headers: body === undefined ? auth : { ...auth, 'Content-Type': 'application/json' },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  })
  const payload = await res.json()
  if (payload.code !== 200) {
    throw new Error(`${method} ${path} 失败：${payload.msg}`)
  }
  return payload.data
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
    (tampered.reply ?? '').includes('没有执行任何操作'),
  `reply=${(tampered.reply ?? '').slice(0, 80)}，status=${await orderStatus(apiOrderId)}`,
)

// 换一个会话交回同一张令牌：令牌里签着签发它的会话，此处必须被拒
const crossSession = await api('/ai/chat', {
  method: 'POST',
  body: { sessionId: `${sessionId}-other`, message: '确认执行', approvalToken: first.approvalToken },
})
ck(
  '换个会话交回同一张令牌也不执行',
  (await orderStatus(apiOrderId)) === 'CREATED' &&
    (crossSession.reply ?? '').includes('没有执行任何操作'),
  `reply=${(crossSession.reply ?? '').slice(0, 80)}，status=${await orderStatus(apiOrderId)}`,
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
await page.reload({ waitUntil: 'networkidle' })

// 定位必须是聊天输入框本身：顶栏改造后页面上多了一个排在前面的全局搜索框
// （`input.search-box__input`），`'textarea, input[type="text"]'` 的 `.first()` 会稳定地
// 填进搜索框——聊天框仍空，按钮因 `!input.trim()` 保持 disabled，看起来像前端坏了
await page.locator('.composer textarea').fill(`帮我取消订单 ${uiOrderId}`)
await page.getByRole('button', { name: '发送消息' }).click()

// 流式回答 + 两次真实模型调用，给足时间；等的是确认卡片出现
const card = page.locator('section[aria-label="高危操作确认"]')
await poll(() => card.count(), (n) => n > 0, 120000, 1000)
ck('确认卡片出现', (await card.count()) > 0)
ck(
  '卡片显示的是要取消的那一单',
  ((await card.first().textContent()) ?? '').includes(`orderId=${uiOrderId}`),
  `卡片文本=${((await card.first().textContent()) ?? '').slice(0, 200)}`,
)
ck(
  '卡片出现时订单还没动',
  (await orderStatus(uiOrderId)) === 'CREATED',
  `status=${await orderStatus(uiOrderId)}`,
)

await card.getByRole('button', { name: '确认执行' }).click()
const uiAfter = await poll(() => orderStatus(uiOrderId), (s) => s === 'CANCELLED', 30000)
ck('点「确认执行」后订单真的被取消', uiAfter === 'CANCELLED', `status=${uiAfter}`)

const staleCard = await poll(() => page.locator('section[aria-label="高危操作确认"]').count(), (n) => n === 0, 15000)
ck('确认之后卡片收起（不能留一张还能再点的旧卡）', staleCard === 0)

ck('全程没有控制台报错', consoleErrors.length === 0, consoleErrors.slice(0, 3).join(' | '))

await browser.close()

console.log(`\n结果：${pass} 通过，${fail} 失败`)
process.exit(fail === 0 ? 0 : 1)
