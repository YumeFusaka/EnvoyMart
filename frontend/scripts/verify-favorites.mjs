/**
 * 商品收藏（批次 13a）的端到端验收。
 *
 * 为什么单开一个脚本：收藏夹看上去只是「一张表加四个接口」，但它的正确性全在边界上——
 * 重复收藏会不会插两行、两个人会不会看到彼此的收藏、商品下架后收藏是不是跟着消失、
 * 一次核对 200 个商品时会不会静默少查。这些都不是单测能替的：单测里没有网关注入的身份头，
 * 也没有「同一秒内十个并发请求」这种东西。
 *
 * 数据影响：
 *   - 会清空 alice 的收藏夹（测试账号的私有数据，脚本开头清一次以保证可重复执行）
 *   - 会把一个在售商品短暂置为下架再改回来（夹具注入，结尾还原）
 *   - 商品的 status 改动会让详情缓存里的那份短暂过期，恢复后下一次 evict 自愈
 *
 * 断言分工：业务事实走公开 API 复读（列表、核对），库内事实走直连 SQL
 * （唯一约束真的生效、并发下真的只有一行）——后者没有也不该有公开接口。
 *
 * 前置条件：后端九个服务已启动（网关 8080 可达），商品种子数据在位。
 *
 * 用法：
 *   node scripts/verify-favorites.mjs
 */
import { execSync } from 'node:child_process'

const GW = process.env.VERIFY_GW ?? 'http://127.0.0.1:8080'
const MYSQL_BIN = process.env.VERIFY_MYSQL ?? 'E:/Tool/mysql-8.0.31-winx64/bin/mysql.exe'
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

