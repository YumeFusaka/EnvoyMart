/** 核对本轮已有证据确认的验收类目；授权模式仅停用，不删除商品或历史数据。 */
import { randomUUID } from 'node:crypto'
import { mkdir, writeFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { resolve } from 'node:path'
import { assert, rows, redact } from './lib/public-api-evidence.mjs'

const args = process.argv.slice(2)
assert(args.every((arg) => ['--audit', '--apply-authorized', '--help'].includes(arg)), '参数不在审阅范围')
if (args.includes('--help')) { console.log('--audit 只读盘点；--apply-authorized 仅在用户确认后停用九个已核实测试类目。默认只读验证公开导航无残留。'); process.exit(0) }
const targets = new Map([[34, 't1api_aada54b9改'], [36, 't1api_ddf4165c改'], [38, 't1ui_b6cddd57'],
  [39, 't1ui_68049275'], [40, 't1ui_b332c658'], [41, 't1ui_3661950e'], [42, 't1ui_4201e26e'],
  [43, 't1ui_f5c86a0b'], [44, 't1ui_19687343']])
const GW = process.env.VERIFY_GW ?? 'http://127.0.0.1:8080'
assert(['localhost', '127.0.0.1'].includes(new URL(GW).hostname), '验收仅允许本地网关')
const ROOT = fileURLToPath(new URL('../', import.meta.url))
const runId = randomUUID(), calls = [], changes = []
let token
async function call(method, path, body, admin = false) {
  const requestId = randomUUID()
  const response = await fetch(GW + path, { method, signal: AbortSignal.timeout(30000),
    headers: { 'Content-Type': 'application/json', 'X-Request-Id': requestId, ...(admin ? { Authorization: 'Bearer ' + token } : {}) },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }) })
  const value = await response.json()
  calls.push({ method, path, requestId, httpStatus: response.status, code: value.code })
  assert(response.ok && value.code === 200, '接口失败：' + method + ' ' + path)
  return value.data
}
const flatten = (items) => items.flatMap((item) => [item, ...flatten(item.children ?? [])])
const directory = resolve(ROOT, '.evidence')
await mkdir(directory, { recursive: true })
const output = resolve(directory, 'fixture-isolation-' + runId + '.json')
const report = { version: 1, runId, mode: args.includes('--apply-authorized') ? 'AUTHORIZED_DISABLE' : 'READ_ONLY', categories: [], changes, calls }
try {
  const identity = await call('POST', '/auth/login', { username: process.env.VERIFY_ADMIN_USERNAME ?? 'admin', password: process.env.VERIFY_ADMIN_PASSWORD ?? '123456' })
  assert(identity.user?.roleName === 'ADMIN', '管理员身份不符')
  token = identity.token
  const before = flatten(await call('GET', '/categories/admin/tree', undefined, true))
  for (const [id, name] of targets) {
    const category = before.find((item) => item.id === id)
    assert(category?.name === name && category.parentId === 0 && !(category.children?.length), '类别身份或关联结构变化，停止：' + id)
    const products = await call('GET', '/products/admin/spus?categoryId=' + id + '&page=0&size=100', undefined, true)
    assert(products.total === rows(products).length && rows(products).every((item) => item.categoryId === id), '关联商品未完整核对：' + id)
    assert(rows(products).every((item) => item.status === 0), '测试类目仍有在售商品，停止：' + id)
    report.categories.push({ id, name, before: { parentId: category.parentId, name: category.name, sort: category.sort, status: category.status },
      products: rows(products).map(({ id, name, status }) => ({ id, name, status })) })
  }
  // 先保存完整回滚输入，任一身份或关联前提失败时不进入批量写阶段。
  await writeFile(output, JSON.stringify(redact(report), null, 2) + '\n')
  if (args.includes('--apply-authorized')) {
    for (const item of report.categories) {
      if (item.before.status === 0) continue
      await call('PUT', '/categories/admin/' + item.id, { ...item.before, status: 0 }, true)
      changes.push({ id: item.id, beforeStatus: item.before.status, afterStatus: 0 })
      await writeFile(output, JSON.stringify(redact(report), null, 2) + '\n')
    }
  }
  const publicTree = flatten(await call('GET', '/categories/tree'))
  const after = flatten(await call('GET', '/categories/admin/tree', undefined, true))
  report.visibleIds = publicTree.filter((item) => targets.has(item.id)).map((item) => item.id)
  report.retained = report.categories.every((item) => after.some((category) => category.id === item.id && category.name === item.name))
  if (!args.includes('--audit')) assert(report.visibleIds.length === 0 && report.retained, '公开导航仍有测试类目或历史类别被删除')
  report.status = args.includes('--audit') ? 'AUDITED' : 'PASS'
} catch (error) { report.status = 'FAIL'; report.error = error.message; process.exitCode = 1 }
finally {
  report.finishedAt = new Date().toISOString()
  await writeFile(output, JSON.stringify(redact(report), null, 2) + '\n')
  console.log(JSON.stringify({ runId, status: report.status, visibleIds: report.visibleIds, changed: changes.length, retained: report.retained, path: output, error: report.error }))
}
