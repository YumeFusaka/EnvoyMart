/**
 * 管理台前端的端到端验收。
 *
 * 为什么单开一个脚本：`pnpm build` 只证明「类型对得上、模板能编译」，
 * 而管理台最典型的坏法是**接线接错**——按钮点下去调了另一个接口、参数名对不上、
 * 或者根本没发请求。这些在构建期一个都暴露不出来，页面看上去也完全正常。
 *
 * 分四段：
 *   一、权限门 —— 普通用户进不去管理台，菜单里也不给一条走不通的路
 *   二、九个页面逐个加载 —— 断言「接口通了、数据渲染出来了、控制台没报错」
 *   三、写路径真跑一遍 —— 备注 / 上下架 / 隐藏恢复 / 禁用启用 / 工单回复关闭
 *   四、还原 —— 把可逆的四处改回原值，并复查确实改回去了
 *
 * 两条实现上的讲究，都是踩出来的：
 *   · **每次跳转都重新加载文档**。同一份文档里只改 hash 的导航走 pushState，
 *     而 vue-router 监听 popstate，收不到 —— 表现是地址变了、页面还停在上一页。
 *   · **等待统一用轮询而不是 sleep**。ElMessageBox 的确认到请求真正落库之间隔着
 *     关闭动画、事件回调、网关与数据库，固定 1.2 秒在本机能过、在机器忙的时候就差一口气，
 *     于是断言读到的是改动前的状态 —— 一次假失败，比不测更费时间。
 *
 * 数据影响：四处可逆改动都会还原（第四段复查）；工单那一条**复用上一轮留下的
 * 「自动化验收」工单**（用户重开 → 客服回复 → 关闭只拦请求 → 标记解决），没有才新建一张。
 * 「关闭」是终态且工单没有删除接口，真关下去每跑一轮就多一张同名的死单，
 * 所以那个按钮只断言接线、不真发 —— 反复跑不会堆积数据。
 *
 * 前置条件：后端九个服务 + 前端 dev server 已启动。
 *
 * 用法：
 *   node scripts/verify-admin-console.mjs
 */
import { chromium } from 'playwright-core'
import { poll, uniqueRow } from './lib/verify-util.mjs'

const BASE = process.env.VERIFY_BASE ?? 'http://localhost:5173'
const GW = process.env.VERIFY_GW ?? 'http://localhost:8080'
const CHROMIUM =
  process.env.PLAYWRIGHT_CHROMIUM ??
  'C:/Users/j/AppData/Local/ms-playwright/chromium-1223/chrome-win64/chrome.exe'

const ADMIN = { username: 'admin', password: '123456' }
const NORMAL = { username: 'alice', password: '123456' }
const TICKET_TITLE = '自动化验收：管理台接线检查'
const REASON = '自动化验收'

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
function skip(name, why) {
  console.log(`  \x1b[33mSKIP\x1b[0m ${name}：${why}`)
}

