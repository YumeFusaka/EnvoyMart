/**
 * checkout 回执的 MySQL/Seata 专项验收。
 *
 * 本脚本默认只读设计；不会读取 .env、不会自行启动/停止服务、不会执行 SQL。
 * --capture 需要主代理显式提供已审阅的锁适配器模块和独立测试账号状态。
 * 锁适配器必须只在 promotion-service 数据库对指定 user_coupon 行执行
 * SELECT ... FOR UPDATE，并返回 release() 与等待证据；取证适配器由主代理审阅，
 * 不能自行指向外部下载内容。脚本不接受任意 shell 命令。
 */
import { readFile, writeFile, mkdir } from 'node:fs/promises'
import { randomUUID } from 'node:crypto'
import { resolve, isAbsolute } from 'node:path'
import { fileURLToPath } from 'node:url'
import { assessNaturalRollback } from './lib/write-receipt-seata.mjs'
import { prepareSeataFixture } from './lib/setup-write-receipt-seata.mjs'

const args = process.argv.slice(2)
const compare=args.find((arg)=>arg.startsWith('--compare='))?.slice(10)
if(compare){if(args.length!==1)throw new Error('compare仅接受单一只读证据路径');const {compareSeataEvidence}=await import('./lib/seata-evidence-compare.mjs');const verdict=await compareSeataEvidence(compare);console.log(JSON.stringify(verdict));process.exit(verdict.status==='PASS'?0:1)}
if(!args.length){console.log(JSON.stringify({status:'BLOCKED',reason:'需要显式capture或--compare=四项Seata证据索引，默认不触发写操作'}));process.exit(2)}
if(args.some((arg)=>!['--help','--dry-run','--capture','--prepare','--prepare-unused'].includes(arg)&&!arg.startsWith('--fixture=')))throw new Error('只允许固定本地适配器与既定参数')
if(args.includes('--prepare-unused')){const result=await prepareSeataFixture({mode:'phase1'});console.log(JSON.stringify(result));process.exit(0)}
if(args.includes('--prepare')){const result=await prepareSeataFixture();console.log(JSON.stringify(result));process.exit(0)}
if (args.includes('--help')) {
  console.log('node scripts/verify-write-receipt-seata.mjs --dry-run\nnode scripts/verify-write-receipt-seata.mjs --prepare\nnode scripts/verify-write-receipt-seata.mjs --prepare-unused\nnode scripts/verify-write-receipt-seata.mjs --capture --fixture=<绝对路径private-fixture.json>\nUSED前提用于首单保护；prepare-unused生成迟到核销独立前提。脚本不读.env、不启动服务、不杀进程；阶段一窗口使用verify-write-receipt-phase1.mjs，迟到核销使用verify-write-receipt-seata-late-coupon.mjs。')
  process.exit(0)
}
if (args.includes('--dry-run')) {
  console.log(JSON.stringify({ status: 'DESIGN_ONLY', phases: [
    '读取capture前快照（订单、购物车、券status/orderNo、SKU库存、回执查询NOT_FOUND）',
    'lock adapter 对指定 user_coupon SELECT FOR UPDATE，证据必须包含连接/事务/行/持锁时间',
    '原op checkout 带 X-Operation-Id；确认 promotion UPDATE 被锁等待后，读取 data_lock_waits 证据',
    '释放锁；自然业务失败或超时后读取订单/购物车/库存/券/回执；比较Seata/XID/branch日志',
    '另行记录窄窗口：无事务内注入时 R7=BLOCKED，禁止用请求前故障或普通重启替代',
  ], mutation: false, servicesRestarted: false }, null, 2))
  process.exit(0)
}
const fixturePath = args.find((arg) => arg.startsWith('--fixture='))?.slice(10)
if (!args.includes('--capture') || !fixturePath) throw new Error('capture必须提供--fixture')
if (!isAbsolute(fixturePath)) throw new Error('fixture必须是绝对路径')
if (process.env.SEATA_VERIFY_ENABLE !== '1') throw new Error('真实capture需主代理显式设置 SEATA_VERIFY_ENABLE=1')

