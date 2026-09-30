/**
 * 工具事实一致性核对（批次 12d）。
 *
 * 引用校验管的是「这句话有没有出处」，管不了「这句话说的是不是真的」。订单类问题走的是
 * 工具而不是知识库，模型手上明明有《应付金额 ¥128.00》，写成别的数照样有工具轨迹、
 * 照样通得过所有引用检查——用户照着这个数字去对账，对不上。
 *
 * 这一批的验收标准是「回答里的订单金额/状态与工具事实<b>机器可核对</b>」。所以本脚本
 * 验的不是「模型这次答得对不对」，而是这条链路本身：
 *
 *   一、工具当场声明了哪些事实 —— order_query 的返回里必须带可机器比对的两个字段；
 *   二、声明的事实与订单服务的真实数据一致 —— 声明自己就是假的，后面全都白搭；
 *   三、真话不被误伤 —— 一轮正常问答下来 factMismatches 必须为空（误删正确句子比漏检严重）；
 *   四、回答里的数字与声明的事实对得上 —— 这是验收标准本身，用机器比而非肉眼；
 *   五、这一栏在界面上真的渲染出来 —— 否则「可核对」只存在于接口里。
 *
 * **本脚本验不了「模型说错时是否真的被拦住」**：没有哪个接口能让线上模型稳定地报错一个
 * 数字。那条路径由 ToolFactVerifierTest 的确定性用例覆盖（构造矛盾 → 断言剔除 + 报告），
 * 这里只保证接线通、不误伤、看得见。两边的分工写在批次材料的验收证据里。
 *
 * 前置条件：后端九个服务已启动。
 * 用法：node scripts/verify-tool-facts.mjs
 */

import { chromium } from 'playwright-core'

const GW = process.env.VERIFY_GW ?? 'http://localhost:8080'
const FE = process.env.VERIFY_FE ?? 'http://localhost:5173'
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

/** 造一笔未支付订单：先全不勾（与购物车历史残留无关）→ 加购 1 号 SKU → 只勾它 → 结算 */
async function placeUnpaidOrder(tag) {
  const detail = await api('/products/1')
  const skuId = detail.skus[0].id
  try {
    await api('/cart/selection', { method: 'PUT', body: { selected: false } })
  } catch {
    /* 空购物车时该接口可能无事可做 */
  }
  const item = await api('/cart/items', { method: 'POST', body: { skuId, quantity: 1 } })
  await api(`/cart/items/${item.id}/selected`, { method: 'PUT', body: { selected: true } })
  return api('/orders/checkout', {
    method: 'POST',
    body: {
      receiverName: '验收脚本',
      receiverPhone: '13800000000',
      receiverProvince: '浙江省',
      receiverCity: '杭州市',
      receiverDistrict: '西湖区',
      receiverDetail: `事实核对验收（${tag}）`,
    },
  })
}

const chat = (sessionId, message) =>
  api('/ai/chat', { method: 'POST', body: { sessionId, message } })

/**
 * 元 → 分。两边的写法本来就不一样（工具声明 {@code ¥128.00}，模型答「128 元」），
 * 比较必须在数值上做，逐字比会把同一个数判成矛盾。
 * <p>
 * 下单接口给的 {@code payAmount} 本身是分，不走这个换算，直接与结果比。
 */
const cents = (text) => {
  const m = String(text ?? '').match(/(?:¥|￥)\s*(\d+(?:\.\d{1,2})?)|(\d+(?:\.\d{1,2})?)\s*元/)
  if (!m) return null
  return Math.round(Number.parseFloat(m[1] ?? m[2]) * 100)
}

// ─────────── 一、工具声明的事实 vs 订单服务的真实数据 ───────────
console.log('\n一、order_query 声明的业务事实')

const order = await placeUnpaidOrder('facts')
const sessionId = `verify-facts-${Date.now()}`
const data = await chat(sessionId, `订单 ${order.id} 现在是什么状态？应付多少钱？`)

const orderCall = (data.toolCalls ?? []).find((c) => c.tool === 'order_query')
ck('这一轮确实调了 order_query', Boolean(orderCall), `tools=${(data.toolCalls ?? []).map((t) => t.tool)}`)

