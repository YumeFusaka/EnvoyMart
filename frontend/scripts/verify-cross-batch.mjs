/**
 * 跨批接缝验收（批次 13g）—— 单批脚本都绿了，缝在哪儿？
 *
 * 批次 13a–13f 各自都有验收脚本，每条链路自己都是通的。但**两批的交界处**没人量过：
 * 13a 做的收藏/购物车失效标记，与 13f 做的结算页口径统一，在「商品下架之后会怎样」
 * 这件事上首尾相接，而接线的那一段恰好没被任何一方的断言覆盖。
 *
 * 接缝 A —— **下架的商品能不能交易**。
 *   13a 让购物车把失效商品标出来而不是删掉，13f 让结算页的失效提示与服务端同源。
 *   两批都假设「下架」这件事在交易链路上已经生效，而它此前并没有：判据散在
 *   购物车与订单两处、每处只看 SKU 一层状态，SPU 下架后详情接口已经回「不存在」，
 *   商品照样能加购、能试算、能下单。这里量的就是这两端是否终于对齐：
 *   下架 → 购物车标记失效 → 再加购被拒 → 试算不计金额并如实报数 → 提交被拒 → 上架复原。
 *
 * 接缝 C —— **试算的应付金额与真实下单的实付金额是否同一个数**。
 *   13f 把「应付多少钱」从前端的正则里搬到了服务端试算。但试算是**另一次调用**：
 *   购物车在这两次调用之间没有任何变化时两张单子必须一模一样，否则用户看到的
 *   与扣掉的不是一个数。这条断言跨了 preview 与 checkout 两个接口与两块代码路径。
 *
 * 数据影响：
 *   - 一次性账号（`cbx<时间戳>`）名下留 1 笔已取消订单；不碰 alice / bob 的购物车。
 *   - **会短暂下架 SPU 1（维生素 D3 软胶囊）再恢复**。这是本脚本唯一影响演示库的动作，
 *     所以入口先还原、finally 再还原 —— 两次都做是因为 `finally` 挡不住进程被 SIGKILL
 *     （2026-10-01 实锤：verify-favorites 被中断后 SPU 1 一直停在下架态，演示时商品消失）。
 *
 * 前置条件：后端九个服务已启动。
 *
 * 用法：
 *   node scripts/verify-cross-batch.mjs
 */
const GW = process.env.VERIFY_GW ?? 'http://localhost:8080'

/** SPU 1 = 维生素 D3 软胶囊；SKU 2 = 它的 400IU×90粒 规格，单价 9900 */
const SPU = 1
const SKU = 2
/** 「营养保健满 100 减 20」，作用域一级类目 1，SKU 2 在范围内 */
const COUPON_TEMPLATE = 5
const COUPON_THRESHOLD = 10000
const COUPON_DEDUCT = 2000

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
/** 只要 data；准备数据的调用失败就该中断，而不是让后面的断言去猜 */
async function data(path, opts) {
  const r = await call(path, opts)
  if (r.code !== 200) throw new Error(`${opts?.method ?? 'GET'} ${path} 失败：${r.msg}`)
  return r.data
}

async function login(username, password) {
  const r = await call('/auth/login', { method: 'POST', body: { username, password } })
  return r.data?.token
}

/** 管理员改上下架。幂等：本来就是这个状态也回 200 */
const setSpuStatus = (status) =>
  call(`/products/admin/spus/${SPU}/status?status=${status}`, { method: 'PUT', token: ADMIN })

/** 入口与 finally 都调用它 —— 一次不够，见文件头「finally 挡不住 SIGKILL」 */
const restoreSpu = () => setSpuStatus(1)

// ─────────── 登录与账号 ───────────
const ADMIN = await login('admin', '123456')
if (!ADMIN) {
  console.error('FATAL: admin 登录失败——先启动后端')
  process.exit(1)
}

// 入口还原：上一次可能是被 SIGKILL 掉的，SPU 停在下架态会让下面的基线断言全红，
// 而那是残留状态不是回归
const entryRestore = await restoreSpu()
if (entryRestore.code !== 200) {
  console.error(`FATAL: 无法先把 SPU ${SPU} 恢复上架（${entryRestore.msg}），后续断言没有意义`)
  process.exit(1)
}

const username = `cbx${Date.now().toString().slice(-9)}`
const registered = await call('/auth/register', {
  method: 'POST',
  body: { username, password: 'verify12345', nickname: '跨批验收用户' },
})
if (registered.code !== 200) {
  console.error(`FATAL: 注册验收账号失败：${registered.msg}`)
  process.exit(1)
}
const T = await login(username, 'verify12345')
console.log(`（验收账号 ${username}）`)