async function apiLogin(credentials) {
  const res = await fetch(`${GW}/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(credentials),
  })
  const body = await res.json()
  if (body.code !== 200) {
    throw new Error(`登录 ${credentials.username} 失败：${body.msg}（后端起了吗）`)
  }
  return body.data
}

const adminSession = await apiLogin(ADMIN)
const normalSession = await apiLogin(NORMAL)
const asAdmin = { Authorization: `Bearer ${adminSession.token}` }
const asUser = { Authorization: `Bearer ${normalSession.token}` }

async function api(path, { method = 'GET', body, headers = asAdmin } = {}) {
  const res = await fetch(`${GW}${path}`, {
    method,
    headers: body === undefined ? headers : { ...headers, 'Content-Type': 'application/json' },
    // 不带 body 的请求不能留一个 body 键在那里（oxlint 的 no-invalid-fetch-options 会拦）
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  })
  const payload = await res.json()
  if (payload.code !== 200) {
    throw new Error(`${method} ${path} 失败：${payload.msg}`)
  }
  return payload.data
}

const browser = await chromium.launch({ executablePath: CHROMIUM })

const hashOf = (page) => new URL(page.url()).hash.replace(/^#/, '')

/**
 * 新开一个页面并预置会话。
 * <p>
 * 必须用 `addInitScript` —— 路由守卫在应用启动那一刻就读了 store，先打开页面再写已经晚了。
 */
async function newPage(session) {
  const context = await browser.newContext({ viewport: { width: 1600, height: 1200 } })
  const page = await context.newPage()
  page.__errors = []
  page.on('console', (m) => m.type() === 'error' && page.__errors.push(m.text()))
  page.on('pageerror', (e) => page.__errors.push('pageerror: ' + e.message))
  // 预置会话要写成 pinia-plugin-persistedstate 实际落盘的形状：它把整个 state
  // 序列化成一条 JSON 存进 localStorage[storeId]，也就是 {"token":"...","profile":{...}}。
  // 形状对不上时 token 读不出来，页面会以匿名身份加载 —— 这件事本身不报错，
  // 但「权限门」那几条断言就会悄悄测到别的东西。
  await page.addInitScript(
    ([key, value]) => localStorage.setItem(key, value),
    ['user', JSON.stringify({ token: session.token, profile: session.user })],
  )
  return page
}

/**
 * 打开管理台的某一页，并等列表渲染出来。
 * <p>
 * 每次都重新加载文档：同一份文档里只改 hash 的 `goto` 走 pushState，vue-router 监听的是
 * popstate，收不到 —— 表现是地址变了、页面还停在上一页（实测：`goto('#/admin/products')`
 * 之后标题仍是上一页的），于是后面的断言全都在错误的页面上跑。
 */
async function openAdmin(page, path) {
  // 等 `domcontentloaded` 而不是 `networkidle`：dev server 首次访问某个视图时要做一次
  // 按需编译，这一下的耗时不受脚本控制（实测重载一次要 7s，冷启动更久），
  // 而 networkidle 把「所有请求都停下来」当条件，编译器一忙就撞满 30s 超时。
  // 真正要等的「页面渲染好了」由下面那条 loading 遮罩的轮询负责。
  await page.goto(`${BASE}/#/admin${path}`, { waitUntil: 'domcontentloaded' })
  await page.reload({ waitUntil: 'domcontentloaded' })
  await page
    .locator('.el-loading-mask')
    .first()
    .waitFor({ state: 'hidden', timeout: 30000 })
    .catch(() => {})
  await page.waitForTimeout(300)
}

/** 打开某一页，并按行内唯一文字定位到一行 */
async function openRow(page, path, text) {
  await openAdmin(page, path)
  const rows = page.locator('.admin-table tbody tr', { hasText: text })
  const count = await poll(
    async () => rows.count(),
    (n) => n >= 1,
    { timeoutMs: 20000 },
  )
  if (count < 1) {
    throw new Error(`按「${text}」定位没有命中任何行`)
  }
  const row = rows.first()
  await row.waitFor({ state: 'visible', timeout: 15000 })
  return row
}

/**
 * 按行内文字定位**唯一**一行，命中多于一条就抛错。
 * <p>
 * `openRow` 用的是 `.first()`：文字在全库不唯一时它会静默选中第一条，
 * 而选错行之后的等待/点击会以「功能坏了」的形式报出来（实测踩过评价的孪生数据）。
 * 定位到唯一一行的地方改用这个函数，把「选错行」当场变成一条能看懂的错。
 */
async function uniqueTableRow(page, path, text) {
  await openAdmin(page, path)
  const rows = page.locator('.admin-table tbody tr', { hasText: text })
  // 轮询到「恰好 1 行」为止，而不是读完一次就断言：表格的 body 由接口回包后异步填充，
  // count() 立刻读会撞在「页面在、行还没渲染」的窗口上，报成「实际 0 行」——
  // 看起来像定位失败，其实是读早了。等待条件写成 n >= 1 而不是 n === 1，
  // 这样才能把「多行」当成真问题在超时后报出来，而不是被轮询掩盖成超时。
  const count = await poll(
    async () => rows.count(),
    (n) => n >= 1,
    { timeoutMs: 20000 },
  )
  if (count !== 1) {
    throw new Error(`按「${text}」定位应当恰好命中 1 行，实际 ${count} 行`)
  }
  const row = rows.first()
  await row.waitFor({ state: 'visible', timeout: 15000 })
  return row
}

const drawer = (page) => page.locator('.el-drawer').first()

/** 打开某一行右侧的入口按钮（详情 / 处理 / 管理），返回抽屉 */
async function openDrawer(page, row, button) {
  await row.getByRole('button', { name: button, exact: true }).click()
  const panel = drawer(page)
  await panel.waitFor({ state: 'visible', timeout: 10000 })
  return panel
}

/**
 * 应答 `ElMessageBox`（confirm 与 prompt 共用一个外壳）。
 * @param text 填进输入框的内容；confirm 没有输入框，传 undefined
 * @param button 确认按钮的文字（各处措辞不同：隐藏 / 禁用 / 关闭工单 …）
 */
async function answerMessageBox(page, { text, button }) {
  const box = page.locator('.el-message-box').last()
  await box.waitFor({ state: 'visible', timeout: 10000 })
  if (text !== undefined) {
    await box.locator('input, textarea').first().fill(text)
  }
  await box.locator('.el-message-box__btns button', { hasText: button }).last().click()
}

// ───────────────────────── 一、权限门 ─────────────────────────

console.log('【一】权限门')

/** 展开右上角用户菜单，返回菜单项文字。管理台入口在这里，不在主导航 */
async function userMenuItems(page) {
  await page.goto(`${BASE}/#/shop`, { waitUntil: 'networkidle' })
  await page.locator('.app-user').first().hover()
  await page.waitForTimeout(500)
  return page.locator('.el-dropdown-menu__item').allInnerTexts()
}

{
  const page = await newPage(normalSession)
  const items = await userMenuItems(page)
  ck(
    '普通用户的菜单里没有「管理台」',
    !items.some((t) => t.includes('管理台')),
    `实际菜单：${items.map((t) => t.trim()).join(' | ')}`,
  )

  // 手敲地址：路由守卫应当把人挡回去，而不是渲染出管理台（哪怕接口层还会再拦一道）
  await page.goto(`${BASE}/#/admin/orders`, { waitUntil: 'networkidle' })
  await page.waitForTimeout(600)
  ck(
    '普通用户手敲 /admin/orders 被守卫挡下',
    hashOf(page) !== '/admin/orders',
    `落到了 ${hashOf(page)}`,
  )
  ck(
    '挡下之后界面上没有管理台内容',
    (await page.locator('.admin-panel').count()) === 0,
    `仍然渲染出了 ${await page.locator('.admin-panel').count()} 个 .admin-panel`,
  )
  await page.context().close()
}

{
  const page = await newPage(adminSession)
  const items = await userMenuItems(page)
  ck(
    '管理员的菜单里有「管理台」',
    items.some((t) => t.includes('管理台')),
    `实际菜单：${items.map((t) => t.trim()).join(' | ')}`,
  )
  await page.context().close()
}

// ───────────────────────── 二、九个页面 ─────────────────────────

console.log('\n【二】九个页面逐个加载')

/** 路径 → 期望的 document.title 前缀（由 router 的 afterEach 设置） */
const PAGES = [
  ['', '概览'],
  ['/products', '商品管理'],
  ['/products/new', '新建商品'],
  ['/catalog', '类目与品牌'],
  ['/orders', '订单管理'],
  ['/after-sales', '售后工作台'],
  ['/reviews', '评价管理'],
  ['/users', '用户管理'],
  ['/tickets', '客服工单'],
]

{
  const page = await newPage(adminSession)
  for (const [path, title] of PAGES) {
    await openAdmin(page, path)
    // 内容区：列表页与编辑页都是 `.admin-panel`，概览页是它的自有布局 `.dashboard`
    const marker = path === '' ? page.locator('.dashboard') : page.locator('.admin-panel').first()
    let visible = true
    try {
      await marker.waitFor({ state: 'visible', timeout: 15000 })
    } catch {
      visible = false
      await page.screenshot({
        path: `.screenshots/verify-admin-${path.replace(/\//g, '_') || 'index'}.png`,
      })
    }
    const errors = page.__errors.filter((t) => !/favicon|DevTools|Failed to load resource/i.test(t))
    ck(
      `#/admin${path} 渲染出内容（期望标题「${title}」）`,
      visible && (await page.title()).startsWith(title),
      `内容可见=${visible}，标题实际是 ${JSON.stringify(await page.title())}`,
    )
    ck(`#/admin${path} 控制台无报错`, errors.length === 0, errors.join('\n        '))
    page.__errors = []
  }
  await page.context().close()
}

// ───────────────────────── 三、写路径 ─────────────────────────

console.log('\n【三】写路径真跑一遍')

const page = await newPage(adminSession)

/** 每条写路径单独兜住异常：一条挂了不该让后面几条全都不跑 */
async function section(name, fn) {
  try {
    await fn()
  } catch (e) {
    ck(`${name} 跑通`, false, e instanceof Error ? e.message : String(e))
  }
}

/** 第三节里各段登记的还原动作，第四节按逆序跑 */
const restores = []

// ── 3.1 订单备注：改完核对，再改回原值 ──
await section('订单备注', async () => {
  const order = (await api('/orders/admin/orders?page=0&size=1')).records[0]
  const before = (await api(`/orders/admin/orders/${order.id}`)).adminRemark ?? ''

  const write = async (text) => {
    const row = await openRow(page, '/orders', order.orderNo)
    const panel = await openDrawer(page, row, '详情')
    await panel.getByRole('button', { name: '编辑备注' }).click()
    const dialog = page.locator('.el-dialog', { hasText: '客服备注' }).first()
    await dialog.waitFor({ state: 'visible', timeout: 10000 })
    await page.getByPlaceholder('只有后台看得见，用户不会看到').fill(text)
    await dialog.getByRole('button', { name: '保存' }).click()
    await page.locator('.el-dialog', { hasText: '客服备注' }).first().waitFor({
      state: 'hidden',
      timeout: 10000,
    })
  }

  const marker = '自动化验收：这条备注会被改回去'
  await write(marker)
  const after = await poll(
    async () => (await api(`/orders/admin/orders/${order.id}`)).adminRemark,
    (v) => v === marker,
  )
  ck('订单备注写入后库里确实是新值', after === marker, `库里是 ${JSON.stringify(after)}`)

  restores.push(async () => {
    await write(before)
    const back = await poll(
      async () => (await api(`/orders/admin/orders/${order.id}`)).adminRemark ?? '',
      (v) => v === before,
    )
    ck('订单备注已还原为原值', back === before, `还原后是 ${JSON.stringify(back)}`)
  })
})

// ── 3.2 商品上下架：下架 → 核对 → 上架 ──
await section('商品上下架', async () => {
  // 必须自己选一个**当前处于上架**的商品：上一轮若在还原前中断，被下架的那件还留在
  // 下架态，此时按 status=1 过滤拿到的仍是别的商品，而还原闭包会把另一件又动一次。
  const spu = (await api('/products/admin/spus?page=0&size=20&status=1')).records[0]
  const statusOf = async () =>
    (
      await api(`/products/admin/spus?page=0&size=1&keyword=${encodeURIComponent(spu.spuCode)}`)
    ).records[0].status

  const click = async (label) => {
    // 商品编码是唯一自然键，用严格定位：命中多行说明数据有问题，当场报出来
    const row = await uniqueTableRow(page, '/products', spu.spuCode)
    await row.getByRole('button', { name: label, exact: true }).click()
    await page.waitForTimeout(1200)
  }

  await click('下架')
  const down = await poll(statusOf, (v) => v === 0)
  ck(`商品 ${spu.spuCode} 下架后库里 status=0`, down === 0, `实际 status=${down}`)

  // 列表没做状态过滤，行不该消失；按钮换成了「上架」才说明这一行真的刷新过。
  // 这里轮询 DOM 而不是读一次：接口改完到表格重渲染之间还有「回包 → load() 重拉 →
  // 重新挂载行」几跳，读一次会拿到改动前的行 —— 一次假失败比不测更费时间
  // 超时给足：这一跳要等「PUT 回包 → load() 重拉列表 → el-table 重渲染行」，
  // 而 dev server 首次访问这个视图时还在按需编译，整条链比常驻时慢一个量级。
  // 12 秒在本机常驻状态下够用，在冷启动那一轮不够 —— 而假失败比不测更费时间。
  const labels = await poll(
    async () => {
      const row = page.locator('.admin-table tbody tr', { hasText: spu.spuCode }).first()
      return (await row.locator('button').allInnerTexts()).map((t) => t.trim())
    },
    (texts) => texts.includes('上架'),
    { timeoutMs: 40000 },
  )
  ck(
    '下架后行内的按钮变成「上架」（说明列表确实刷新了）',
    labels.includes('上架'),
    `行内按钮：${labels.filter(Boolean).join(' / ')}`,
  )

  restores.push(async () => {
    // 自愈：先看实际状态。上一轮若在还原前中断，这件商品可能已经是上架的，
    // 此时列表里根本没有「上架」按钮可点 —— 直接确认即可，不必硬点一次。
    if ((await statusOf()) !== 1) {
      await click('上架')
    }
    const up = await poll(statusOf, (v) => v === 1)
    ck('商品已恢复上架', up === 1, `实际 status=${up}`)
  })
})

// ── 3.3 评价隐藏与恢复 ──
await section('评价隐藏与恢复', async () => {
  // 候选**不按状态过滤**：脚本自己隐藏掉的那条若上一轮没还原成功，它在库里就是 HIDDEN，
  // 用 status=PUBLISHED 取候选会换一条评价，而还原闭包仍按旧内容去找 —— 表现为
  // 「搜索结果里应恰好 1 行，实际 0 行」，坏的是脚本不是产品。
  const list = await api('/reviews/admin/reviews?page=0&size=50')
  // 行里没有 id 列，只能按内容定位 —— 内容必须**在全库唯一**（理由见 uniqueRow 的说明）
  const review = await uniqueRow(
    list.records,
    (r) => r.content,
    (content) => api(`/reviews/admin/reviews?page=0&size=5&keyword=${encodeURIComponent(content)}`),
  )
  const read = async () => (await api(`/reviews/admin/reviews/${review.id}`)).review

  // 定位不能依赖「行恰好在第一页」。默认列表混全部状态、按时间倒序，而验收夹具
  // （脚本自己隐藏掉的评价）会一轮轮堆在列表最前面——候选行昨天还在第 6 行，
  // 今天前面已积了 40 条夹具，掉到第 3 页，「等行出现」等成超时（实测踩到）。
  // 改走页面自己的关键词搜索把列表收窄到一条，行排在第几就无关紧要
  const open = async () => {
    await openAdmin(page, '/reviews')
    await page.locator('#review-keyword').fill(review.content)
    await page.getByRole('button', { name: '查询' }).click()
    // 已用 uniqueRow 保证 review.content 全库唯一；这里再要求表格里恰好命中一行，
    // 把「关键词搜索没把列表收窄」这类问题当场暴露出来，而不是等超时
    const rows = page.locator('.admin-table tbody tr', { hasText: review.content })
    // 与 uniqueTableRow 同一个理由：点完「查询」到表格填充之间隔着一次请求，
    // 立刻 count() 会读到旧表（关键词还没生效的那一版），于是「应恰好 1 行」变成
    // 「实际 20 行」——一次典型的读早了。轮询到非空再断言唯一性。
    const count = await poll(
      async () => rows.count(),
      (n) => n >= 1,
      { timeoutMs: 20000 },
    )
    if (count !== 1) {
      throw new Error(
        `评价「${review.content.slice(0, 20)}…」在搜索结果里应恰好 1 行，实际 ${count} 行`,
      )
    }
    const row = rows.first()
    await row.waitFor({ state: 'visible', timeout: 15000 })
    return openDrawer(page, row, '处理')
  }

  // 自愈：上一轮若把它留在 HIDDEN，这一轮就不该再点一次「隐藏」（按钮不存在）
  if ((await read()).status !== 'HIDDEN') {
    await (await open()).getByRole('button', { name: '隐藏', exact: true }).click()
    await answerMessageBox(page, { text: REASON, button: '隐藏' })
  }
  const hidden = await poll(read, (v) => v.status === 'HIDDEN')
  ck(
    `评价 ${review.id} 隐藏后 status=HIDDEN 且落下了原因`,
    hidden.status === 'HIDDEN' && hidden.hiddenReason === REASON,
    `status=${hidden.status} reason=${JSON.stringify(hidden.hiddenReason)}`,
  )

  restores.push(async () => {
    // 自愈：已经是 PUBLISHED 就不必点（按钮不存在），直接确认结果
    if ((await read()).status !== 'PUBLISHED') {
      await (await open()).getByRole('button', { name: '恢复发布', exact: true }).click()
    }
    const back = await poll(read, (v) => v.status === 'PUBLISHED')
    ck(
      '评价已恢复发布，隐藏痕迹被清空',
      back.status === 'PUBLISHED' && back.hiddenReason === null,
      `status=${back.status} reason=${JSON.stringify(back.hiddenReason)}`,
    )
  })
})

// ── 3.4 用户禁用与启用 ──
await section('用户禁用与启用', async () => {
  // 挑一个**不是**管理员、也不是其余脚本在用的账号（alice / bob / admin 都别动）
  const users = await api('/auth/admin/users?page=0&size=50&status=1')
  const user = users.records.find(
    (u) => u.roleName === 'USER' && !['u1001', 'u1002'].includes(u.id),
  )
  if (!user) {
    throw new Error('库里没有可安全禁用的普通用户（需要一个非 alice / bob 的账号）')
  }
  // 用户详情外面包了一层：`{user: {...}, addressCount: n}`，字段不在顶层
  const read = async () => (await api(`/auth/admin/users/${user.id}`)).user

  const open = async () => {
    const row = await openRow(page, '/users', user.username)
    return openDrawer(page, row, '管理')
  }

  await (await open()).getByRole('button', { name: '禁用', exact: true }).click()
  await answerMessageBox(page, { text: REASON, button: '禁用' })
  const disabled = await poll(read, (v) => v.status === 0)
  ck(
    `用户 ${user.username} 禁用后 status=0 且留下原因`,
    disabled.status === 0 && disabled.disabledReason === REASON,
    `status=${disabled.status} reason=${JSON.stringify(disabled.disabledReason)}`,
  )

  restores.push(async () => {
    await (await open()).getByRole('button', { name: '启用', exact: true }).click()
    const back = await poll(read, (v) => v.status === 1)
    ck('用户已恢复启用', back.status === 1, `实际 status=${back.status}`)
  })
})

// ── 3.5 工单：回复 → 关闭 → 标记解决 ──
await section('工单回复与关闭', async () => {
  const all = await api('/tickets/admin/tickets?page=0&size=50')
  const mine = all.records.filter((t) => t.title === TICKET_TITLE)
  // 复用上一轮留下的那张：**关闭是终态**（`reopen` 只对「已解决」开放，见
  // SupportTicketServiceImpl.reopen 的状态守卫），而工单没有删除接口 ——
  // 每跑一次新建一张的话，演示库里会堆一排同名的死单。
  // 所以本节结束时把它停在「已解决」，下一轮开头用户重开它，闭环且不新增数据
  // 复用面要比「已解决」更宽：脚本每轮要把这张单停在「已解决」，下一轮才重开它；
  // 而一旦某一轮在收尾前中断，它会停在 PROCESSING / OPEN —— 那时只认 RESOLVED
  // 就会去新建一张，而 CLOSED 没有重开入口、工单也没有删除接口，**每中断一次就多
  // 一张再也清不掉的死单**（实测已经积了两张，且消息堆了 30 多轮）。
  // 所以按「还能继续用的」优先挑：RESOLVED > PROCESSING/OPEN，最后才是新建。
  const reusable =
    mine.find((t) => t.status === 'RESOLVED') ??
    mine.find((t) => t.status === 'PROCESSING' || t.status === 'OPEN')
  // 历史遗留的 CLOSED 死单清不掉，但也不能让它们混进队列断言里 —— 后面按工单号定位。
  let ticketId
  if (reusable) {
    ticketId = reusable.id
    // 只有「已解决」才需要重开；停在 PROCESSING/OPEN 的那种本来就在队列里。
    // 重开要带一句内容：队列默认只显示「最后一条消息来自用户」的工单，
    // 不带内容的话重开后那张单不会出现在待回复队列里。
    if (reusable.status === 'RESOLVED') {
      await api(`/tickets/${ticketId}/reopen`, {
        method: 'POST',
        headers: asUser,
        body: { content: '自动化验收：重开一次' },
      })
    }
  } else {
    const created = await api('/tickets', {
      method: 'POST',
      headers: asUser,
      body: {
        category: 'OTHER',
        title: TICKET_TITLE,
        content: '这条工单由验收脚本创建，用于验证管理台的回复、关闭与解决接线。',
      },
    })
    ticketId = created.ticket.id
  }
  const read = async () => await api(`/tickets/admin/tickets/${ticketId}`)
  const ticketNo = (await read()).ticket.ticketNo

  await openAdmin(page, '/tickets')
  // 工单号是唯一键。队列里可能同时挂着同名工单（脚本每轮复用同一张，但历史轮次可能有别的），
  // 按工单号定位并确认唯一，避免点到别人的工单上
  // 等队列里出现这张单，而不是读完一次就断言：新建 / 重开之后列表要多一次加载，
  // dev server 冷启动时那一次要几秒 —— 读一次会撞在「页面刚打开、列表还没来」的窗口上。
  // 超时后再断言「恰好 1 项」，把「没出现」与「出现两张」区分开报出来。
  const queueItems = page.locator('.queue__item', { hasText: ticketNo })
  const count = await poll(
    async () => queueItems.count(),
    (n) => n >= 1,
    { timeoutMs: 30000 },
  )
  if (count !== 1) {
    throw new Error(`工单 ${ticketNo} 在客服队列里应恰好 1 项，实际 ${count} 项`)
  }
  await queueItems.first().click()
  await page.waitForTimeout(600)

  const compose = page.locator('.compose__actions')
  const replyBox = page.getByPlaceholder('回复用户。回复会自动接手这条工单，状态推到「处理中」。')
  await replyBox.fill('已收到你的反馈，这边帮你查一下。')
  // 用 exact 限定按钮名：队列项的球权文案里也有「回复」二字，宽匹配会点到队列上去
  await compose.getByRole('button', { name: '回复', exact: true }).click()

  const replied = await poll(read, (v) => v.ticket.status === 'PROCESSING')
  ck(
    '回复后工单被自动接手，状态推到 PROCESSING',
    replied.ticket.status === 'PROCESSING',
    `status=${replied.ticket.status}`,
  )
  ck(
    '回复内容落进了会话',
    replied.messages.some((m) => m.content.includes('这边帮你查一下')),
    `消息 ${replied.messages.length} 条`,
  )

  // 关闭只拦请求断言接线，不真发：真关下去这张单就永远停在终态，下一轮只能再建一张。
  // 状态流转本身由上面「回复→接手」和下面「标记解决」两条真跑覆盖，这里补的是
  // 「按钮接的是哪个接口、原因进了请求体还是 query」——接线错误恰好是构建期查不出来的那类
  let closeReq = null
  const intercept = async (route) => {
    closeReq = route.request().postDataJSON()
    const fresh = await api(`/tickets/admin/tickets/${ticketId}`)
    await route.fulfill({
      status: 200,
      contentType: 'application/json;charset=UTF-8',
      body: JSON.stringify({ code: 200, data: fresh, msg: '拦截验证，未真正关闭' }),
    })
  }
  await page.route('**/tickets/admin/tickets/*/close', intercept)
  await compose.getByRole('button', { name: '关闭工单', exact: true }).click()
  await answerMessageBox(page, { text: '自动化验收：验证完毕', button: '关闭工单' })
  await page.waitForTimeout(1500)
  await page.unroute('**/tickets/admin/tickets/*/close', intercept)

  ck(
    '「关闭工单」调的是 close 接口，原因走请求体（不是 query）',
    closeReq?.reason === '自动化验收：验证完毕',
    `实发 ${JSON.stringify(closeReq)}`,
  )
  ck(
    '拦截确实生效（工单没被真的关掉，仍是 PROCESSING）',
    (await read()).ticket.status === 'PROCESSING',
    `status=${(await read()).ticket.status}`,
  )

  // 标记已解决：这个按钮是 `disabled = !draft.trim()`，得先往回复框里写字才能点，
  // 而框里的内容会作为解决说明发给用户
  await replyBox.fill('本轮的接线检查做完了，如果还有问题请在 7 天内重开这条工单。')
  await compose.getByRole('button', { name: '标记已解决', exact: true }).click()
  await answerMessageBox(page, { button: '标记解决' })
  const resolved = await poll(read, (v) => v.ticket.status === 'RESOLVED')
  ck(
    '标记解决后状态为 RESOLVED（这是本轮留下的终态，下一轮重开它）',
    resolved.ticket.status === 'RESOLVED',
    `status=${resolved.ticket.status}`,
  )
  ck(
    '解决说明进了会话',
    resolved.messages.some((m) => m.content.includes('7 天内重开')),
    `消息 ${resolved.messages.length} 条`,
  )
})

// ── 3.6 售后审核：没有 APPLIED 的单就只断言接口形态（不真发） ──
await section('售后审核接口形态', async () => {
  const list = await api('/after-sales/admin/after-sales?page=0&size=50')
  const applied = list.records.find((a) => a.status === 'APPLIED')
  if (!applied) {
    // 不静默通过：这一条没覆盖到就得说出来，不能让它看起来像测过了
    skip(
      '售后审核',
      `库里当前没有 APPLIED 状态的售后单（现有：${[...new Set(list.records.map((a) => a.status))].join(' / ') || '无'}）`,
    )
    return
  }
  const row = await openRow(page, '/after-sales', applied.afterSaleNo)
  await openDrawer(page, row, '处理')

  let sent = null
  const intercept = async (route) => {
    if (route.request().method() !== 'POST') {
      return route.continue()
    }
    sent = route.request().url()
    await route.fulfill({
      status: 200,
      contentType: 'application/json;charset=UTF-8',
      body: JSON.stringify({ code: 200, data: null, msg: '拦截验证，未真正审核' }),
    })
  }
  await page.route('**/after-sales/admin/**', intercept)
  await drawer(page).getByRole('button', { name: '同意', exact: true }).click()
  await answerMessageBox(page, { button: '同意' })
  await page.waitForTimeout(1500)
  await page.unroute('**/after-sales/admin/**', intercept)

  ck(
    '同意售后调的是审核接口，且裁决走 query 参数（approved=true）',
    sent !== null &&
      sent.includes(`/after-sales/admin/after-sales/${applied.id}/audit`) &&
      sent.includes('approved=true'),
    `实际请求 ${sent ?? '（没抓到）'}`,
  )
})

// ───────────────────────── 四、还原 ─────────────────────────

// 还原动作自带导航：第三节结束时页面停在别处、抽屉也早关了，
// 依赖「页面还停在原处」的还原闭包会全部超时（第一版就是这么挂的）
console.log('\n【四】还原')
for (const undo of [...restores].reverse()) {
  try {
    await undo()
  } catch (e) {
    ck('还原动作执行', false, e instanceof Error ? e.message : String(e))
  }
}

const leftover = page.__errors.filter((t) => !/favicon|DevTools|Failed to load resource/i.test(t))
if (leftover.length) {
  console.log(`\n浏览器控制台报错：\n  ${leftover.join('\n  ')}`)
  fail += leftover.length
}

await page.context().close()
await browser.close()

console.log(`\n== pass=${pass} fail=${fail} ==`)
process.exit(fail === 0 ? 0 : 1)
