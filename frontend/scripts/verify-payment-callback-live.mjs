/** 支付回调真实验收；临时密钥仅驻留进程内，退出前恢复原服务启动配置。 */
import { createHmac, randomBytes, randomUUID } from 'node:crypto'
import { spawnSync } from 'node:child_process'
import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { resolve, relative, isAbsolute } from 'node:path'
import { ApiEvidence, assert, pollAuthority } from './lib/public-api-evidence.mjs'
import { findGitBash } from './lib/complex-agent-controls.mjs'

const ROOT = fileURLToPath(new URL('../', import.meta.url))
const args = process.argv.slice(2)
const compare = args.find((arg) => arg.startsWith('--compare='))?.slice(10)
if (!args.every((arg) => arg === '--run-authorized' || arg === '--help' || arg.startsWith('--compare='))) throw new Error('不支持的验收参数')
if (args.includes('--help')) {
  console.log('默认不重启或写入。经用户授权后使用 --run-authorized；--compare=.evidence/payment-callback-<runId>.json 核对报告与本轮独立订单权威状态。临时回调密钥不落盘，finally恢复原启动配置。')
  process.exit(0)
}
const GW = process.env.VERIFY_GW ?? 'http://127.0.0.1:8080'
assert(['localhost', '127.0.0.1'].includes(new URL(GW).hostname), '仅允许本地网关')
const matrix = JSON.parse(await readFile(new URL('./fixtures/platform-feature-matrix.json', import.meta.url), 'utf8'))
const tokens = {}
const runId = randomUUID()
const evidence = new ApiEvidence(matrix.apiContracts, async (method, path, options) => {
  const response = await fetch(GW + path, {
    method, signal: AbortSignal.timeout(60000),
    headers: { 'Content-Type': 'application/json', 'X-Request-Id': options.requestId,
      ...(tokens[options.role] ? { Authorization: 'Bearer ' + tokens[options.role] } : {}), ...options.headers },
    ...(options.body === undefined ? {} : { body: JSON.stringify(options.body) }),
  })
  const body = await response.json()
  return { code: body.code, data: body.data, message: body.msg, httpStatus: response.status }
}, runId)
const call = (key, path, body, role = 'buyer', extra = {}) => evidence.call(key, path, { body, role, ...extra })
const login = async () => {
  const value = await call('POST /auth/login', '/auth/login', {
    username: process.env.VERIFY_ADMIN_USERNAME ?? 'admin', password: process.env.VERIFY_ADMIN_PASSWORD ?? '123456',
  }, 'anonymous')
  assert(value.data?.user?.roleName === 'ADMIN' && value.data.token, '管理员身份不符')
  tokens.admin = value.data.token
}
const authority = (id) => call('GET /orders/admin/orders/{id}', '/orders/admin/orders/' + id, undefined, 'admin')
if (compare) {
  const path = resolve(ROOT, compare)
  const local = relative(resolve(ROOT, '.evidence'), path)
  assert(local && !local.startsWith('..') && !isAbsolute(local), '证据必须位于忽略目录')
  const original = JSON.parse(await readFile(path, 'utf8'))
  assert(original.mode === 'LIVE_SIGNED_PAYMENT_CALLBACK' && original.restored === true && original.pass === true, '原报告未完整成功恢复')
  const endpoint = original.endpoints.find((item) => item.key === 'POST /payments/callback')
  assert(endpoint?.status === 'PASS' && endpoint.checks.every((item) => item.pass), '有效签名回调缺逐请求断言')
  await login()
  const current = await authority(original.orderId)
  assert(current.data.order.status === 'PAID' && current.data.statusLogs.some((entry) => entry.toStatus === 'CREATED'
    && entry.operatorType === 'USER' && entry.operatorId === original.userId), '独立订单权威状态或创建者变化')
  console.log('PASS POST /payments/callback 有效签名、重复幂等、无效签名拒绝与配置恢复；runId=' + original.runId)
  process.exit(0)
}
if (!args.includes('--run-authorized')) throw new Error('未提供明确验收阶段；不启动服务或使用原密钥，不能记真实PASS')

