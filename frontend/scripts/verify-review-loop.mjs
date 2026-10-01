/**
 * 批次 13d「评价闭环」的端到端验收。
 *
 * 为什么单开一个脚本：这一批把评价从「一张表加两个接口」接到了整条链路上——
 *   1. 评价提交后**回写商品的评分均值与评论数**（走 MQ，不是同步调用）
 *   2. 支付成功后**累加销量**（走 MQ + 台账，幂等）
 *   3. 评价列表分页与按星级 / 有图筛选（筛在服务端）
 *   4. 「我的评价」：含被隐藏的，带状态与隐藏原因
 *   5. 商品卡与详情页展示评分与销量
 *   6. 防刷：同用户每日上限 + 当日重复内容
 * 这六件事里有五件**错了也不报错**：均分不更新只是数字停着、销量不涨只是数字不动、
 * 筛选失效只是列表比筛的多、隐藏原因不返回只是那一行空着。全都得靠对账验。
 *
 * 断言分工：
 *   - 业务事实走公开 API 复读（商品详情、评价统计、搜索卡片），三处必须给出**同一个数**
 *   - 库内事实走直连 SQL（销量台账、点赞去重表），这两张表没有也不该有公开接口
 *   - 前端事实走真浏览器（商品卡评分行、订单详情的「评价 → 已评价」切换）
 *
 * 数据影响（终态不还原，与 verify-logistics 同一口径：这些表本来就是只增不减的流水）：
 *   - 每轮**新注册一个验收用户**，它名下会留下 1 笔订单 + 5 条评价
 *   - 商品 1 的均分与条数、销量 +3 因此变化（评价与销量都是流水，不还原）
 *   - 其中一条评价会被管理端隐藏后再恢复
 * 之所以每轮换用户：每日上限按用户按天算，固定账号跑第二遍就会被上一轮的额度挡住，
 * 而且会往演示账号名下堆测试数据。夹具注入的那 2 条评价是为**每日上限**服务的——
 * 不注入就得真下 5 笔订单收货再评价，代价远大于收益，
 * 而服务端的计数来自库表，两条路径写入的行在计数上没有差别。
 *
 * 前置条件：后端九个服务 + 前端已启动（run-local.sh demo）；商品 1 的三个规格在售。
 *
 * 用法：
 *   node scripts/verify-review-loop.mjs
 */
import { execSync } from 'node:child_process'
import { chromium } from 'playwright-core'

const BASE = process.env.VERIFY_BASE ?? 'http://localhost:5173'
const GW = process.env.VERIFY_GW ?? 'http://127.0.0.1:8080'
const MYSQL_BIN = process.env.VERIFY_MYSQL ?? 'E:/Tool/mysql-8.0.31-winx64/bin/mysql.exe'
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

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

