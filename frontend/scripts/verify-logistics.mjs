/**
 * 批次 13c 物流轨迹的端到端验收。
 *
 * 为什么单开一个脚本：这一批把三件此前断开的事接上了——
 *   1. 发货/签收**自动写轨迹**（此前只在发货时写了一条，签收什么都不写）
 *   2. 客服**补录节点**（承运商只回传一句话时，轨迹不能在平台侧断掉）
 *   3. 轨迹上的**所在地**（库表第一列，但此前没有任何一条链路把它取出来过）
 * 三者任一接错，页面都照样好看：轨迹少一条只是时间线短一截、地点取不出来
 * 只是那行不显示、补录写错状态只是时间轴上多一条看不懂的记录。
 *
 * 还固化了两个口径差异，两者都是**故意**的、必须一起验：
 *   - 接口按时间**正序**返回（那是"轨迹"这件事本来的样子，模型读它也按时间读），
 *     页面按时间**倒序**展示（用户点进来问的是「我的包裹现在到哪了」）
 *   - 「补录」只往轨迹里加事实，**不动订单状态**——补录 SIGNED 不会把订单变成已收货。
 *     轨迹是事实记录，不是状态机；这条如果哪天被"顺手改成联动"，本条断言会红。
 *
 * 数据影响：每跑一轮新建 2 笔订单（一笔推进到已发货并带 4~5 条轨迹，一笔停在待发货），
 * 订单表本就是只增不减的流水，终态不还原。
 *
 * 前置条件：后端九个服务 + 前端 dev server 已启动；sku 16 在售。
 *
 * 用法：
 *   node scripts/verify-logistics.mjs
 */
import { chromium } from 'playwright-core'

const BASE = process.env.VERIFY_BASE ?? 'http://localhost:5173'
const GW = process.env.VERIFY_GW ?? 'http://127.0.0.1:8080'
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

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

async function call(path, { method = 'GET', token, body } = {}) {
  const res = await fetch(`${GW}${path}`, {
    method,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    ...(body ? { body: JSON.stringify(body) } : {}),
  })
  return res.json()
}

async function login({ username, password }) {
  const r = await call('/auth/login', { method: 'POST', body: { username, password } })
  if (r.code !== 200) {
    throw new Error(`登录失败：${r.msg}（后端起了吗）`)
  }
  return r.data
}

const alice = await login({ username: 'alice', password: '123456' })
const admin = await login({ username: 'admin', password: '123456' })
const T = alice.token
const A = admin.token

/** 下单 → 支付 → 发货，返回订单 id 与订单号。needShip=false 时停在待发货 */
async function placeOrder({ needShip = true } = {}) {
  await call('/cart/items', { method: 'POST', token: T, body: { skuId: 16, quantity: 1 } })
  const created = await call('/orders/checkout', {
    method: 'POST',
    token: T,
    body: {
      receiverName: '张三',
      receiverPhone: '13800000001',
      receiverProvince: '上海市',
      receiverCity: '上海市',
      receiverDistrict: '徐汇区',
      receiverDetail: '漕河泾开发区 1 号楼',
      remark: 'verify-logistics',
    },
  })
  const id = created.data?.id
  const orderNo = created.data?.orderNo
  if (!id) {
    throw new Error(`下单失败：${JSON.stringify(created)}`)
  }
  await call('/payments', { method: 'POST', token: T, body: { orderId: id, channel: 'MOCK', payType: 'MOCK' } })
  await call(`/payments/${id}/mock-pay`, { method: 'POST', token: T })
  // 支付→订单转 PAID 是 MQ 驱动的异步投影，按最终一致等待
  for (let waited = 0; waited < 10000; waited += 250) {
    if ((await call(`/orders/${id}`, { token: T })).data?.status === 'PAID') {
      break
    }
    await sleep(250)
  }
  if (needShip) {
    await call(`/orders/admin/orders/${id}/ship`, {
      method: 'POST',
      token: A,
      body: { carrierCode: 'SF', carrierName: '顺丰速运', trackingNo: `SF-${orderNo}` },
    })
  }
  return { id, orderNo }
}

const addTrace = (id, body) =>
  call(`/orders/admin/orders/${id}/traces`, { method: 'POST', token: A, body })

// ════ 一、补录三种形态：历史时间 / 默认时间 / 默认文案 ════
console.log('\n== 一、客服补录：历史节点、默认时间、默认文案 ==')
const O1 = await placeOrder()
const shippedAt = (await call(`/orders/${O1.id}`, { token: T })).data.shippedAt

// 历史节点：打单比揽收早两小时，这是补录的典型场景（承运商三天后才回传）
const earlier = new Date(new Date(shippedAt.replace('T', ' ').replace(/-/g, '/')).getTime() - 2 * 3600 * 1000)
const pad = (n) => String(n).padStart(2, '0')
const earlierIso = `${earlier.getFullYear()}-${pad(earlier.getMonth() + 1)}-${pad(earlier.getDate())}T${pad(earlier.getHours())}:${pad(earlier.getMinutes())}:${pad(earlier.getSeconds())}`