const BASH = process.env.VERIFY_GIT_BASH ?? findGitBash()
const backend = resolve(ROOT, '../backend')
const changes = []
let changed = false
let restored = false
let failure
let orderId
let userId
const service = (action, temporarySecret) => {
  const result = spawnSync(BASH, ['./run-local.sh', ...(action === 'stop' ? ['stop', 'payment-service'] : ['payment-service'])], {
    cwd: backend, encoding: 'utf8', timeout: 180000, maxBuffer: 4 * 1024 * 1024,
    env: { ...process.env, PRECOMPILED: '1', ...(temporarySecret ? { SPRING_APPLICATION_JSON: JSON.stringify({ envoymart: { payment: { 'callback-secret': temporarySecret } } }) } : {}) },
  })
  changes.push({ action, temporaryOverride: Boolean(temporarySecret), status: result.status, at: new Date().toISOString() })
  assert(result.status === 0, '支付服务' + action + '失败；检查本地服务日志，不输出启动环境')
}
async function ready() {
  const deadline = Date.now() + 180000
  while (Date.now() < deadline) {
    try {
      const response = await fetch('http://127.0.0.1:9005/actuator/health/readiness', { signal: AbortSignal.timeout(3000) })
      const body = await response.json()
      if (response.ok && body.status === 'UP') return
    } catch { /* 进程启动期间只等待，不重复重启。 */ }
    await new Promise((done) => setTimeout(done, 1000))
  }
  throw new Error('支付服务就绪超时')
}
try {
  const registered = await call('POST /auth/register', '/auth/register', {
    username: 't1pay_' + runId.slice(0, 8), password: randomBytes(16).toString('hex'), nickname: '支付回调隔离验收',
  }, 'anonymous')
  tokens.buyer = registered.data.token
  userId = registered.data.user.id
  await login()
  await call('POST /cart/items', '/cart/items', { skuId: 1, quantity: 1 })
  const checkout = await call('POST /orders/checkout', '/orders/checkout', {
    requestId: 't1pay-' + runId, receiverName: '隔离验收', receiverPhone: '13800000000',
    receiverProvince: '上海市', receiverCity: '上海市', receiverDistrict: '浦东新区', receiverDetail: '本地测试地址',
  })
  orderId = checkout.data.id
  const payment = await call('POST /payments', '/payments', { orderId, channel: 'MOCK' })
  assert(payment.data.status === 'PENDING', '前置支付单必须待支付')
  const secret = randomBytes(32).toString('hex')
  changed = true
  service('stop')
  service('start', secret)
  await ready()
  const request = { orderId, transactionNo: 'T1-' + runId, status: 'SUCCESS' }
  const signature = createHmac('sha256', secret).update(orderId + '|' + request.transactionNo + '|SUCCESS').digest('hex')
  const headers = { 'X-Pay-Signature': signature }
  const success = await call('POST /payments/callback', '/payments/callback', request, 'anonymous', { headers })
  const paid = await pollAuthority(() => authority(orderId), (value) => value.order.status === 'PAID')
  evidence.check('POST /payments/callback', '有效签名真实HTTP请求完成支付并投影订单PAID', success.data.status === 'SUCCESS' && success.data.transactionNo === request.transactionNo, [success, paid])
  const duplicate = await call('POST /payments/callback', '/payments/callback', request, 'anonymous', { headers })
  evidence.check('POST /payments/callback', '相同签名重复回调返回同一支付记录与首次金额', duplicate.data.id === success.data.id && duplicate.data.amount === success.data.amount && duplicate.data.transactionNo === success.data.transactionNo, [success, duplicate])
  const rejected = await call('POST /payments/callback', '/payments/callback', { ...request, status: 'FAILED' }, 'anonymous', { headers, allowedCodes: [400, 401, 403] })
  const unchanged = await call('GET /payments/{orderId}', '/payments/' + orderId)
  evidence.check('POST /payments/callback', '篡改已签状态被拒且权威支付仍SUCCESS', rejected.code !== 200 && unchanged.data.status === 'SUCCESS' && unchanged.data.transactionNo === request.transactionNo, [rejected, unchanged])
} catch (error) { failure = error.message }
finally {
  if (changed) {
    try { service('stop'); service('start'); await ready(); restored = true }
    catch (error) { failure = [failure, '恢复失败：' + error.message].filter(Boolean).join('；') }
  }
}
const report = { ...evidence.report(), mode: 'LIVE_SIGNED_PAYMENT_CALLBACK', orderId, userId,
  endpoints: evidence.report().endpoints.filter((item) => item.checks.length),
  restored, pass: !failure && restored, failure, changes, finishedAt: new Date().toISOString() }
await mkdir(resolve(ROOT, '.evidence'), { recursive: true })
const output = resolve(ROOT, '.evidence/payment-callback-' + runId + '.json')
await writeFile(output, JSON.stringify(report, null, 2) + '\n', 'utf8')
console.log((report.pass ? 'PASS' : 'FAIL') + ' 有效签名支付回调；配置恢复=' + restored + '；证据=' + output)
if (!report.pass) process.exitCode = 1
