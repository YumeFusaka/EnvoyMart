/**
 * C 端交易闭环的端到端验收（批次 11 固化）。
 *
 * 为什么单开一个脚本：交易闭环的正确性横跨三个服务与五种状态机——
 * 订单（取消/投影/自动完成）、支付（退款幂等）、售后（审核/寄回/退款/换货短路），
 * 单测只能覆盖各自服务内的分支，而「钱退了几笔」「库存补了几次」「订单被投成了什么态」
 * 只有把整条路跑通才看得见。本脚本把批次 11 实施期的五个场景固化为一条可重复执行的验收。
 *
 * 数据影响：
 *   - 每跑一轮新建 4~5 笔订单（全部推进到终态：取消退款 / 退货完成 / 换货完成 / 自动完成）
 *   - 场景 4 临时注入一张折扣券并在结尾删除；场景 5 把一笔订单的收货时间改老 8 天
 *   - 订单表本就是只增不减的流水，终态不还原
 *
 * 断言分工：业务事实走公开 API 复读（订单态、退款列表、券状态、售后详情），
 * 夹具注入与流水级反向断言走直连 SQL——后者没有也不该有公开接口。
 *
 * 前置条件：后端九个服务已启动（网关 8080、支付 9005 可达），种子数据在位
 * （券 5 = 限类目 2,3,4,5,6 满 10000 减 2000；sku 4/11/16 在售）。
 * 场景 5 需要等调度器（每 60s 一趟），整轮约 3~6 分钟。
 *
 * 用法：
 *   node scripts/verify-trade-flow.mjs
 */
import { execSync } from 'node:child_process'

const GW = process.env.VERIFY_GW ?? 'http://127.0.0.1:8080'
const PAY = process.env.VERIFY_PAY ?? 'http://127.0.0.1:9005'
const MYSQL_BIN =
  process.env.VERIFY_MYSQL ?? 'E:/Tool/mysql-8.0.31-winx64/bin/mysql.exe'