const r1 = await addTrace(O1.id, {
  status: 'CREATED',
  description: '电子面单已生成，等待揽收',
  location: '上海分拨中心',
  happenAt: earlierIso,
})
ck('补录历史节点返回整条轨迹而不是刚写的那一步', Array.isArray(r1.data?.steps), JSON.stringify(r1).slice(0, 200))
ck('补录后轨迹有 2 条（发货自动写的那条 + 补录的）', r1.data?.steps?.length === 2, `实得 ${r1.data?.steps?.length}`)

// 两次补录之间跨一秒：happen_at 是秒级精度，同秒内的顺序在 SQL 里是不确定的
await sleep(1200)
await addTrace(O1.id, { status: 'IN_TRANSIT', location: '杭州转运中心' })
await sleep(1200)
const r3 = await addTrace(O1.id, { status: 'DELIVERING', location: '上海市徐汇区' })

const steps = r3.data?.steps ?? []
ck('轨迹总共 4 条', steps.length === 4, `实得 ${steps.length}`)
ck('接口按时间**正序**返回', steps.map((s) => s.status).join(',') === 'CREATED,PICKED_UP,IN_TRANSIT,DELIVERING', steps.map((s) => s.status).join(','))
const times = steps.map((s) => s.time)
ck('时间严格递增', times.every((t, i) => i === 0 || t > times[i - 1]), times.join(' | '))
ck('发货自动写的第一条是「已揽收」', steps[1]?.status === 'PICKED_UP' && steps[1]?.detail === '包裹已由承运商揽收', JSON.stringify(steps[1]))
ck('补录不填说明时按状态生成默认文案', steps[3]?.detail === '包裹正在派送中', JSON.stringify(steps[3]))
ck('地点原样透出', steps[0]?.location === '上海分拨中心' && steps[2]?.location === '杭州转运中心', `${steps[0]?.location} / ${steps[2]?.location}`)
ck('发货自动写的那条没有地点，也不会编一个出来', !steps[1]?.location, JSON.stringify(steps[1]))
ck('承运商与运单号仍在', r3.data?.carrier === '顺丰速运' && r3.data?.trackingNo === `SF-${O1.orderNo}`, `${r3.data?.carrier} / ${r3.data?.trackingNo}`)

// ════ 二、拒绝路径 ════
console.log('\n== 二、拒绝路径：非法状态 400 / 未发货 409 ==')
const bad = await addTrace(O1.id, { status: 'PICKED', location: 'x' })
ck('非法状态被拒绝而不是落库', bad.code === 400, `实得 code=${bad.code} msg=${bad.msg}`)
ck('报错信息里带上全部合法取值', ['CREATED', 'PICKED_UP', 'IN_TRANSIT', 'DELIVERING', 'SIGNED'].every((s) => (bad.msg ?? '').includes(s)), bad.msg)

const blank = await addTrace(O1.id, { status: '' })
ck('状态留空被参数校验挡住', blank.code === 400, `实得 code=${blank.code} msg=${blank.msg}`)

const O2 = await placeOrder({ needShip: false })
const noship = await addTrace(O2.id, { status: 'IN_TRANSIT' })
ck('未发货订单没有轨迹可补，按「当前状态不允许」拒绝', noship.code === 409, `实得 code=${noship.code} msg=${noship.msg}`)
ck('拒绝原因说得清是"还没发货"', (noship.msg ?? '').includes('发货'), noship.msg)

// ════ 三、补录可追溯：谁在什么时候改了这条轨迹 ════
console.log('\n== 三、补录写进状态流水，操作人可追溯 ==')
const detail = (await call(`/orders/admin/orders/${O1.id}`, { token: A })).data
const traceLogs = (detail?.statusLogs ?? []).filter((l) => (l.remark ?? '').startsWith('补录物流节点：'))
ck('三条补录各留一条状态流水', traceLogs.length === 3, `实得 ${traceLogs.length}`)
ck('流水上记的是管理员身份', traceLogs.every((l) => l.operatorId === admin.user?.id && l.operatorType === 'ADMIN'), JSON.stringify(traceLogs.map((l) => [l.operatorType, l.operatorId])))
ck('补录**不改订单状态**（轨迹是事实记录，不是状态机）', detail?.order?.status === 'SHIPPED' && traceLogs.every((l) => l.fromStatus === 'SHIPPED' && l.toStatus === 'SHIPPED'), `${detail?.order?.status} / ${JSON.stringify(traceLogs.map((l) => [l.fromStatus, l.toStatus]))}`)

// ════ 四、C 端页面：最新置顶高亮、地点可见 ════
console.log('\n== 四、C 端订单详情页 ==')
const browser = await chromium.launch({ executablePath: CHROMIUM })
const context = await browser.newContext({ viewport: { width: 1440, height: 1100 } })
const page = await context.newPage()
const problems = []
page.on('console', (m) => m.type() === 'error' && problems.push(m.text()))
page.on('pageerror', (e) => problems.push('pageerror: ' + e.message))
await page.addInitScript(
  ([key, value]) => localStorage.setItem(key, value),
  ['user', JSON.stringify({ token: T, profile: alice.user })],
)