function sql(q) {
  // 多行 SQL 必须压成一行：execSync 走 cmd.exe，`-e "..."` 引号里的换行会在
  // 第一个换行处把参数截断，MySQL 收到半句语法错误（ERROR 1064 ... near ''）
  const oneLine = q.replace(/\s+/g, ' ').trim()
  return execSync(
    `"${MYSQL_BIN}" -h127.0.0.1 -P3306 -uyumefusaka -pj -N --default-character-set=utf8mb4 -e "${oneLine}"`,
    { encoding: 'utf8', stdio: ['pipe', 'pipe', 'pipe'] },
  ).trim()
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

/** 最终一致：MQ 是异步的，商品侧那份冗余要给它一点时间。轮询而不是 sleep 一个定值 */
async function waitFor(fn, timeoutMs = 15000, stepMs = 400) {
  for (let waited = 0; waited <= timeoutMs; waited += stepMs) {
    const value = await fn().catch(() => null)
    if (value) {
      return value
    }
    await sleep(stepMs)
  }
  return null
}

async function login({ username, password }) {
  const r = await call('/auth/login', { method: 'POST', body: { username, password } })
  if (r.code !== 200) {
    throw new Error(`登录失败：${r.msg}（后端起了吗）`)
  }
  return r.data
}

const admin = await login({ username: 'admin', password: '123456' })
const A = admin.token

/**
 * 每轮**新注册一个用户**来跑评价，而不是用演示账号 alice。
 * <p>
 * 两个原因，都不是洁癖：
 *   1. 「每天最多 5 条」是按用户按天算的。用固定账号跑第二遍时，上一轮留下的 3 条
 *      已经把额度吃掉大半，脚本会在「第 3 条评价提交成功」这一步就失败——
 *      而那看起来像防刷功能坏了，其实是验收脚本自己不可重复。
 *   2. 评价是只增不减的用户数据，往演示账号名下堆「verify-review-loop 夹具一」
 *      这种行，二面演示时点开「我的评价」就穿帮。
 * 新用户从 0 条起步，上限测试才是确定的；跑完留下的数据也都在一个只跑过验收的账号名下。
 */
const tester = await (async () => {
  const username = `rvw${Date.now().toString().slice(-9)}`
  const registered = await call('/auth/register', {
    method: 'POST',
    body: { username, password: 'verify12345', nickname: '验收用户' },
  })
  if (registered.code !== 200) {
    throw new Error(`注册验收用户失败：${registered.msg}`)
  }
  return login({ username, password: 'verify12345' })
})()
const T = tester.token
const testerName = tester.user.username

/**
 * 库里的 `user_id` 是 JWT 的 subject（`u1001`），**不是用户名**。
 * <p>
 * 这里踩过一次：从 {@code order_item_id = -1} 的种子评价里读 user_id 拿到的是 `'alice'`——
 * 那批是「展示给商品页的演示评价」，作者写的是人名；而登录用户写下的评价作者是 JWT 主键。
 * 拿人名去数「今天发了几条」，数出来永远是自己注入的夹具，每日上限也就永远测不到
 * （表现为第 6 条被拒的理由是「已经评价过这一行」，而不是「今天到量了」）。
 * 真值只有一处：登录返回的身份，别再猜。
 */
const testerId = String(tester.user?.id ?? '')
if (!testerId) {
  console.error(`FATAL: 登录响应里没有用户主键，无法定位库里的评价作者：${JSON.stringify(tester.user)}`)
  process.exit(1)
}

const SPU = 1
const SKUS = [1, 2, 3]
const detail = async (id) => (await call(`/products/${id}`)).data
const statistics = async (id) => (await call(`/reviews/spu/${id}/statistics`)).data
const listReviews = async (id, qs = '') => (await call(`/reviews/spu/${id}?${qs}`)).data
const myReviews = async (qs = '') => (await call(`/reviews/mine?${qs}`, { token: T })).data

// ════ 〇、一笔可评价的订单（3 行，同商品不同规格） ════
console.log(`\n== 〇、准备一笔已收货订单 ==   （本轮用户 ${testerName} / ${testerId}）`)

const before = await detail(SPU)
const beforeSales = before.sales
const beforeStats = await statistics(SPU)

for (const skuId of SKUS) {
  await call('/cart/items', { method: 'POST', token: T, body: { skuId, quantity: 1 } })
}
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
    remark: 'verify-review-loop',
  },
})
const order = created.data
if (!order?.id || (order.items ?? []).length !== SKUS.length) {
  console.error(`FATAL: 下单失败或订单行数不对：${JSON.stringify(created)}`)
  process.exit(1)
}
const [itemA, itemB, itemC] = order.items

await call('/payments', { method: 'POST', token: T, body: { orderId: order.id, channel: 'MOCK', payType: 'MOCK' } })
await call(`/payments/${order.id}/mock-pay`, { method: 'POST', token: T })
const paid = await waitFor(async () => ((await call(`/orders/${order.id}`, { token: T })).data?.status === 'PAID' ? 1 : 0))
ck('订单已支付（payment.completed → order.paid 的异步投影）', paid === 1)

