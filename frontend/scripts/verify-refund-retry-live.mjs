/** 独立售后单在支付服务不可达时保留REFUNDING，恢复后真实重试，核对唯一退款。 */
import { randomBytes, randomUUID } from 'node:crypto'
import { spawnSync } from 'node:child_process'
import { readFile, mkdir, writeFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { resolve, relative, isAbsolute } from 'node:path'
import { ApiEvidence, assert, pollAuthority } from './lib/public-api-evidence.mjs'
import { findGitBash } from './lib/complex-agent-controls.mjs'

const ROOT = fileURLToPath(new URL('../', import.meta.url))
const args = process.argv.slice(2)
const compare = args.find((arg) => arg.startsWith('--compare='))?.slice(10)
if (!args.every((arg) => ['--capture', '--help'].includes(arg) || arg.startsWith('--compare='))) throw new Error('不支持的参数')
if (args.includes('--help')) {
  console.log('--capture 创建独立订单和售后，只停止/恢复payment-service以产生真实退款不可达；--compare=.evidence/refund-retry-<runId>.json 只读核对原终态与唯一退款。')
  process.exit(0)
}
if (args.includes('--capture') === Boolean(compare)) throw new Error('必须且只能指定capture或compare；无阶段不触发业务或重启')
const GW = process.env.VERIFY_GW ?? 'http://127.0.0.1:8080'
assert(['localhost', '127.0.0.1'].includes(new URL(GW).hostname), '仅允许本地网关')
const matrix = JSON.parse(await readFile(new URL('./fixtures/platform-feature-matrix.json', import.meta.url), 'utf8'))
const tokens = {}
const runId = randomUUID()
const evidence = new ApiEvidence(matrix.apiContracts, async (method, path, options) => {
  const response = await fetch(GW + path, { method, signal: AbortSignal.timeout(120000),
    headers: { 'Content-Type': 'application/json', 'X-Request-Id': options.requestId,
      ...(tokens[options.role] ? { Authorization: 'Bearer ' + tokens[options.role] } : {}) },
    ...(options.body === undefined ? {} : { body: JSON.stringify(options.body) }) })
  const body = await response.json()
  return { code: body.code, data: body.data, message: body.msg, httpStatus: response.status }
}, runId)
const call = (key, path, body, role = 'buyer', allowedCodes) => evidence.call(key, path, { body, role, allowedCodes })
async function loginAdmin() {
  const value = await call('POST /auth/login', '/auth/login', {
    username: process.env.VERIFY_ADMIN_USERNAME ?? 'admin', password: process.env.VERIFY_ADMIN_PASSWORD ?? '123456',
  }, 'anonymous')
  assert(value.data?.user?.roleName === 'ADMIN', '管理员身份不符')
  tokens.admin = value.data.token
}
const detail = (id) => call('GET /after-sales/admin/after-sales/{id}', '/after-sales/admin/after-sales/' + id, undefined, 'admin')
if (compare) {
  const path = resolve(ROOT, compare)
  const local = relative(resolve(ROOT, '.evidence'), path)
  assert(local && !local.startsWith('..') && !isAbsolute(local), '证据必须位于忽略目录')
  const original = JSON.parse(await readFile(path, 'utf8'))
  assert(original.mode === 'LIVE_REFUND_RETRY' && original.restored && original.pass, '原报告未完整成功恢复')
  await loginAdmin()
  const current = await detail(original.afterSaleId)
  assert(current.data.afterSale.status === 'FINISHED' && current.data.afterSale.orderId === original.orderId, '售后终态或原订单身份变化')
  assert(current.data.logs.filter((log) => log.remark === '人工重试退款').length === 1, '重试流水应仅一次')
  console.log('PASS POST /after-sales/admin/after-sales/{id}/retry-refund 真实失败后重试、唯一退款与终态拒重；runId=' + original.runId)
  process.exit(0)
}
const changes = []
let changed = false
let restored = false
let failure
let orderId
let afterSaleId
const service = (action) => {
  const result = spawnSync(process.env.VERIFY_GIT_BASH ?? findGitBash(),
    ['./run-local.sh', ...(action === 'stop' ? ['stop', 'payment-service'] : ['payment-service'])], {
      cwd: resolve(ROOT, '../backend'), encoding: 'utf8', timeout: 180000, maxBuffer: 4 * 1024 * 1024,
      env: { ...process.env, PRECOMPILED: '1' },
    })
  changes.push({ action, status: result.status, at: new Date().toISOString() })
  assert(result.status === 0, '支付服务' + action + '失败，检查本地日志')
}
async function ready() {
  const deadline = Date.now() + 180000
  while (Date.now() < deadline) {
    try {
      const response = await fetch('http://127.0.0.1:9005/actuator/health/readiness', { signal: AbortSignal.timeout(3000) })
      if (response.ok && (await response.json()).status === 'UP') return
    } catch { /* 启动窗口只等待就绪，不重复杀进程。 */ }
    await new Promise((done) => setTimeout(done, 1000))
  }
  throw new Error('支付服务就绪超时')
}
try {
  const registered = await call('POST /auth/register', '/auth/register', {
    username: 't1refund_' + runId.slice(0, 8), password: randomBytes(16).toString('hex'), nickname: '退款重试隔离验收',
  }, 'anonymous')
  tokens.buyer = registered.data.token
  await loginAdmin()
  await call('POST /cart/items', '/cart/items', { skuId: 1, quantity: 1 })
  const order = await call('POST /orders/checkout', '/orders/checkout', {
    requestId: 't1refund-' + runId, receiverName: '隔离验收', receiverPhone: '13800000000',
    receiverProvince: '上海市', receiverCity: '上海市', receiverDistrict: '浦东新区', receiverDetail: '本地测试地址',
  })
  orderId = order.data.id
  await call('POST /payments', '/payments', { orderId, channel: 'MOCK' })
  await call('POST /payments/{orderId}/mock-pay', '/payments/' + orderId + '/mock-pay')
  await pollAuthority(() => call('GET /orders/{id}', '/orders/' + orderId), (value) => value.status === 'PAID')
  await call('POST /orders/admin/orders/{id}/ship', '/orders/admin/orders/' + orderId + '/ship', {
    carrierCode: 'SF', carrierName: '顺丰速运', trackingNo: 't1refund-' + runId,
  }, 'admin')
  const received = await call('POST /orders/{id}/receive', '/orders/' + orderId + '/receive')
  const itemId = received.data.items[0].id
  const preview = await call('GET /after-sales/preview', '/after-sales/preview?orderItemId=' + itemId + '&type=REFUND_ONLY&qualityIssue=true')
  assert(preview.data.eligible === true && preview.data.maxRefundAmount > 0, '售后真实前提不成立')
  const applied = await call('POST /after-sales', '/after-sales', {
    orderItemId: itemId, type: 'REFUND_ONLY', reason: '质量问题', description: '独立退款失败重试验收', qualityIssue: true,
  })
  afterSaleId = applied.data.id
  const originalRefunds = await call('GET /payments/refunds/{orderId}', '/payments/refunds/' + orderId)
  assert(originalRefunds.data.length === 0, '前置不得已有退款')
  changed = true
  service('stop')
  const audited = await call('POST /after-sales/admin/after-sales/{id}/audit', '/after-sales/admin/after-sales/' + afterSaleId + '/audit?approved=true', undefined, 'admin')
  const failed = await detail(afterSaleId)
  assert(audited.data.status === 'REFUNDING' && failed.data.afterSale.status === 'REFUNDING', '真实下游不可达时必须保留退款中，不能假造FAILED行')
  service('start')
  await ready()
  restored = true
  const noRefund = await call('GET /payments/refunds/{orderId}', '/payments/refunds/' + orderId)
  assert(noRefund.data.length === 0, '不可达阶段不得产生退款')
  const retry = await call('POST /after-sales/admin/after-sales/{id}/retry-refund', '/after-sales/admin/after-sales/' + afterSaleId + '/retry-refund', undefined, 'admin')
  const finished = await detail(afterSaleId)
  const refunds = await call('GET /payments/refunds/{orderId}', '/payments/refunds/' + orderId)
  evidence.check('POST /after-sales/admin/after-sales/{id}/retry-refund', '真实下游不可达后保留REFUNDING，恢复后原售后重试FINISHED且退款一次',
    retry.data.status === 'FINISHED' && finished.data.afterSale.status === 'FINISHED' && refunds.data.length === 1
      && refunds.data[0].afterSaleId === afterSaleId && refunds.data[0].amount === applied.data.refundAmount,
    [audited, failed, noRefund, retry, finished, refunds])
  const duplicate = await call('POST /after-sales/admin/after-sales/{id}/retry-refund', '/after-sales/admin/after-sales/' + afterSaleId + '/retry-refund', undefined, 'admin', [400, 409])
  const unchanged = await call('GET /payments/refunds/{orderId}', '/payments/refunds/' + orderId)
  const log = await detail(afterSaleId)
  evidence.check('POST /after-sales/admin/after-sales/{id}/retry-refund', '终态重复重试被拒且金额与退款编号不变，仅一条人工重试流水',
    duplicate.code !== 200 && JSON.stringify(unchanged.data) === JSON.stringify(refunds.data)
      && log.data.logs.filter((item) => item.remark === '人工重试退款').length === 1,
    [duplicate, refunds, unchanged, log])
} catch (error) { failure = error.message }
finally {
  if (changed && !restored) {
    try { service('start'); await ready(); restored = true }
    catch (error) { failure = [failure, '恢复失败：' + error.message].filter(Boolean).join('；') }
  }
}
const report = { ...evidence.report(), mode: 'LIVE_REFUND_RETRY', orderId, afterSaleId,
  endpoints: evidence.report().endpoints.filter((item) => item.checks.length),
  restored, pass: !failure && restored, failure, changes, finishedAt: new Date().toISOString() }
await mkdir(resolve(ROOT, '.evidence'), { recursive: true })
const output = resolve(ROOT, '.evidence/refund-retry-' + runId + '.json')
await writeFile(output, JSON.stringify(report, null, 2) + '\n', 'utf8')
console.log((report.pass ? 'PASS' : 'FAIL') + ' 真实退款失败后重试；支付服务恢复=' + restored + '；证据=' + output)
if (!report.pass) process.exitCode = 1