const gateway = process.env.VERIFY_GW ?? 'http://127.0.0.1:8080'
const fixture = JSON.parse(await readFile(resolve(fixturePath), 'utf8'))
const adapter = await import('./lib/write-receipt-seata-local.mjs')
for (const method of ['lockUserCoupon', 'observeWaiting', 'snapshotDatabase', 'queryReceipt', 'collectSeataEvidence']) {
  if (typeof adapter[method] !== 'function') throw new Error('已审阅适配器缺少取证方法：' + method)
}
const runId = randomUUID()
const evidenceDir = fileURLToPath(new URL('../.evidence/', import.meta.url))
const output = resolve(evidenceDir, `seata-receipt-${runId}.json`)
const evidence = { version: 'seata-receipt-v1', runId, mode: 'CAPTURE', status: 'BLOCKED', blocked: [], phases: [], startedAt: new Date().toISOString() }
let activeLock
let checkoutPromise
const redact = (value) => {
  if (Array.isArray(value)) return value.map(redact)
  if (value && typeof value === 'object') return Object.fromEntries(Object.entries(value).map(([key, item]) => [key, /token|password|authorization|phone|detail/i.test(key) ? '[已脱敏]' : redact(item)]))
  return typeof value === 'string' ? value.replace(/(?<!\d)1\d{10}(?!\d)/g, '[手机号已脱敏]') : value
}
const call = async (path, options = {}) => {
  const response = await fetch(gateway + path, { method: options.method ?? 'GET', headers: {
    'Content-Type': 'application/json', Authorization: `Bearer ${fixture.token}`,
    ...(options.operationId ? { 'X-Operation-Id': options.operationId } : {}),
    'X-Request-Id': options.requestId ?? randomUUID(),
  }, ...(options.body === undefined ? {} : { body: JSON.stringify(options.body) }), signal: AbortSignal.timeout(options.timeoutMs ?? 10000) })
  return { status: response.status, headers: Object.fromEntries(response.headers), body: await response.json().catch(() => null) }
}
const snapshot = async () => {
  const [cart, orders, coupons] = await Promise.all([call('/cart'), call('/orders?tab=ALL&page=0&size=100'), call('/coupons/mine')])
  return { cart: cart.body?.data, orders: orders.body?.data?.records, coupons: coupons.body?.data }
}
try {
  if (!fixture.token || !fixture.userId || !fixture.userCouponId || !fixture.operationId || !fixture.checkoutBody || !fixture.parametersHash) throw new Error('fixture缺token/userId/userCouponId/operationId/checkoutBody/parametersHash')
  const before = await snapshot(); evidence.before = redact(before)
  const coupon = (before.coupons ?? []).find((item) => String(item.id) === String(fixture.userCouponId))
  if (!coupon) throw new Error('fixture券不属于当前独立用户')
  if (coupon.status !== 'USED' || !coupon.orderNo) throw new Error('自然失败实验前提必须是已有订单核销过的USED券')
  evidence.phases.push({ name: 'precondition', pass: true, couponStatus: coupon.status, orderNoPresent: true })

  evidence.databaseBefore = await adapter.snapshotDatabase({ fixture })
  if (evidence.databaseBefore.engine !== 'MySQL' || evidence.databaseBefore.seataEnabled !== true) {
    throw Object.assign(new Error('未取得真实MySQL与当前实例Seata运行证据，禁止按本地事务通过'), { blocked: true })
  }
  const lock = await adapter.lockUserCoupon({ userId: fixture.userId, userCouponId: fixture.userCouponId })
  activeLock = lock
  if (!lock || typeof lock.release !== 'function' || !lock.evidence) throw new Error('锁适配器未返回release/evidence')
  evidence.lock = redact(lock.evidence)
  evidence.phases.push({ name: 'row-lock', pass: true })

  const requestId = randomUUID(); evidence.requestId = requestId
  checkoutPromise = call('/orders/checkout', { method: 'POST', operationId: fixture.operationId, body: fixture.checkoutBody, requestId, timeoutMs: 12000 })
    .then((result) => ({ result }), (error) => ({ transport: { name: error.name, message: '请求超时或连接中断，需查XID日志' } }))
  evidence.waiting = await adapter.observeWaiting({ userCouponId: fixture.userCouponId, requestId, maxWaitMs: 3500 })
  if (evidence.waiting?.waiting !== true || evidence.waiting?.table !== 'user_coupon' || !evidence.waiting?.blockingTransactionId) {
    evidence.blocked.push('未观察到实际核销UPDATE的行锁等待；USED谓词可能提前排除，不能把持锁当事务内暂停')
  }
  evidence.during = await adapter.snapshotDatabase({ fixture })
  evidence.duringReceipt = await adapter.queryReceipt({ fixture, maxWaitMs: 800 })
  if (evidence.duringReceipt?.code === 200) throw new Error('事务未完成时回执查询提前声明成功')
  await lock.release(); activeLock = null
  evidence.phases.push({ name: 'row-unlock', pass: true })
  const checkout = await checkoutPromise
  evidence.checkout = checkout.result ? { status: checkout.result.status, code: checkout.result.body?.code } : checkout.transport
  const after = await snapshot(); evidence.after = redact(after)
  const sameCoupon = (after.coupons ?? []).find((item) => String(item.id) === String(fixture.userCouponId))
  evidence.databaseAfter = await adapter.snapshotDatabase({ fixture })
  evidence.afterReceipt = await adapter.queryReceipt({ fixture, maxWaitMs: 10000 })
  evidence.seata = await adapter.collectSeataEvidence({ requestId, operationId: fixture.operationId })
  evidence.naturalFailure = { checkoutCode: checkout.result?.body?.code ?? null, couponStatusBefore: coupon.status, couponStatusAfter: sameCoupon?.status, orderNoBefore: coupon.orderNo, orderNoAfter: sameCoupon?.orderNo }
  if (sameCoupon?.status !== 'USED' || sameCoupon?.orderNo !== coupon.orderNo) throw new Error('失败路径错误退还此前订单使用的优惠券；保留FAIL，不能声称Seata通过')
  if (evidence.afterReceipt?.code !== 404 || evidence.databaseAfter.receiptCount !== 0
    || evidence.databaseAfter.operationOrderCount !== 0 || evidence.databaseAfter.stock !== evidence.databaseBefore.stock
    || JSON.stringify(after.cart) !== JSON.stringify(before.cart)) throw new Error('全局失败后业务、库存、购物车或回执没有复原')
  evidence.naturalRollback = assessNaturalRollback({beforeCoupon:coupon,afterCoupon:sameCoupon,beforeDatabase:evidence.databaseBefore,
    afterDatabase:evidence.databaseAfter,waiting:evidence.waiting,receipt:evidence.afterReceipt,seata:evidence.seata,checkoutCode:checkout.result?.body?.code})
  if (evidence.naturalRollback.status === 'FAIL') throw new Error(evidence.naturalRollback.reason)
  if (evidence.naturalRollback.status === 'BLOCKED') evidence.blocked.push(evidence.naturalRollback.reason)
  evidence.blocked.push('该实验验证自然领域失败和回执无成功结果；未证明全局事务已phase1提交后由TC回滚')
  evidence.status = evidence.blocked.length ? 'BLOCKED' : 'PASS'
} catch (error) {
  evidence.status = error.blocked ? 'BLOCKED' : 'FAIL'
  evidence.error = error.message
} finally {
  if (activeLock) {
    try { await activeLock.release(); evidence.lockReleasedOnFailure = true }
    catch (error) { evidence.status = 'FAIL'; evidence.lockReleaseError = error.message }
  }
  if (checkoutPromise) await checkoutPromise
  evidence.finishedAt = new Date().toISOString()
  if(evidence.status!=='PASS')process.exitCode=1
  await mkdir(evidenceDir, { recursive: true })
  await writeFile(output, JSON.stringify(redact(evidence), null, 2) + '\n', 'utf8')
  console.log(JSON.stringify({ runId, status: evidence.status, path: output, blocked: evidence.blocked }, null, 2))
}