await call(`/orders/admin/orders/${order.id}/ship`, {
  method: 'POST',
  token: A,
  body: { carrierCode: 'SF', carrierName: '顺丰速运', trackingNo: `SF-${order.orderNo}` },
})
const received = await call(`/orders/${order.id}/receive`, { method: 'POST', token: T })
ck('确认收货成功（评价的前置条件）', received.data?.status === 'RECEIVED', `status=${received.data?.status}`)

// ════ 一、支付成功 → 销量累加（MQ + 台账） ════
console.log('\n== 一、支付成功后销量累加 ==')

const afterSales = await waitFor(async () => {
  const d = await detail(SPU)
  return d.sales === beforeSales + SKUS.length ? d.sales : 0
})
ck(
  `商品销量随支付成功累加 ${SKUS.length}`,
  afterSales === beforeSales + SKUS.length,
  `下单前 ${beforeSales}，现在 ${(await detail(SPU)).sales}`,
)

const ledger = sql(
  `select count(*) from envoymart_product.product_sales_ledger where order_id = ${order.id}`,
)
ck('销量落的是台账而不是直接改数——按订单行记了 3 行', ledger === '3', `实得 ${ledger} 行`)

// ════ 二、防刷：夹具 + 上限 + 重复内容 ════
console.log('\n== 二、防刷（每日上限 / 当日重复内容） ==')

// 夹具注入 2 条「今天」的评价，让额度从 3 起步而不是从 0 起步。
// order_item_id 用负数与真实订单行隔开（与 data.sql 的种子同一约定）。
// **status 取 HIDDEN**：额度计数不看状态（被隐藏的不该把额度还回来），
// 但它不该出现在商品页的评价列表里、也不该掺进均分——那是给面试官看的数据
sql(
  `insert into envoymart_review.review
     (spu_id, sku_id, order_id, order_item_id, user_id, rating, content, is_anonymous, status, useful_count, created_at)
   values
     (${SPU}, 1, -9001, -201, '${testerId}', 4, 'verify-review-loop 夹具一', 0, 'HIDDEN', 0, now()),
     (${SPU}, 1, -9002, -202, '${testerId}', 5, 'verify-review-loop 夹具二', 0, 'HIDDEN', 0, now())`,
    )

const newReview = (item, rating, content, extra = {}) =>
  call('/reviews', {
    method: 'POST',
    token: T,
    body: { orderId: order.id, orderItemId: item.id, rating, content, ...extra },
  })

const ratingA = await detail(SPU)
const reviewA = await newReview(itemA, 1, 'verify-review-loop 差评：包装破损，客服也没人接')
ck('第 3 条评价提交成功（额度未满）', reviewA.code === 200, `code=${reviewA.code} msg=${reviewA.msg}`)
ck(
  '商品信息由服务端从订单行反查，不采信请求体',
  reviewA.data?.spuId === SPU && reviewA.data?.skuId === itemA.skuId,
  `spuId=${reviewA.data?.spuId} skuId=${reviewA.data?.skuId}，订单行是 skuId=${itemA.skuId}`,
)

const duplicated = await newReview(itemB, 5, 'verify-review-loop 差评：包装破损，客服也没人接')
ck(
  '同日重复内容被拒，且是 409 而不是 200',
  duplicated.code === 409,
  `code=${duplicated.code} msg=${duplicated.msg}`,
)
ck('拒绝原因说得清是「内容完全相同」', (duplicated.msg ?? '').includes('完全相同'), duplicated.msg)

const reviewB = await newReview(itemB, 5, 'verify-review-loop 好评：吃着不错，物流也快', {
  images: ['/images/review/demo-1.jpg', '/images/review/demo-2.jpg'],
})
ck('第 4 条评价提交成功', reviewB.code === 200, `code=${reviewB.code} msg=${reviewB.msg}`)