await page.goto(`${BASE}/#/orders/${O1.id}`, { waitUntil: 'networkidle' })
await page.waitForSelector('.trace__item', { timeout: 15000 })

const items = page.locator('.trace__item')
const shown = await items.count()
ck('页面显示 4 条轨迹', shown === 4, `实得 ${shown}`)

const first = items.first()
ck('最新一条在最上面（页面倒序）', (await first.locator('strong').innerText()).includes('包裹正在派送中'), await first.innerText())
ck('最新一条被高亮', (await first.getAttribute('class'))?.includes('is-latest') === true, await first.getAttribute('class'))
const last = items.last()
ck('最早一条在最下面', (await last.locator('strong').innerText()).includes('电子面单已生成'), await last.innerText())

const whereTexts = await page.locator('.trace__where').allTextContents()
ck('地点在页面上可见', whereTexts.some((t) => t.includes('上海市徐汇区')) && whereTexts.some((t) => t.includes('上海分拨中心')), JSON.stringify(whereTexts))
ck('没有地点的节点不占位', whereTexts.length === 3, `实得 ${whereTexts.length}`)
ck('状态码不再直接露给用户', !(await page.locator('.trace').innerText()).includes('PICKED_UP'), await page.locator('.trace').innerText())
ck('标题行给出承运商与运单号', (await page.locator('.section-hint').first().innerText()).includes(`SF-${O1.orderNo}`), await page.locator('.section-hint').first().innerText())

// 刷新按钮：包裹在路上时用户会反复看这一块，不能逼他刷整页
await page.locator('.trace-refresh').click()
await page.waitForSelector('.el-message--success', { timeout: 10000 })
ck('刷新物流不刷整页', (await items.count()) === 4, `刷新后 ${await items.count()} 条`)

ck('页面没有控制台报错', problems.length === 0, problems.slice(0, 3).join(' | '))

// ════ 五、管理端页面：从界面补录一条，抽屉里立刻看得到 ════
console.log('\n== 五、管理端订单页补录 ==')
const adminContext = await browser.newContext({ viewport: { width: 1600, height: 1100 } })
const adminPage = await adminContext.newPage()
const adminProblems = []
adminPage.on('console', (m) => m.type() === 'error' && adminProblems.push(m.text()))
adminPage.on('pageerror', (e) => adminProblems.push('pageerror: ' + e.message))
await adminPage.addInitScript(
  ([key, value]) => localStorage.setItem(key, value),
  ['user', JSON.stringify({ token: A, profile: admin.user })],
)

await adminPage.goto(`${BASE}/#/admin/orders`, { waitUntil: 'networkidle' })
await adminPage.getByPlaceholder('订单号 / 收货人 / 手机号 / 运单号').fill(O1.orderNo)
await adminPage.getByRole('button', { name: '查询' }).click()
await adminPage.waitForSelector('.el-table__row', { timeout: 15000 })
await adminPage.locator('.el-table__row').first().click()
await adminPage.waitForSelector('.admin-detail', { timeout: 15000 })

const traceButton = adminPage.getByRole('button', { name: '补录节点' })
ck('详情抽屉里有「补录节点」入口', await traceButton.isVisible(), '')
const beforeCount = await adminPage.locator('.admin-timeline__body').count()
await traceButton.click()
await adminPage.waitForSelector('.el-dialog', { timeout: 10000 })
await adminPage.locator('.el-dialog .el-select').click()
await adminPage.locator('.el-select-dropdown__item:visible', { hasText: '运输中' }).first().click()
await adminPage.getByPlaceholder('如 杭州转运中心').fill('苏州转运中心')
await adminPage.getByRole('button', { name: '确认补录' }).click()
await adminPage.waitForSelector('.el-message--success', { timeout: 10000 })

ck('补录后抽屉里立刻能看到这条节点', await adminPage.getByText('苏州转运中心').first().isVisible(), '')
const afterCount = await adminPage.locator('.admin-timeline__body').count()
ck('轨迹条目数增加', afterCount > beforeCount, `${beforeCount} → ${afterCount}`)
// 列表倒序：刚补的这条应当排在轨迹的第一条
const firstTraceHead = await adminPage.locator('.admin-timeline__head').first().innerText()
ck('管理端最新一条也置顶', firstTraceHead.includes('包裹已发往下一站'), firstTraceHead)
ck('状态码在管理端保留（补录时要照着填）', (await adminPage.locator('.admin-timeline__body').first().innerText()).includes('IN_TRANSIT'), await adminPage.locator('.admin-timeline__body').first().innerText())
ck('管理端没有控制台报错', adminProblems.length === 0, adminProblems.slice(0, 3).join(' | '))

await browser.close()

console.log(`\n${fail === 0 ? '\x1b[32m' : '\x1b[31m'}${pass} passed, ${fail} failed\x1b[0m`)
process.exit(fail === 0 ? 0 : 1)