const ALICE = { username: 'alice', password: '123456' }
const ADMIN = { username: 'admin', password: '123456' }

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
async function poll(fn, predicate, timeoutMs = 400000, stepMs = 10000) {
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

/** 直连 SQL（夹具注入 + 流水级断言；SQL 保持纯 ASCII 避免命令行编码问题） */
function sql(q) {
  return execSync(
    `"${MYSQL_BIN}" -h127.0.0.1 -P3306 -uyumefusaka -pj -N --default-character-set=utf8mb4 -e "${q}"`,
    // stderr 静默：压掉「密码写在命令行」的例行警告；SQL 真失败靠非零退出码抛出
    { encoding: 'utf8', stdio: ['pipe', 'pipe', 'pipe'] },
  ).trim()
}

async function call(base, path, { method = 'GET', token, body } = {}) {
  const res = await fetch(`${base}${path}`, {
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
  const r = await call(GW, '/auth/login', { method: 'POST', body: { username, password } })
  return r.data?.token
}

const T = await login(ALICE)
const A = await login(ADMIN)
if (!T || !A) {
  console.error('FATAL: 登录失败——先启动后端')
  process.exit(1)
}

const CHECKOUT_BODY = {
  receiverName: '张三',
  receiverPhone: '13800000001',
  receiverProvince: '上海市',
  receiverCity: '上海市',
  receiverDistrict: '徐汇区',
  receiverDetail: '漕河泾开发区 1 号楼',
}

async function clearCart() {
  const r = await call(GW, '/cart', { token: T })
  for (const item of r.data ?? []) {
    await call(GW, `/cart/items/${item.id}`, { method: 'DELETE', token: T })
  }
}

async function addItem(skuId, quantity) {
  await call(GW, '/cart/items', { method: 'POST', token: T, body: { skuId, quantity } })
}

async function checkout(userCouponId) {
  return call(GW, '/orders/checkout', {
    method: 'POST',
    token: T,
    body: { ...CHECKOUT_BODY, remark: 'verify-trade-flow', ...(userCouponId ? { userCouponId } : {}) },
  })
}

async function payOrder(orderId) {
  await call(GW, '/payments', { method: 'POST', token: T, body: { orderId, channel: 'MOCK', payType: 'MOCK' } })
  return call(GW, `/payments/${orderId}/mock-pay`, { method: 'POST', token: T })
}

async function shipOrder(orderId) {
  return call(GW, `/orders/admin/orders/${orderId}/ship`, {
    method: 'POST',
    token: A,
    body: { carrierCode: 'SF', carrierName: '顺丰速运', trackingNo: `SF-${orderId}` },
  })
}

async function getOrder(orderId) {
  const r = await call(GW, `/orders/${orderId}`, { token: T })
  return r.data
}

async function refundsOf(orderId) {
  const r = await call(GW, `/payments/refunds/${orderId}`, { token: T })
  return r.data ?? []
}

async function couponStatus(userCouponId) {
  const r = await call(GW, '/coupons/mine', { token: T })
  return (r.data ?? []).find((c) => c.id === userCouponId)?.status
}

/** 下单+支付+发货+收货，返回订单（与 after-sales 场景共用的前置链） */
async function buyReceived(skuId = 16, quantity = 1) {
  await clearCart()
  await addItem(skuId, quantity)
  const o = await checkout()
  const oid = o.data?.id
  if (!oid) {
    console.log(`  FATAL: 下单失败 ${JSON.stringify(o)}`)
    process.exit(1)
  }
  await payOrder(oid)
  await shipOrder(oid)
  await call(GW, `/orders/${oid}/receive`, { method: 'POST', token: T })
  return oid
}

async function applyAfterSale(orderId, body) {
  const order = await getOrder(orderId)
  const itemId = order.items[0].id
  return call(GW, '/after-sales', {
    method: 'POST',
    token: T,
    body: { orderItemId: itemId, ...body },
  })
}

// ════ 场景 1：已支付取消 —— 全额退款 + 幂等重试 + 终态拒绝 ════
console.log('\n== 场景 1 已支付取消（sku5 x2）==')
await clearCart()
const stock1Before = Number(sql(`select stock from envoymart_product.product_sku where id=5`))
await addItem(5, 2)
const O1 = (await checkout()).data
ck('下单成功', O1?.id > 0, JSON.stringify(O1))
await payOrder(O1.id)
ck('支付成功', (await getOrder(O1.id)).status === 'PAID')

const r1 = await call(GW, `/orders/${O1.id}/cancel`, { method: 'POST', token: T })
ck('取消即退款：订单转 REFUNDED', r1.data?.status === 'REFUNDED', JSON.stringify(r1))
ck('closedAt 已写入', Boolean((await getOrder(O1.id)).closedAt))

const refunds1 = await refundsOf(O1.id)
ck('退款恰好 1 条', refunds1.length === 1, `实际 ${refunds1.length} 条`)
ck('退款金额=订单实付', refunds1[0]?.amount === O1.payAmount, `${refunds1[0]?.amount} vs ${O1.payAmount}`)
ck('幂等键正确（CANCEL:单号）', sql(`select biz_no from envoymart_payment.refund where order_id=${O1.id}`) === `CANCEL:${O1.orderNo}`)

// 幂等重试：模拟「上次成功但响应丢了」，同一 bizNo 再来一次
const retry = await call(PAY, '/payments/internal/refunds', {
  method: 'POST',
  body: { orderId: O1.id, bizNo: `CANCEL:${O1.orderNo}`, reason: 'verify-retry' },
})
ck('同 bizNo 重试返回同一笔 refundNo', retry.data?.refundNo === refunds1[0]?.refundNo, JSON.stringify(retry))
ck('退款仍只有 1 条', (await refundsOf(O1.id)).length === 1)

const again = await call(GW, `/orders/${O1.id}/cancel`, { method: 'POST', token: T })
ck('终态再取消被拒', again.code === 409, `code=${again.code}`)
ck('拒绝文案无叠字', again.msg === '订单已退款，无需重复操作', again.msg)
ck('库存回补到取消前', Number(sql(`select stock from envoymart_product.product_sku where id=5`)) === stock1Before)

// ════ 场景 2：退货退款全程 —— 申请→审核→寄回→收货→退款→订单投影 ════
console.log('\n== 场景 2 退货退款全程（sku16 x1）==')
const O2 = await buyReceived(16, 1)
const stock2Before = Number(sql(`select stock from envoymart_product.product_sku where id=16`))

const AS2 = await applyAfterSale(O2, {
  type: 'RETURN_REFUND',
  reason: '商品与描述不符，味道刺鼻',
  description: '拆封后食用过一次，与页面描述差异较大',
  qualityIssue: true,
})
const as2Id = AS2.data?.id
ck('售后申请受理', as2Id > 0 && AS2.data.status === 'APPLIED', JSON.stringify(AS2))
ck('政策引擎给出可退金额 6900', AS2.data.refundAmount === 6900, `${AS2.data.refundAmount}`)

await call(GW, `/after-sales/admin/after-sales/${as2Id}/audit?approved=true&remark=${encodeURIComponent('同意')}`, {
  method: 'POST',
  token: A,
})
const sb = await call(GW, `/after-sales/${as2Id}/ship-back`, {
  method: 'POST',
  token: T,
  body: { carrier: '顺丰速运', trackingNo: `SFB-${as2Id}` },
})
ck('寄回登记转 RETURNING', sb.data?.status === 'RETURNING')
ck('响应回带承运商与单号', sb.data?.returnCarrier === '顺丰速运' && sb.data?.returnTrackingNo === `SFB-${as2Id}`)
ck('returnedAt 已写入', Boolean(sb.data?.returnedAt))

const detail2 = await call(GW, `/after-sales/${as2Id}`, { token: T })
ck('用户侧流水不带 operatorId', (detail2.data?.logs ?? []).every((l) => l.operatorId === null))

const recv2 = await call(GW, `/after-sales/admin/after-sales/${as2Id}/received`, { method: 'POST', token: A })
ck('确认收货即完成', recv2.data?.status === 'FINISHED', JSON.stringify(recv2))

const refunds2 = await refundsOf(O2)
ck('退款 1 条 6900 且挂售后单', refunds2.length === 1 && refunds2[0].amount === 6900 && refunds2[0].afterSaleId === as2Id, JSON.stringify(refunds2))
ck('订单投影为 REFUNDED', (await getOrder(O2)).status === 'REFUNDED')
ck('库存回补 +1', Number(sql(`select stock from envoymart_product.product_sku where id=16`)) === stock2Before + 1)

// ════ 场景 3：换货（0 元）—— 不调支付、不投影订单、无假流水 ════
console.log('\n== 场景 3 换货 0 元（sku16 x1）==')
const O3 = await buyReceived(16, 1)
const AS3 = await applyAfterSale(O3, {
  type: 'EXCHANGE',
  reason: '规格买错，想换成同款小规格',
  description: '未拆封，包装完好',
  qualityIssue: false,
})
const as3Id = AS3.data?.id
ck('换货申请金额为 0', AS3.data?.refundAmount === 0, `${AS3.data?.refundAmount}`)

await call(GW, `/after-sales/admin/after-sales/${as3Id}/audit?approved=true&remark=OK`, { method: 'POST', token: A })
await call(GW, `/after-sales/${as3Id}/ship-back`, {
  method: 'POST',
  token: T,
  body: { carrier: '顺丰速运', trackingNo: `SFB-${as3Id}` },
})
const recv3 = await call(GW, `/after-sales/admin/after-sales/${as3Id}/received`, { method: 'POST', token: A })
ck('0 元短路直达 FINISHED', recv3.data?.status === 'FINISHED', JSON.stringify(recv3))

ck('全程无退款记录', (await refundsOf(O3)).length === 0)
ck('订单回 RECEIVED（不是 REFUNDED）', (await getOrder(O3)).status === 'RECEIVED')
// 反向断言：0 元短路的旧缺陷是「先投影 REFUNDING 再短路」，最终态看不出来，只有流水能抓
ck(
  '订单流水无假「退款发起」',
  sql(`select count(*) from envoymart_order.order_status_log where order_id=${O3} and remark like '%退款发起%'`) === '0',
)

// ════ 场景 4：券作用域 —— 门槛与折扣基数都是范围内小计 ════
console.log('\n== 场景 4 券作用域（券 5：限类目 2~6 满 10000 减 2000）==')
await clearCart()
let receive5 = await call(GW, '/coupons/5/receive', { method: 'POST', token: T })
let uc5 = receive5.data?.id
if (!uc5) {
  uc5 = Number(
    sql(`select id from envoymart_promotion.user_coupon where user_id='u1001' and coupon_id=5 and status='UNUSED' order by id limit 1`),
  )
}
ck('拿到券 5（领取或复用）', uc5 > 0, `userCouponId=${uc5}`)

// 4.1 只有范围外商品 → 拒绝且券不消耗
await addItem(16, 2)
const c41 = await checkout(uc5)
ck('范围外商品拒绝下单', c41.code === 409, `code=${c41.code}`)
ck('提示「不适用于订单中的商品」', c41.msg === '该优惠券不适用于订单中的商品', c41.msg)
ck('券未被消耗', (await couponStatus(uc5)) === 'UNUSED')

// 4.2 总额够门槛但范围内不够 → 拒绝
await clearCart()
await addItem(16, 2)
await addItem(11, 1)
const c42 = await checkout(uc5)
ck('范围内不足门槛拒绝', c42.code === 409, `code=${c42.code}`)
ck('文案按范围内金额算差额（还差 3100 分）', c42.msg === '优惠券适用范围内的商品金额未达到使用门槛，还差 3100 分', c42.msg)
ck('券仍未被消耗', (await couponStatus(uc5)) === 'UNUSED')

// 4.3 范围内够门槛 → 核销；取消未支付订单 → 券退回
await clearCart()
await addItem(4, 1)
const c43 = await checkout(uc5)
ck('范围内下单成功', c43.code === 200, JSON.stringify(c43))
ck('抵扣 2000', c43.data?.discountAmount === 2000, `${c43.data?.discountAmount}`)
ck('应付 10800', c43.data?.payAmount === 10800, `${c43.data?.payAmount}`)
ck('券已核销', (await couponStatus(uc5)) === 'USED')
await call(GW, `/orders/${c43.data.id}/cancel`, { method: 'POST', token: T })
ck('取消未支付订单后券退回', (await couponStatus(uc5)) === 'UNUSED')

// 4.4 折扣基数是范围内小计（限类目 2 的 8.5 折）
sql(
  `insert into envoymart_promotion.coupon (id,name,type,amount,discount,threshold,scope_type,scope_ids,total_count,received_count,valid_days,status,created_at) select 6,'Nutrition 15% off (verify-test)','DISCOUNT',null,0.85,0,'CATEGORY','2',100,0,30,1,now() where not exists (select 1 from envoymart_promotion.coupon where id=6)`,
)
const receive6 = await call(GW, '/coupons/6/receive', { method: 'POST', token: T })
const uc6 =
  receive6.data?.id ??
  Number(sql(`select id from envoymart_promotion.user_coupon where user_id='u1001' and coupon_id=6 and status='UNUSED' order by id limit 1`))
await clearCart()
await addItem(16, 2)
await addItem(4, 1)
const c44 = await checkout(uc6)
ck('混合购物车下单成功', c44.code === 200, JSON.stringify(c44))
ck('总额 26600（含范围外 13800）', c44.data?.totalAmount === 26600, `${c44.data?.totalAmount}`)
ck('抵扣=12800*0.15=1920（按总额算是 3990）', c44.data?.discountAmount === 1920, `${c44.data?.discountAmount}`)
ck('应付 24680', c44.data?.payAmount === 24680, `${c44.data?.payAmount}`)
await call(GW, `/orders/${c44.data.id}/cancel`, { method: 'POST', token: T })
sql(`delete from envoymart_promotion.user_coupon where coupon_id=6`)
sql(`delete from envoymart_promotion.coupon where id=6`)
await clearCart()

// ════ 场景 5：收货期满自动完成 —— 调度器推 COMPLETED ════
console.log('\n== 场景 5 收货 7 天期满自动完成（sku16 x1，需等调度器）==')
const O5 = await buyReceived(16, 1)
sql(`update envoymart_order.shop_order set received_at = now() - interval 8 day where id=${O5} and status='RECEIVED'`)
console.log('  已把收货时间改老 8 天，等待调度器（每 60s 一趟，轮询上限 400s）…')
const done = await poll(
  async () => sql(`select status from envoymart_order.shop_order where id=${O5}`),
  (s) => s === 'COMPLETED',
)
ck('调度器推到 COMPLETED', done === 'COMPLETED', `实际 ${done}`)
ck('finishedAt 已写入', sql(`select count(*) from envoymart_order.shop_order where id=${O5} and finished_at is not null`) === '1')

console.log(`\n== 交易闭环验收完成：PASS=${pass} FAIL=${fail} ==`)
if (fail > 0) {
  process.exitCode = 1
}