const reviewC = await newReview(itemC, 4, 'verify-review-loop 中评：还行，价格偏高')
ck('第 5 条评价提交成功（正好用满今天的额度）', reviewC.code === 200, `code=${reviewC.code} msg=${reviewC.msg}`)

const overLimit = await newReview(itemA, 3, 'verify-review-loop 第六条')
ck('第 6 条被每日上限拦下（409）', overLimit.code === 409, `code=${overLimit.code} msg=${overLimit.msg}`)
ck(
  '上限文案给出了「今天几条 / 每天几条 / 什么时候能再来」',
  /每天最多\s*5\s*条/.test(overLimit.msg ?? '') && (overLimit.msg ?? '').includes('明天'),
  overLimit.msg,
)

const todayCount = sql(
  `select count(*) from envoymart_review.review
    where user_id = '${testerId}' and created_at >= curdate()`,
)
ck('库里今天的评价正好 5 条——被拒的那条没有落库', todayCount === '5', `实得 ${todayCount} 条`)

// ════ 三、聚合回写：商品侧与评价侧必须是同一个数 ════
console.log('\n== 三、评价 → 商品评分聚合（MQ 回写） ==')

// 只有本轮真提的 3 条进聚合：夹具那 2 条是 HIDDEN，不进任何对外可见的数字
const expectedTotal = Number(beforeStats.total) + 3
const expectedAvg = (
  (Number(beforeStats.average) * Number(beforeStats.total) +
    1 +
    5 +
    4) /
  expectedTotal
).toFixed(1)

const converged = await waitFor(async () => {
  const d = await detail(SPU)
  return d.reviewCount === expectedTotal ? d : 0
})
ck(
  `商品侧评论数回写到 ${expectedTotal}（评价提交 → MQ → product-service）`,
  converged?.reviewCount === expectedTotal,
  `实得 reviewCount=${(await detail(SPU)).reviewCount}`,
)

const statsNow = await statistics(SPU)
ck(
  '商品侧评论数与评价服务统计一致',
  (await detail(SPU)).reviewCount === Number(statsNow.total),
  `商品侧 ${(await detail(SPU)).reviewCount}，评价侧 ${statsNow.total}`,
)
ck(
  '均分按一位小数回写，两侧逐位相同',
  Number((await detail(SPU)).ratingAvg).toFixed(1) === Number(statsNow.average).toFixed(1),
  `商品侧 ${(await detail(SPU)).ratingAvg}，评价侧 ${statsNow.average}`,
)
ck(
  '回写的是真算出来的值：与按已发布评价手工重算的均分一致',
  Number(statsNow.average).toFixed(1) === expectedAvg,
  `期望 ${expectedAvg}，实得 ${statsNow.average}`,
)
ck(
  '一条 1 星评价把均分拉下来了',
  Number(statsNow.average) < Number(beforeStats.average || ratingA.ratingAvg),
  `提交前 ${beforeStats.average}，现在 ${statsNow.average}`,
)

// ════ 四、搜索卡片同步（ES） ════
console.log('\n== 四、搜索卡片与详情页同源 ==')

const keyword = String(itemA.spuName ?? '').split(/[\s·]/)[0].slice(0, 4)
const inSearch = await waitFor(async () => {
  const page = (await call(`/products/search?keyword=${encodeURIComponent(keyword)}&page=0&size=50`)).data
  const hit = (page?.records ?? []).find((p) => p.id === SPU)
  return hit && hit.reviewCount === expectedTotal ? hit : 0
})
ck(
  `ES 索引里的评分与库内一致（关键词「${keyword}」）`,
  inSearch?.reviewCount === expectedTotal &&
    Number(inSearch.ratingAvg).toFixed(1) === Number(statsNow.average).toFixed(1),
  inSearch ? `搜索侧 ${inSearch.ratingAvg}/${inSearch.reviewCount}` : `搜索没命中商品 ${SPU}`,
)