const CHECKOUT_BODY = {
  receiverName: '张三',
  receiverPhone: '13800000001',
  receiverProvince: '上海市',
  receiverCity: '上海市',
  receiverDistrict: '徐汇区',
  receiverDetail: '漕河泾开发区 1 号楼',
  remark: 'verify-cross-batch',
}

async function setCart(spec) {
  for (const item of await data('/cart', { token: T })) {
    await data(`/cart/items/${item.id}`, { method: 'DELETE', token: T })
  }
  for (const [skuId, quantity] of spec) {
    await data('/cart/items', { method: 'POST', token: T, body: { skuId, quantity } })
  }
}
const cartLine = async (skuId) => (await data('/cart', { token: T })).find((i) => i.skuId === skuId)
const preview = (userCouponId) =>
  data('/orders/preview', {
    method: 'POST',
    token: T,
    body: { userCouponId: userCouponId ?? null },
  })

let orderIdToCancel = null
try {
  // ─────────── 接缝 A：下架的商品不能交易 ───────────
  console.log('\n一、接缝 A：商品下架后，交易链路认不认这件事')

  await setCart([[SKU, 1]])
  const before = await cartLine(SKU)
  ck('上架态基线：购物车这一行可用', before?.available === true, JSON.stringify(before))
  const detailOnSale = await call(`/products/${SPU}`)
  ck('上架态基线：商品详情可见', detailOnSale.code === 200, `code=${detailOnSale.code}`)

  await setSpuStatus(0)

  const detailOffSale = await call(`/products/${SPU}`)
  // 展示层早就生效了 —— 这条是下面几条的对照：差异不在「下架有没有用」，
  // 而在「下架这件事被哪些层看见了」
  ck(
    '下架后：商品详情已不可见（展示层）',
    detailOffSale.code !== 200,
    `code=${detailOffSale.code} msg=${detailOffSale.msg}`,
  )

  const after = await cartLine(SKU)
  ck(
    '下架后：购物车里那一行被标成失效（修复前这里是 available=true）',
    after?.available === false,
    JSON.stringify(after),
  )

  const addAgain = await call('/cart/items', {
    method: 'POST',
    token: T,
    body: { skuId: SKU, quantity: 1 },
  })
  ck(
    '下架后：再加购被拒（409 商品已下架）',
    addAgain.code === 409 && String(addAgain.msg).includes('已下架'),
    `code=${addAgain.code} msg=${addAgain.msg}`,
  )

  const previewOffSale = await preview(null)
  ck(
    '下架后：试算如实报 1 件不可用，且不计金额',
    previewOffSale.unavailableCount === 1 &&
      previewOffSale.itemCount === 0 &&
      previewOffSale.totalAmount === 0 &&
      previewOffSale.payAmount === 0,
    JSON.stringify(previewOffSale).slice(0, 200),
  )

  const checkoutOffSale = await call('/orders/checkout', {
    method: 'POST',
    token: T,
    body: { ...CHECKOUT_BODY },
  })
  ck(
    '下架后：提交被拒，文案指向「已下架」而不是含糊的库存不足',
    checkoutOffSale.code !== 200 && String(checkoutOffSale.msg).includes('已下架'),
    `code=${checkoutOffSale.code} msg=${checkoutOffSale.msg}`,
  )

  // ─────────── 接缝 A 的反向断言：上架复原后必须回到可买 ───────────
  const backOnSale = await restoreSpu()
  ck('恢复上架成功', backOnSale.code === 200, `code=${backOnSale.code} msg=${backOnSale.msg}`)

  const restored = await cartLine(SKU)
  ck('恢复上架后：购物车那一行重新可用', restored?.available === true, JSON.stringify(restored))
  const previewOnSale = await preview(null)
  ck(
    '恢复上架后：试算回到 1 件可用、无不可用计数',
    previewOnSale.unavailableCount === 0 && previewOnSale.itemCount === 1,
    JSON.stringify(previewOnSale).slice(0, 200),
  )

  // ─────────── 接缝 C：试算的应付 = 真实下单的实付 ───────────
  console.log('\n二、接缝 C：券试算给出的应付金额，就是下单扣掉的实付金额')

  const received = await call(`/coupons/${COUPON_TEMPLATE}/receive`, { method: 'POST', token: T })
  ck('领券成功', received.code === 200, `code=${received.code} msg=${received.msg}`)
  const userCouponId = received.data?.id

  await setCart([[SKU, 2]])
  const previewCoupon = await preview(userCouponId)
  // 先钉死券真的生效了：否则下面「两个数相等」在 0 == 0 时也是绿的，等于什么都没测
  ck(
    `试算：2 件小计 ${2 * 9900} ≥ 门槛 ${COUPON_THRESHOLD}，抵扣 ${COUPON_DEDUCT}`,
    previewCoupon.discountAmount === COUPON_DEDUCT,
    JSON.stringify(previewCoupon).slice(0, 200),
  )

  const created = await call('/orders/checkout', {
    method: 'POST',
    token: T,
    body: { ...CHECKOUT_BODY, userCouponId },
  })
  if (created.code !== 200) {
    ck('下单成功（接缝 C 的前提）', false, `code=${created.code} msg=${created.msg}`)
    throw new Error(`下单失败，接缝 C 无法继续：${created.msg}`)
  }
  orderIdToCancel = created.data.id

  ck(
    `实付 === 试算应付（${previewCoupon.payAmount}）`,
    created.data.payAmount === previewCoupon.payAmount,
    `下单 ${created.data.payAmount} vs 试算 ${previewCoupon.payAmount}`,
  )
  ck(
    `抵扣 === 试算抵扣（${previewCoupon.discountAmount}）`,
    created.data.discountAmount === previewCoupon.discountAmount,
    `下单 ${created.data.discountAmount} vs 试算 ${previewCoupon.discountAmount}`,
  )

  // 响应回显可能与落库不一致，所以再查一次订单本体：前端的订单详情读的是这个
  const stored = await data(`/orders/${orderIdToCancel}`, { token: T })
  ck(
    '订单详情里的实付与下单响应一致（落库一致，不只是回显）',
    stored.payAmount === previewCoupon.payAmount,
    `详情 ${stored.payAmount} vs 试算 ${previewCoupon.payAmount}`,
  )

  const mine = await data('/coupons/mine', { token: T })
  const used = mine.find((c) => c.id === userCouponId)
  ck(
    '下单后券不再是未使用（钱减了，券也得真的核销掉）',
    used && used.status !== 'UNUSED',
    JSON.stringify(used),
  )
} finally {
  await restoreSpu().catch(() => {})
  if (orderIdToCancel) {
    await call(`/orders/${orderIdToCancel}/cancel`, { method: 'POST', token: T }).catch(() => {})
  }
}

