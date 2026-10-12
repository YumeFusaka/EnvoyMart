import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import { readFile, writeFile } from 'node:fs/promises'
import { spawnSync } from 'node:child_process'

// 独立key与索引，仅验证外部版本闸；不读取或改写任何业务数据。
const runId = randomUUID(), prefix = 'verify:product-projection:' + runId
const index = 'envoymart_projection_verify_' + runId.replaceAll('-', '')
const source = await readFile(new URL('../../backend/product-service/src/main/java/yumefusaka/envoymart/productservice/projection/ProductProjectionCacheScripts.java', import.meta.url), 'utf8')
function script(name) {
  const statement = source.match(new RegExp('String ' + name + ' = ([\\s\\S]*?);\\s*\\n'))?.[1]
  if (!statement) throw new Error('未找到生产Redis脚本常量：' + name)
  const literals = [...statement.matchAll(/"((?:\\.|[^"\\])*)"/g)].map(match => JSON.parse('"' + match[1] + '"')).join('')
  return name === 'COMPARE' ? literals : script('COMPARE') + literals
}
function redis(...arguments_) {
  const result = spawnSync('docker', ['exec', 'envoymart-redis', 'redis-cli', '--raw', ...arguments_.map(String)], { encoding: 'utf8', windowsHide: true, timeout: 15000 })
  if (result.status !== 0 || /^ERR|^WRONGTYPE/.test(result.stdout)) throw new Error('独立Redis版本实验失败')
  return result.stdout.trim()
}
const keys = [prefix + ':value', prefix + ':version']
const checks = [], report = { runId, startedAt: new Date().toISOString(), scope: '独立Redis key与ES索引，非Spring Data发包验证', checks }
try {
  const invalidate = script('INVALIDATE'), fill = script('FILL')
  const old = '9007199254740992', latest = '9007199254740993'
  assert.equal(redis('EVAL', fill, 2, ...keys, old, '旧快照', 60000), '1')
  assert.equal(redis('EVAL', fill, 2, ...keys, latest, '新快照', 60000), '1')
  assert.equal(redis('EVAL', fill, 2, ...keys, old, '迟到旧快照', 60000), '0')
  assert.equal(redis('GET', keys[0]), '新快照')
  assert.equal(redis('GET', keys[1]), latest)
  checks.push({ name: '超过2^53相邻bigint新回填推进版本、迟到旧回填被拒', status: 'PASS' })
  assert.equal(redis('EVAL', invalidate, 2, ...keys, old), '0')
  assert.equal(redis('GET', keys[0]), '新快照')
  assert.equal(redis('EVAL', invalidate, 2, ...keys, '10000000000000000'), '1')
  assert.equal(redis('GET', keys[0]), '')
  assert.equal(redis('EVAL', fill, 2, ...keys, latest, '失效前迟到回填', 60000), '0')
  assert.equal(redis('PTTL', keys[1]), '-1')
  checks.push({ name: '跨位数版本失效与永久版本闸拒旧填', status: 'PASS' })

  const es = async (path, method = 'GET', body) => fetch('http://127.0.0.1:9200/' + index + path, {
    method, headers: { 'Content-Type': 'application/json' }, ...(body ? { body: JSON.stringify(body) } : {}), signal: AbortSignal.timeout(10000)
  })
  assert.equal((await es('', 'PUT', {})).ok, true)
  const publish = (version, body) => es('/_doc/20?version_type=external_gte&version=' + version, 'PUT', body)
  assert.equal((await publish(100, { id: 20, status: 1, totalStock: 495 })).ok, true)
  assert.equal((await publish(102, { id: 20, status: 1, totalStock: 494 })).ok, true)
  assert.equal((await publish(101, { id: 20, status: 1, totalStock: 495 })).status, 409)
  const current = await (await es('/_doc/20')).json()
  assert.equal(current._version, 102); assert.equal(current._source.totalStock, 494)
  checks.push({ name: 'ES external_gte拒绝迟到旧版本', status: 'PASS' })
  assert.equal((await publish(103, { id: 20, status: -1, totalStock: 0 })).ok, true)
  assert.equal((await publish(102, { id: 20, status: 1, totalStock: 494 })).status, 409)
  const tombstone = await (await es('/_doc/20')).json()
  assert.equal(tombstone._version, 103); assert.equal(tombstone._source.status, -1)
  checks.push({ name: 'ES永久墓碑阻止旧请求复活商品', status: 'PASS' })
  report.status = 'PASS'
} catch (error) {
  report.status = 'FAIL'; report.errorType = error.name
} finally {
  report.cleanupFailures=[]
  try { redis('DEL', ...keys) } catch { report.cleanupFailures.push('独立Redis key清理失败') }
  try {
    const response = await fetch('http://127.0.0.1:9200/' + index, { method: 'DELETE', signal: AbortSignal.timeout(10000) })
    if (!response.ok && response.status !== 404) report.cleanupFailures.push('独立ES实验索引清理失败')
  } catch { report.cleanupFailures.push('独立ES实验索引清理失败') }
  if(report.cleanupFailures.length)report.status='FAIL'
  report.finishedAt = new Date().toISOString(); report.cleanup = report.cleanupFailures.length?'清理未完成，保留独立路径待核验':'独立key与索引已清理'
  const path = new URL('../.evidence/product-projection-versions-' + runId + '.json', import.meta.url)
  await writeFile(path, JSON.stringify(report, null, 2) + '\n')
  console.log(JSON.stringify({ runId, status: report.status, checks: checks.length, evidence: path.pathname }))
  if(report.status!=='PASS')process.exitCode=1
}