// ════ 五、列表分页与筛选 ════
console.log('\n== 五、评价列表：分页、星级筛选、有图筛选 ==')

const page1 = await listReviews(SPU, 'page=0&size=2')
const page2 = await listReviews(SPU, 'page=1&size=2')
ck('分页每页 2 条，总数与统计一致', page1.records.length === 2 && Number(page1.total) === expectedTotal)
ck('第二页与第一页不重叠', !page2.records.some((r) => page1.records.some((p) => p.id === r.id)))
ck(
  '翻页的排序带唯一兜底列（按 id 严格递减，同秒提交的两条不会漏也不会重）',
  page1.records[0].id > page1.records[1].id && page1.records[1].id > page2.records[0].id,
  `${page1.records.map((r) => r.id)} / ${page2.records.map((r) => r.id)}`,
)

const oneStar = await listReviews(SPU, 'rating=1&size=50')
ck(
  '按 1 星筛选只剩 1 星的，且包含刚提交的那条',
  oneStar.records.length > 0 &&
    oneStar.records.every((r) => r.rating === 1) &&
    oneStar.records.some((r) => r.id === reviewA.data.id),
  `实得 ${oneStar.records.length} 条`,
)
ck(
  '筛选后的总数是筛过的总数，不是全量总数',
  Number(oneStar.total) === oneStar.records.length && Number(oneStar.total) < expectedTotal,
  `total=${oneStar.total} 全量=${expectedTotal}`,
)

const badRating = await call(`/reviews/spu/${SPU}?rating=9`)
ck('越界星级直接拒绝而不是当作「不筛」', badRating.code === 400, `code=${badRating.code}`)

const withImage = await listReviews(SPU, 'hasImage=true&size=50')
ck(
  '「有图」筛在服务端，总数与统计里的有图数一致',
  Number(withImage.total) === Number(statsNow.withImage),
  `筛选 total=${withImage.total}，统计 withImage=${statsNow.withImage}`,
)
ck('带图的那条就是刚提交的', withImage.records.some((r) => r.id === reviewB.data.id))

const noImage = await listReviews(SPU, 'hasImage=true&size=1&page=0')
ck(
  '「有图」的第一页是真的有图，不是「这一页恰好都没图」',
  noImage.records.every((r) => (r.images ?? []).length > 0),
  JSON.stringify(noImage.records.map((r) => r.images?.length)),
)

// ════ 六、我的评价 ════
console.log('\n== 六、我的评价 ==')

const mine = await myReviews('size=50')
ck(
  '「我的评价」含本轮全部 5 条',
  [reviewA, reviewB, reviewC].every((r) => mine.records.some((m) => m.id === r.data.id)),
  `实得 ${mine.records.length} 条`,
)
ck(
  '每条都带订单行 id（订单详情据此标「已评价」）',
  mine.records.filter((m) => m.orderId === order.id).every((m) => typeof m.orderItemId === 'number'),
)
const mineWithProduct = mine.records.find((m) => m.id === reviewA.data.id)
ck(
  '带商品卡片数据，商品被删时前端才有东西可渲染（tombstone）',
  mineWithProduct?.product?.id === SPU && !!mineWithProduct.product.name,
  JSON.stringify(mineWithProduct?.product ?? null),
)

const mineOfOrder = await myReviews(`orderId=${order.id}&size=50`)
ck(
  '按订单过滤只回这一单的 3 条（订单详情页不整页拉回来再在浏览器里筛）',
  Number(mineOfOrder.total) === 3 && mineOfOrder.records.every((m) => m.orderId === order.id),
  `实得 ${mineOfOrder.total} 条`,
)

const anonymous = await newReview(itemA, 5, 'verify-review-loop 匿名')
ck('已评过的订单行不能重复评价', anonymous.code === 409, `code=${anonymous.code} msg=${anonymous.msg}`)