// ════ 三、全 broker 的死信队列必须为空 ════
console.log('\n三、死信队列：一条都不该躺着')
// 为什么值得一条常驻断言：死信不是"消息系统的内部状态"，而是"某件本该发生的事没发生"。
// 实测一笔真实支付订单的销量事件在死信队列躺了 24 小时（重试耗尽被 reject，没人看、没回放），
// 商品少记 3 件销量且全程无声。它放在跨批脚本里而不是任何单批脚本里：
// 每批脚本只断言自己那条链路，而"有没有消息死在没人看的地方"不属于任何一批。
const rabbitAuth = 'Basic ' + Buffer.from(
  `${process.env.RABBITMQ_USERNAME ?? 'envoymart'}:${process.env.RABBITMQ_PASSWORD ?? 'envoymart123'}`,
).toString('base64')
const queues = await fetch(
  `${process.env.RABBITMQ_API ?? 'http://127.0.0.1:15672'}/api/queues?columns=name,messages_ready`,
  { headers: { Authorization: rabbitAuth } },
)
  .then((r) => r.json())
  .catch(() => null)
// 只看 messages_ready 不看 messages：后者含 unacked，正常消费中（毫秒级）的消息
// 也会被算进去，那是在测消费速度而不是测"有没有消息死了"
const deadQueues = (queues ?? []).filter((q) => String(q.name).includes('dlx'))
// 先钉死"真的查到了死信队列"：查不到队列时下面"过滤后为空"在空集上恒真，
// 接口挂了、认证错了都会被这层绿掉——空集的真不是真
ck(
  `查到了 ${deadQueues.length} 个死信队列（三个服务各一，不是"没查到"）`,
  deadQueues.length >= 3,
  queues === null ? '管理 API 不可达或认证失败' : deadQueues.map((q) => q.name).join(', '),
)
const notEmpty = deadQueues.filter((q) => Number(q.messages_ready) > 0)
ck(
  '死信队列全部为空（有条目 = 有事件没人处理，先看 x-death 再回放）',
  notEmpty.length === 0,
  notEmpty.map((q) => `${q.name} 躺着 ${q.messages_ready} 条`).join('；'),
)

console.log(`\n===== 跨批接缝验收：${pass} 通过 / ${fail} 失败 =====`)
process.exit(fail ? 1 : 0)