const facts = orderCall?.facts ?? null
ck(
  '工具声明了可机器比对的事实',
  facts && Object.keys(facts).length > 0,
  `facts=${JSON.stringify(facts)}`,
)
ck(
  '声明了「订单状态」，且与订单服务的真实状态一致',
  facts?.订单状态 && order.statusText === facts.订单状态,
  `声明=${facts?.订单状态}，接口=${order.statusText}`,
)
ck(
  '声明了「应付金额」，且与订单服务的真实金额一致',
  facts?.应付金额 && cents(facts.应付金额) === order.payAmount,
  `声明=${facts?.应付金额}（${cents(facts?.应付金额)} 分），接口=${order.payAmount} 分`,
)
ck(
  '标签是无歧义的那个（「订单状态」而不是「状态」）',
  Boolean(facts?.订单状态) && !Object.hasOwn(facts ?? {}, '状态'),
  `facts=${JSON.stringify(facts)}——「状态」会撞上「物流状态」，把正确的话判成矛盾`,
)

// ─────────── 二、真话不误伤 ───────────
console.log('\n二、正常问答不该被这道闸碰到')

ck(
  '回答里没有出现事实不符',
  !data.factMismatches || data.factMismatches.length === 0,
  `factMismatches=${JSON.stringify(data.factMismatches)}，reply=${(data.reply ?? '').slice(0, 200)}`,
)
ck(
  '这一轮没有被剔除任何句子',
  data.factStripped !== true,
  `factStripped=${data.factStripped}`,
)

// ─────────── 三、回答里的数字与工具事实对得上（验收标准本身）───────────
console.log('\n三、回答写出来的金额与工具事实逐数比对')

const replyMoney = cents(data.reply)
ck(
  '回答里报的金额与工具声明的一致',
  replyMoney !== null && replyMoney === cents(facts?.应付金额),
  `回答=${replyMoney}，工具=${cents(facts?.应付金额)}，reply=${(data.reply ?? '').slice(0, 200)}`,
)
ck(
  '回答里报的状态与工具声明的一致',
  (data.reply ?? '').includes(facts?.订单状态 ?? '\u0000'),
  `reply=${(data.reply ?? '').slice(0, 200)}，工具=${facts?.订单状态}`,
)

// ─────────── 四、界面上看得见 ───────────
console.log('\n四、浏览器里的工具轨迹')

const CHROMIUM =
  process.env.PLAYWRIGHT_CHROMIUM ??
  'C:/Users/j/AppData/Local/ms-playwright/chromium-1223/chrome-win64/chrome.exe'

const browser = await chromium.launch({ executablePath: CHROMIUM })
const context = await browser.newContext({ viewport: { width: 1440, height: 1200 } })
const page = await context.newPage()
const consoleErrors = []
page.on('console', (m) => m.type() === 'error' && consoleErrors.push(m.text()))
page.on('pageerror', (e) => consoleErrors.push('pageerror: ' + e.message))

// 登录态直接塞进 localStorage 而不是走登录页：这里要验的是事实栏，
// 走上一次登录表单只是多一段会随样式变化而失效的等待
await page.addInitScript(
  ([key, value]) => localStorage.setItem(key, value),
  ['user', JSON.stringify({ token: login.data.token, profile: login.data.user })],
)

await page.goto(`${FE}/#/assistant`, { waitUntil: 'networkidle', timeout: 30000 })
await page.locator('.composer textarea').fill(`订单 ${order.id} 现在是什么状态？应付多少钱？`)
await page.getByRole('button', { name: /发送消息/ }).click()

// 等这一轮生成结束：流式指示点只在生成期间存在
const live = page.locator('.message-live')
await live.waitFor({ state: 'visible', timeout: 15000 }).catch(() => {})
await live.waitFor({ state: 'detached', timeout: 180000 })

const trace = page.locator('details.trace').last()
await trace.locator('summary').click()
const factPanel = trace.locator('.trace__facts').first()
await factPanel.waitFor({ timeout: 5000 })

ck('轨迹里渲染出了事实栏', await factPanel.isVisible())
ck(
  '事实栏里写的就是工具声明的那两条',
  (await factPanel.innerText()).includes('订单状态') &&
    (await factPanel.innerText()).includes('应付金额'),
  `面板文本=${(await factPanel.innerText()).replace(/\n/g, ' | ')}`,
)
ck(
  '事实栏里能读到与订单服务一致的金额',
  cents(await factPanel.innerText()) === order.payAmount,
  `面板=${cents(await factPanel.innerText())} 分，接口=${order.payAmount} 分`,
)

await page.screenshot({ path: '.screenshots/facts-trace.png', fullPage: false })
ck('全程无控制台报错', consoleErrors.length === 0, consoleErrors.join(' | '))

await browser.close()


console.log(`\n${pass} 通过 / ${fail} 失败`)
console.log('截图：.screenshots/facts-trace.png')
process.exit(fail === 0 ? 0 : 1)