// ════ 七、管理端隐藏 → 聚合回落 + 用户看得见状态 ════
console.log('\n== 七、隐藏一条评价：商品侧均分要跟着变 ==')

const hidden = await call(`/reviews/admin/reviews/${reviewA.data.id}/status`, {
  method: 'PUT',
  token: A,
  body: { status: 'HIDDEN', reason: 'verify-review-loop：含违规内容' },
})
ck('管理端隐藏成功', hidden.code === 200, `code=${hidden.code} msg=${hidden.msg}`)

const afterHide = await waitFor(async () => {
  const d = await detail(SPU)
  return d.reviewCount === expectedTotal - 1 ? d : 0
})
ck(
  '隐藏一条 1 星评价后，商品侧评论数减 1（隐藏也是聚合的一部分）',
  afterHide?.reviewCount === expectedTotal - 1,
  `实得 ${(await detail(SPU)).reviewCount}`,
)
const statsAfterHide = await statistics(SPU)
ck(
  '隐藏后商品侧与评价侧仍然一致',
  Number(statsAfterHide.total) === (await detail(SPU)).reviewCount,
  `评价侧 ${statsAfterHide.total}，商品侧 ${(await detail(SPU)).reviewCount}`,
)
ck(
  '隐藏一条差评后均分回升',
  Number(statsAfterHide.average) > Number(statsNow.average),
  `${statsNow.average} → ${statsAfterHide.average}`,
)

const publicList = await listReviews(SPU, 'size=50')
ck('隐藏的评价不出现在公开列表里', !publicList.records.some((r) => r.id === reviewA.data.id))

const mineAfterHide = (await myReviews('size=50')).records.find((m) => m.id === reviewA.data.id)
ck('但作者在我的评价里看得到它', !!mineAfterHide)
ck('并且带着状态与隐藏原因，而不是无声消失', mineAfterHide?.status === 'HIDDEN', mineAfterHide?.status)
ck(
  '隐藏原因如实透出',
  (mineAfterHide?.hiddenReason ?? '').includes('违规'),
  mineAfterHide?.hiddenReason,
)

const restored = await call(`/reviews/admin/reviews/${reviewA.data.id}/status`, {
  method: 'PUT',
  token: A,
  body: { status: 'PUBLISHED' },
})
ck('恢复成功', restored.code === 200)
const afterRestore = await waitFor(async () => {
  const d = await detail(SPU)
  return d.reviewCount === expectedTotal ? d : 0
})
ck('恢复后评论数与均分回到隐藏前', afterRestore?.reviewCount === expectedTotal)
ck(
  '恢复时清空了隐藏原因（不然「按原因排查」会查出已经撤销的操作）',
  sql(`select coalesce(hidden_reason, '∅') from envoymart_review.review where id = ${reviewA.data.id}`) === '∅',
)

// ════ 八、有用：一票一人 ════
console.log('\n== 八、「有用」一票一人 ==')

const target = reviewB.data.id
const usefulBefore = (await listReviews(SPU, 'size=50')).records.find((r) => r.id === target).usefulCount
const vote1 = await call(`/reviews/${target}/useful`, { method: 'POST', token: T })
ck('第一次标记有用成功', vote1.code === 200, `code=${vote1.code}`)
const vote2 = await call(`/reviews/${target}/useful`, { method: 'POST', token: T })
ck('同一个人再点一次被拒（409，而不是静默成功让界面加上去）', vote2.code === 409, `code=${vote2.code}`)
const voteAdmin = await call(`/reviews/${target}/useful`, { method: 'POST', token: A })
ck('换一个人投票可以', voteAdmin.code === 200, `code=${voteAdmin.code}`)

const usefulNow = await waitFor(async () => {
  const r = (await listReviews(SPU, 'size=50')).records.find((x) => x.id === target)
  return r.usefulCount === usefulBefore + 2 ? r.usefulCount : 0
})
ck('计数正好 +2：重复的那次真的没有加', usefulNow === usefulBefore + 2, `实得 ${usefulNow}`)