function sql(q) {
  return execSync(
    `"${MYSQL_BIN}" -h127.0.0.1 -P3306 -uyumefusaka -pj -N --default-character-set=utf8mb4 -e "${q}"`,
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
  return { status: res.status, body: await res.json().catch(() => null) }
}

async function login({ username, password }) {
  const r = await call('/auth/login', { method: 'POST', body: { username, password } })
  return r.body?.data?.token
}

const T = await login(ALICE)
const A = await login(ADMIN)
if (!T || !A) {
  console.error('FATAL: 登录失败——先启动后端')
  process.exit(1)
}

/**
 * 库里的 user_id **不是用户名**：网关往 X-User-Id 头里注入的是 JWT 里的用户主键。
 * 直接按 'alice' 查库会一条都查不到，而那种失败看起来像「收藏根本没写进去」——
 * 所以这里从第一次写入的结果里把真实值读出来，不去猜。
 */
let aliceId = null

/** 清空 alice 的收藏，让这一轮从确定的起点开始 */
async function clearFavorites(token) {
  let guard = 0
  for (;;) {
    const r = await call('/favorites?page=0&size=50', { token })
    const records = r.body?.data?.records ?? []
    if (!records.length) break
    for (const item of records) {
      await call(`/favorites/${item.spuId}`, { method: 'DELETE', token })
    }
    if (++guard > 10) throw new Error('收藏清空失败：翻页没有收敛')
  }
}

console.log('\n=== 0. 前置：三个在售商品 ===')
await clearFavorites(T)
const catalog = await call('/products?size=3&sort=newest')
const products = catalog.body?.data?.records ?? []
ck('商品接口取到 3 个在售商品', products.length === 3, `实得 ${products.length}`)
if (products.length < 3) process.exit(1)
const [p1, p2, p3] = products

console.log('\n=== 1. 未登录一律拦在网关 ===')
const anonList = await call('/favorites')
ck('未登录 GET /favorites → 401', anonList.status === 401, `实得 ${anonList.status}`)
const anonAdd = await call(`/favorites/${p1.id}`, { method: 'POST' })
ck('未登录 POST /favorites/{id} → 401', anonAdd.status === 401, `实得 ${anonAdd.status}`)
const anonCheck = await call(`/favorites/check?spuIds=${p1.id}`)
ck('未登录 GET /favorites/check → 401', anonCheck.status === 401, `实得 ${anonCheck.status}`)

console.log('\n=== 2. 收藏：幂等 + 库内只有一行 ===')
const add1 = await call(`/favorites/${p1.id}`, { method: 'POST', token: T })
ck('首次收藏成功', add1.body?.code === 200, JSON.stringify(add1.body))
const add2 = await call(`/favorites/${p1.id}`, { method: 'POST', token: T })
ck('重复收藏仍返回成功（幂等）', add2.body?.code === 200, JSON.stringify(add2.body))
aliceId = sql(`select distinct user_id from envoymart_product.user_favorite where spu_id=${p1.id}`)
ck('库里留下了收藏行', Boolean(aliceId), `user_id 读出来是「${aliceId}」`)
const rowsAfterDup = sql(
  `select count(*) from envoymart_product.user_favorite where user_id='${aliceId}' and spu_id=${p1.id}`,
)
ck('重复收藏后库里只有一行', rowsAfterDup === '1', `实得 ${rowsAfterDup}`)

console.log('\n=== 3. 并发收藏同一商品：唯一约束兜住 ===')
const concurrent = await Promise.all(
  Array.from({ length: 10 }, () => call(`/favorites/${p2.id}`, { method: 'POST', token: T })),
)
const concurrentOk = concurrent.filter((r) => r.body?.code === 200).length
ck('10 个并发收藏全部成功', concurrentOk === 10, `成功 ${concurrentOk}/10`)
const rowsConcurrent = sql(
  `select count(*) from envoymart_product.user_favorite where user_id='${aliceId}' and spu_id=${p2.id}`,
)
ck('并发之下仍只有一行', rowsConcurrent === '1', `实得 ${rowsConcurrent}`)

console.log('\n=== 4. 收藏一个不存在的商品 ===')
const ghost = await call('/favorites/99999999', { method: 'POST', token: T })
ck('不存在的商品被拒绝', ghost.body?.code !== 200, JSON.stringify(ghost.body))
const ghostRows = sql(`select count(*) from envoymart_product.user_favorite where spu_id=99999999`)
ck('库里没留下脏行', ghostRows === '0', `实得 ${ghostRows}`)

console.log('\n=== 5. 我的收藏：内容与顺序 ===')
const list = await call('/favorites?page=0&size=20', { token: T })
const items = list.body?.data?.records ?? []
ck('列表返回两条', items.length === 2, `实得 ${items.length}`)
ck('总数正确', list.body?.data?.total === 2, `实得 ${list.body?.data?.total}`)
const first = items[0]
ck(
  '按收藏时间倒序（先收藏的在后）',
  first?.spuId === p2.id,
  `首条 spuId=${first?.spuId}，期望 ${p2.id}`,
)
ck('带收藏时间', Boolean(first?.favoritedAt), JSON.stringify(first?.favoritedAt))
ck('带商品卡片数据', first?.product?.name === p2.name, `${first?.product?.name} vs ${p2.name}`)
ck('在售商品 available=true', first?.available === true, `实得 ${first?.available}`)

console.log('\n=== 6. 批量核对只返回已收藏的子集 ===')
const check = await call(`/favorites/check?spuIds=${p1.id},${p2.id},${p3.id}`, { token: T })
const favorited = (check.body?.data ?? []).sort()
ck(
  '核对的子集恰好等于已收藏的两个',
  favorited.length === 2 && favorited.includes(p1.id) && favorited.includes(p2.id),
  JSON.stringify(check.body?.data),
)
const checkNone = await call(`/favorites/check?spuIds=${p3.id}`, { token: T })
ck(
  '未收藏的商品不出现在结果里',
  (checkNone.body?.data ?? []).length === 0,
  JSON.stringify(checkNone.body?.data),
)

console.log('\n=== 7. 收藏是私有的：换个用户看不到 ===')
const adminCheck = await call(`/favorites/check?spuIds=${p1.id},${p2.id}`, { token: A })
ck(
  'admin 核对同一批商品 → 空',
  (adminCheck.body?.data ?? []).length === 0,
  JSON.stringify(adminCheck.body?.data),
)
const adminList = await call('/favorites?page=0&size=20', { token: A })
ck('admin 的收藏夹里没有 alice 的收藏', (adminList.body?.data?.records ?? []).length === 0)

console.log('\n=== 8. 商品下架后：收藏还在，只是标为不可购买 ===')
// 走下架**接口**而不是直接改库：改库绕过了详情缓存的那次 evict，会把「缓存里的旧详情」
// 当成「下架没生效」——那是夹具的问题，不是被测行为的问题
await call(`/products/admin/spus/${p1.id}/status?status=0`, { method: 'PUT', token: A })
try {
  const afterOffline = await call('/favorites?page=0&size=20', { token: T })
  const offlineItems = afterOffline.body?.data?.records ?? []
  ck('下架商品仍留在收藏夹里', offlineItems.length === 2, `实得 ${offlineItems.length}`)
  const offlineItem = offlineItems.find((i) => i.spuId === p1.id)
  ck('available 变为 false', offlineItem?.available === false, `实得 ${offlineItem?.available}`)
  ck(
    '商品卡数据仍在（下架不等于数据消失）',
    Boolean(offlineItem?.product?.name),
    JSON.stringify(offlineItem?.product),
  )
  const offlineDetail = await call(`/products/${p1.id}`)
  ck(
    '而商品详情对下架商品返回「不存在」（下架确实生效了）',
    offlineDetail.body?.code !== 200,
    JSON.stringify(offlineDetail.body),
  )
} finally {
  await call(`/products/admin/spus/${p1.id}/status?status=1`, { method: 'PUT', token: A })
}
const restored = await call(`/favorites?page=0&size=20`, { token: T })
ck(
  '恢复上架后 available 回到 true',
  restored.body?.data?.records?.find((i) => i.spuId === p1.id)?.available === true,
)

console.log('\n=== 9. 分页：页码 0 基，翻页不重不漏 ===')
const page1 = await call('/favorites?page=1&size=1', { token: T })
ck(
  '第 2 页返回 1 条',
  (page1.body?.data?.records ?? []).length === 1,
  JSON.stringify(page1.body?.data),
)
ck('page 回显为 1', page1.body?.data?.page === 1, `实得 ${page1.body?.data?.page}`)
const pagedSpu = page1.body?.data?.records?.[0]?.spuId
const allSpus = (await call('/favorites?page=0&size=20', { token: T })).body?.data?.records?.map(
  (i) => i.spuId,
)
ck('第 2 页的那条与第 1 页不重复', !allSpus.slice(0, 1).includes(pagedSpu), `第2页=${pagedSpu}`)

console.log('\n=== 10. 核对接口不许静默少查 ===')
const tooMany = Array.from({ length: 201 }, (_, i) => i + 1).join(',')
const overflow = await call(`/favorites/check?spuIds=${tooMany}`, { token: T })
ck('超过上限报错而不是返回部分结果', overflow.body?.code !== 200, JSON.stringify(overflow.body))

console.log('\n=== 11. 取消收藏：幂等 + 真的删了 ===')
const del1 = await call(`/favorites/${p1.id}`, { method: 'DELETE', token: T })
ck('取消收藏成功', del1.body?.code === 200, JSON.stringify(del1.body))
const del2 = await call(`/favorites/${p1.id}`, { method: 'DELETE', token: T })
ck('重复取消仍返回成功（幂等）', del2.body?.code === 200, JSON.stringify(del2.body))
const afterDel = await call('/favorites?page=0&size=20', { token: T })
ck(
  '列表里只剩一条',
  (afterDel.body?.data?.records ?? []).length === 1,
  JSON.stringify(afterDel.body?.data?.total),
)
const delRows = sql(
  `select count(*) from envoymart_product.user_favorite where user_id='${aliceId}' and spu_id=${p1.id}`,
)
ck('库里真的删掉了', delRows === '0', `实得 ${delRows}`)

console.log('\n=== 12. 收尾：清空这一轮的数据 ===')
await clearFavorites(T)
const finalRows = sql(
  `select count(*) from envoymart_product.user_favorite where user_id='${aliceId}'`,
)
ck('alice 的收藏夹回到空', finalRows === '0', `实得 ${finalRows}`)

console.log(`\n===== 收藏验收：${pass} 通过 / ${fail} 失败 =====`)
process.exit(fail ? 1 : 0)