const votes = sql(`select count(*) from envoymart_review.review_useful where review_id = ${target}`)
ck('库里记了两票而不是三票', votes === '2', `实得 ${votes}`)

// ════ 九、前端 ════
console.log('\n== 九、前端：商品卡评分、订单详情的「已评价」 ==')

const browser = await chromium.launch({ executablePath: CHROMIUM })
const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
await context.addInitScript(
  ([key, value]) => localStorage.setItem(key, value),
  ['user', JSON.stringify({ token: T, profile: tester.user })],
)
const page = await context.newPage()
const pageErrors = []
page.on('pageerror', (e) => pageErrors.push(String(e)))

await page.goto(`${BASE}/#/products/${SPU}`, { waitUntil: 'networkidle' })
const metaText = await page.locator('.detail__meta').innerText()
ck(
  `详情页展示「${expectedTotal} 条评价」`,
  metaText.includes(`${expectedTotal} 条评价`),
  metaText.replace(/\s+/g, ' '),
)
ck(
  '详情页的分数与接口一致（同一份冗余，不是自己算的）',
  metaText.includes(Number(statsAfterHide.average).toFixed(1)) ||
    metaText.includes(Number(afterRestore.ratingAvg).toFixed(1)),
  metaText.replace(/\s+/g, ' '),
)

await page.goto(`${BASE}/#/shop?keyword=${encodeURIComponent(keyword)}`, { waitUntil: 'networkidle' })
const card = page.locator('.product-card', { hasText: String(itemA.spuName).slice(0, 6) }).first()
// 商品卡的评分行来自接口返回的冗余列，`networkidle` 之后才由 Vue 挂上去；
// `count()` 不自动等待（`innerText()` 会），抢在渲染前问一次会得到 0 —— 那测的是网速，不是功能
const cardRating = card.locator('.product-card__rating')
const ratingShown = await cardRating
  .waitFor({ state: 'attached', timeout: 10000 })
  .then(() => true)
  .catch(() => false)
ck('商品卡上有评分行', ratingShown)
const cardText = (await cardRating.innerText().catch(() => '')).replace(/\s+/g, ' ')
ck(
  '卡片上的分数与条数与接口一致',
  cardText.includes(Number(afterRestore.ratingAvg).toFixed(1)) && cardText.includes(`${expectedTotal} 条评价`),
  cardText,
)

await page.goto(`${BASE}/#/orders/${order.id}`, { waitUntil: 'networkidle' })
const goods = page.locator('.goods')
// 同样的等待问题：`.goods` 由订单接口渲染，「已评价」要等评价接口回来才出现。
// 页面本身是一次请求两段渲染，快照必须等到第二段
const reviewedShown = await page
  .locator('.goods__reviewed')
  .first()
  .waitFor({ state: 'attached', timeout: 10000 })
  .then(() => true)
  .catch(() => false)
const goodsText = (await goods.innerText()).replace(/\s+/g, ' ')
ck(
  '已评过的订单行显示「已评价」而不是一个点进去必然报错的按钮',
  reviewedShown && (goodsText.match(/已评价/g) ?? []).length === 3,
  goodsText,
)
ck('不再有可点的「评价」按钮', (await page.locator('.goods__actions button:has-text("评价")').count()) === 0)

await browser.close()
ck('前端控制台没有报错', pageErrors.length === 0, pageErrors.join(' | '))

// ════ 汇总 ════
console.log(`\n${'='.repeat(52)}`)
console.log(`通过 ${pass}，失败 ${fail}`)
console.log(`本条链路的数据影响：订单 ${order.orderNo}、商品 ${SPU} 现有 ${expectedTotal} 条评价、销量 +${SKUS.length}`)
console.log('='.repeat(52))
process.exit(fail === 0 ? 0 : 1)
