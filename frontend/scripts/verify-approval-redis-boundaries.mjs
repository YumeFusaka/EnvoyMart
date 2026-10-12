/** 真实Redis执行生产Lua；仅自有账号随机临时键，不调用业务API、模型或控制服务。 */
import assert from 'node:assert/strict'
import { readFile, mkdir, writeFile } from 'node:fs/promises'
import { spawnSync } from 'node:child_process'
import { createHash, randomUUID } from 'node:crypto'
import { fileURLToPath } from 'node:url'
import { resolve } from 'node:path'

export const approvalRedisChecks = [
  '有效owner正向确实执行真实Lua', '首次接受正向确实执行真实Lua', 'Java式预检时租约尚有效',
  '预检之后到期的旧owner不可续租', '到期CAS没有改变原记录', 'Java式预检时墓碑不存在',
  '预检之后删除不允许ownerCAS写入', '新owner已接管时旧ownerCAS拒绝', '首次接受预检尚在原签名期限内',
  '首次接受Lua执行时过期不能获得授权', '首次过期仍保持未接受记录', '首次接受同时校验会话墓碑',
  '取消或变更的原记录不能发布新卡', '发布同时校验会话墓碑', '原记录匹配时发布正向确实执行', '重复发布单次位阻止第二张卡',
]
export const validApprovalUserId = (value) => /^(?:[a-f0-9]{32}|\d{1,19})$/i.test(value ?? '')

/** 门禁仅回读真实完整报告；dry-run、部分失败和其他源码版本均不能兑付。 */
export function validateApprovalRedisEvidence(proof, expected) {
  assert.ok(proof?.version === 'approval-redis-boundaries-v1' && proof.status === 'PASS'
    && proof.mode === 'PRODUCTION_LUA_ONLY', '缺少真实Redis完整PASS报告，帮助或dry-run不能作为证据')
  assert.ok(proof.sourceDigest === expected.sourceDigest, '报告生产源码摘要与当前版本不一致')
  for (const name of ['begin', 'owned', 'publish']) {
    assert.ok(proof.scriptDigests?.[name] === expected.scriptDigests[name], '报告生产Lua摘要与当前版本不一致')
  }
  assert.ok(proof.businessCalls === 0 && proof.modelCalls === 0 && proof.serviceControls === 0, '边界探针必须没有业务、模型或服务控制调用')
  assert.ok(Array.isArray(proof.checks) && proof.checks.length === 16
    && new Set(proof.checks.map((item) => item.name)).size === 16
    && approvalRedisChecks.every((name) => proof.checks.some((item) => item.name === name && item.pass === true)), '缺少完整16项唯一边界断言')
  assert.ok(Array.isArray(proof.commands) && proof.commands.length > 0
    && proof.commands.every((item) => item.category === 'OK' && item.exitCode === 0 && !item.errorCode && !item.redisErrorCode), '真实Redis调用存在失败或没有命令观测')
  for (const digest of Object.values(expected.scriptDigests)) {
    assert.ok(proof.commands.some((item) => item.verb === 'EVAL' && item.evalScriptDigest === digest), '报告缺少某条生产Lua的真实执行观测')
  }
  const detail = (name) => proof.checks.find((item) => item.name === name)?.detail
  const leaseBefore = detail('Java式预检时租约尚有效'), leaseAfter = detail('预检之后到期的旧owner不可续租')
  const acceptBefore = detail('首次接受预检尚在原签名期限内'), acceptAfter = detail('首次接受Lua执行时过期不能获得授权')
  assert.ok(leaseBefore?.precheckMode === 'ATOMIC_REDIS_SEED' && acceptBefore?.precheckMode === 'ATOMIC_REDIS_SEED'
    && leaseBefore.redisTime < leaseBefore.leaseUntil && leaseAfter?.redisTime > leaseBefore.leaseUntil
    && leaseAfter.leaseUntil === leaseBefore.leaseUntil && acceptBefore.redisTime < acceptBefore.signatureUntil
    && acceptAfter?.redisTime > acceptBefore.signatureUntil && acceptAfter.signatureUntil === acceptBefore.signatureUntil,
  '时间边界缺少同一真实Redis时钟的预检与越界证据')
  return { status: 'PASS', checks: 16, sourceDigest: expected.sourceDigest }
}

async function main() {

const args = process.argv.slice(2)
if (args.some((item) => ['--help', '--dry-run'].includes(item)) && args.length !== 1) {
  console.log(JSON.stringify({ status: 'BLOCKED', reason: '帮助或dry-run必须独立使用，不能混入真实执行或证据模式' }))
  process.exit(2)
}
const sourcePath = fileURLToPath(new URL('../../backend/ai-service/src/main/java/yumefusaka/envoymart/aiservice/memory/ChatHistoryStore.java', import.meta.url))
const source = await readFile(sourcePath, 'utf8')
const hash = (value) => createHash('sha256').update(String(value)).digest('hex')
function productionScript(signature) {
  const offset = source.indexOf(signature)
  assert.ok(offset >= 0, '生产方法不存在，不能使用替代Lua')
  const match = /new org\.springframework\.data\.redis\.core\.script\.DefaultRedisScript<Long>\(\s*((?:"(?:\\.|[^"\\])*"\s*\+?\s*)+), Long\.class\)/.exec(source.slice(offset))
  assert.ok(match, '生产Lua格式变化，需核对后更新提取器')
  return [...match[1].matchAll(/"(?:\\.|[^"\\])*"/g)].map((item) => JSON.parse(item[0])).join('')
}
const scripts = {
  begin: productionScript('public ApprovalLease beginApproval('),
  owned: productionScript('private boolean compareAndSetOwnedApproval('),
  publish: productionScript('public boolean publishNextApproval('),
}
assert.ok(scripts.begin.includes("redis.call('TIME')") && scripts.begin.includes("KEYS[3]"), '首次接受必须同时检查TIME和墓碑')
assert.ok(scripts.owned.includes("redis.call('TIME')") && scripts.owned.includes("KEYS[2]"), 'ownerCAS必须同时检查TIME和墓碑')
assert.ok(scripts.publish.includes('nextPublished') && scripts.publish.includes("KEYS[2]"), '发布必须同时检查墓碑与单次位')
if (args.length === 0) {
  console.log(JSON.stringify({ status: 'BLOCKED', reason: '缺少真实Redis边界报告，不能把帮助或dry-run作为门禁成功' }))
  process.exit(2)
}
if (args.includes('--help')) {
  console.log('node scripts/verify-approval-redis-boundaries.mjs --dry-run\nnode scripts/verify-approval-redis-boundaries.mjs --run --user-id=<自有隔离账号ID，32位hex或兼容数字>\nnode scripts/verify-approval-redis-boundaries.mjs --evidence=<真实报告JSON>\n仅执行生产Lua边界探针；临时记录不是合法签名、不调用业务或授权API。所有键180秒到期，真实运行仅由主代理统一执行。')
  process.exit(0)
}
if (args.includes('--dry-run')) {
  console.log(JSON.stringify({ mode: 'DRY_RUN', sourceDigest: hash(source), scripts: Object.fromEntries(Object.entries(scripts).map(([name, script]) => [name, hash(script)])),
    cases: ['owner有效正向', '首次接受有效正向', 'Java预检后租约到期', 'Java预检后删除', 'owner变更CAS拒绝', '首次接受延迟过签名截止', '首次接受前删除', '取消旧记录不能发布', '删除不能发布', '发布正向及重复零新增'], businessCalls: 0, modelCalls: 0, redisExecuted: false }))
  process.exit(0)
}
const evidence = args.find((item) => item.startsWith('--evidence='))?.slice('--evidence='.length)
if (evidence) {
  if (args.length !== 1) throw new Error('证据回读不能混入run、help或dry-run模式')
  try {
    let proof
    try { proof = JSON.parse(await readFile(resolve(evidence), 'utf8')) } catch { throw new Error('真实Redis边界报告无法读取或解析') }
    const result = validateApprovalRedisEvidence(proof, { sourceDigest: hash(source),
      scriptDigests: Object.fromEntries(Object.entries(scripts).map(([name, script]) => [name, hash(script)])) })
    console.log(JSON.stringify({ ...result, runId: proof.runId, evidence: resolve(evidence), replayOnly: true }))
    console.log('16 通过 / 0 失败')
  } catch (error) {
    console.log(JSON.stringify({ status: 'BLOCKED', reason: error.message, replayOnly: true }))
    process.exitCode = 2
  }
  return
}
const userId = args.find((item) => item.startsWith('--user-id='))?.slice('--user-id='.length)
if (!args.includes('--run') || !validApprovalUserId(userId) || args.some((item) => item !== '--run' && !item.startsWith('--user-id='))) {
  throw new Error('真实探针必须显式提供自有隔离账号32位hex或兼容数字ID，仅允许--run与--user-id')
}
const runId = randomUUID()
const ttl = 180000
const report = { version: 'approval-redis-boundaries-v1', runId, startedAt: new Date().toISOString(), mode: 'PRODUCTION_LUA_ONLY',
  actorFingerprint: hash(userId), sourceDigest: hash(source), scriptDigests: Object.fromEntries(Object.entries(scripts).map(([name, script]) => [name, hash(script)])),
  checks: [], keys: [], commands: [], businessCalls: 0, modelCalls: 0, serviceControls: 0 }
function redis(...command) {
  const started = Date.now()
  const result = spawnSync('docker', ['exec', 'envoymart-redis', 'redis-cli', '--raw', ...command.map(String)], { encoding: 'utf8', timeout: 15000, windowsHide: true })
  const redisError = /^(NOAUTH|ERR|WRONGTYPE|BUSY|OOM|LOADING|MISCONF|READONLY|NOPERM)\b/m.exec(result.stdout ?? '')?.[1] ?? null
  const category = result.error?.code === 'ETIMEDOUT' ? 'TOOL_TIMEOUT'
    : result.error ? 'TOOL_SPAWN_FAILURE' : result.status !== 0 ? 'TOOL_EXIT_FAILURE' : redisError ? 'REDIS_ERROR' : 'OK'
  const observation = { verb: command[0], evalScriptDigest: command[0] === 'EVAL' ? hash(command[1]) : null,
    durationMs: Date.now() - started, exitCode: result.status, errorCode: result.error?.code ?? null, redisErrorCode: redisError, category,
    at: new Date().toISOString() }
  report.commands.push(observation)
  if (category !== 'OK') {
    throw new Error(`真实Redis调用失败：${category} verb=${observation.verb} durationMs=${observation.durationMs} exitCode=${observation.exitCode} errorCode=${observation.errorCode}`)
  }
  return result.stdout.trim()
}
function time() {
  const values = redis('TIME').split(/\r?\n/).map(Number)
  assert.ok(values.length === 2 && values.every(Number.isSafeInteger), 'Redis TIME必须返回真实秒与微秒')
  return values[0] * 1000 + Math.floor(values[1] / 1000)
}
const check = (name, condition, detail = {}) => {
  report.checks.push({ name, pass: !!condition, at: new Date().toISOString(), detail })
  assert.ok(condition, name)
}
const seedScript = `
for i=1,#KEYS do if redis.call('EXISTS',KEYS[i])==1 then return redis.error_reply('PROBE_COLLISION') end end
local t=redis.call('TIME'); local now=t[1]*1000+math.floor(t[2]/1000)
local row=cjson.decode(ARGV[1]); local accepted=row.status~='PENDING'
row.acceptedAt=accepted and now or 0
row.recoveryUntil=accepted and now+86400000 or 0
row.leaseUntil=row.status=='IN_FLIGHT' and now+tonumber(ARGV[3]) or 0
local raw=cjson.encode(row); local head=cjson.encode(row.original)
local signatureUntil=now+tonumber(ARGV[4])
local leaseValid=row.status=='IN_FLIGHT' and now<row.leaseUntil
local notDeleted=redis.call('EXISTS',KEYS[2])==0
redis.call('HSET',KEYS[1],'record',raw); redis.call('PEXPIRE',KEYS[1],ARGV[2])
if row.status=='PENDING' then redis.call('LPUSH',KEYS[3],head); redis.call('PEXPIRE',KEYS[3],ARGV[2]) end
return cjson.encode({raw=raw,head=head,now=now,signatureUntil=signatureUntil,leaseValid=leaseValid,notDeleted=notDeleted})
`
report.seedScriptDigest = hash(seedScript)
function fixture(status = 'IN_FLIGHT', leaseMs = 120000, signatureMs = 120000) {
  const session = `approval-lua-${runId}-${report.keys.length}`
  const token = `临时探针非合法签名-${session}`
  const keys = { ledger: `chat:approval:${userId}:${session}:${hash(token)}`, deleted: `chat:deleted:${userId}:${session}`,
    history: `chat:hist:${userId}:${session}`, meta: `chat:meta:${userId}:${session}`,
    // 不碰真实用户会话索引，避免缩短已有索引TTL或把伪审批放入UI。
    index: `chat:lua-probe:${userId}:${session}:index` }
  const original = { id: 'a-probe', role: 'assistant', content: '仅Lua隔离探针', at: new Date().toISOString(),
    response: { approvalToken: token, pendingActions: ['lua_probe'], approvalStatus: 'PENDING' } }
  const template = { token, original, status, acceptedAt: 0, ownerNonce: status === 'IN_FLIGHT' ? 'original-owner' : null,
    leaseUntil: 0, result: null, recoveryUntil: 0 }
  // seed与预检使用同一次Redis TIME；Docker往返慢不能让预检变成固定等待后的猜测。
  const seeded = JSON.parse(redis('EVAL', seedScript, 5, keys.ledger, keys.deleted, keys.history, keys.meta, keys.index,
    JSON.stringify(template), ttl, leaseMs, signatureMs))
  const record = JSON.parse(seeded.raw)
  report.keys.push({ sessionFingerprint: hash(session), keyDigests: Object.values(keys).map(hash), temporaryTtlMs: ttl })
  return { keys, record, raw: seeded.raw, now: seeded.now, original, headRaw: seeded.head,
    signatureUntil: seeded.signatureUntil, leasePrecheck: seeded.leaseValid, deletedPrecheck: seeded.notDeleted }
}
function evaluate(script, keys, values) { return Number(redis('EVAL', script, keys.length, ...keys, ...values)) }
function owned(item) {
  const next = JSON.stringify({ ...item.record, leaseUntil: item.record.leaseUntil + 30000 })
  return evaluate(scripts.owned, [item.keys.ledger, item.keys.deleted], [item.raw, next, ttl, item.record.leaseUntil])
}
async function after(deadline) {
  const end = Date.now() + 10000
  while (time() <= deadline && Date.now() < end) await new Promise((done) => setTimeout(done, 80))
  assert.ok(time() > deadline, '边界必须由真实Redis时钟越过，不伪造服务时钟')
  return time()
}
function markDeleted(item) { assert.equal(redis('SET', item.keys.deleted, '1', 'PX', ttl), 'OK') }
function begin(item, deadline) {
  const acquired = JSON.stringify({ ...item.record, status: 'IN_FLIGHT', acceptedAt: item.now, ownerNonce: 'new-owner', leaseUntil: item.now + 30000, recoveryUntil: item.now + 86400000 })
  return evaluate(scripts.begin, [item.keys.ledger, item.keys.history, item.keys.deleted], [item.raw, item.headRaw, acquired, ttl, deadline])
}
function head(item) {
  assert.equal(item.record.status, 'PENDING', '原子seed已准备未接受消息，不重复推入历史')
}
function publish(item) {
  const user = JSON.stringify({ id: 'u-next', role: 'user', content: '仅Lua探针', at: new Date(item.now).toISOString() })
  const assistant = JSON.stringify({ id: 'a-next', role: 'assistant', content: '不允许业务执行', at: new Date(item.now).toISOString(), response: { approvalToken: '非合法签名下一卡', approvalStatus: 'PENDING', pendingActions: ['lua_probe'] } })
  return evaluate(scripts.publish, [item.keys.ledger, item.keys.deleted, item.keys.history, item.keys.meta, item.keys.index],
    [item.raw, user, assistant, 'Lua隔离探针', new Date(item.now).toISOString(), item.now, '仅临时索引', ttl])
}
try {
  const valid = fixture()
  check('有效owner正向确实执行真实Lua', owned(valid) === 1)

  const validPending = fixture('PENDING')
  head(validPending)
  check('首次接受正向确实执行真实Lua', begin(validPending, validPending.signatureUntil) === 1
    && JSON.parse(redis('HGET', validPending.keys.ledger, 'record')).acceptedAt > 0)

  const expired = fixture('IN_FLIGHT', 2500)
  check('Java式预检时租约尚有效', expired.leasePrecheck && expired.now < expired.record.leaseUntil,
    { redisTime: expired.now, leaseUntil: expired.record.leaseUntil, precheckMode: 'ATOMIC_REDIS_SEED' })
  const afterLease = await after(expired.record.leaseUntil)
  check('预检之后到期的旧owner不可续租', owned(expired) === 0, { redisTime: afterLease, leaseUntil: expired.record.leaseUntil })
  check('到期CAS没有改变原记录', redis('HGET', expired.keys.ledger, 'record') === expired.raw)

  const deleted = fixture()
  check('Java式预检时墓碑不存在', deleted.deletedPrecheck, { precheckMode: 'ATOMIC_REDIS_SEED' })
  markDeleted(deleted)
  check('预检之后删除不允许ownerCAS写入', owned(deleted) === 0)

  const changed = fixture()
  const changedRaw = JSON.stringify({ ...changed.record, ownerNonce: 'another-owner' })
  redis('HSET', changed.keys.ledger, 'record', changedRaw)
  check('新owner已接管时旧ownerCAS拒绝', owned(changed) === 0 && redis('HGET', changed.keys.ledger, 'record') === changedRaw)

  const pending = fixture('PENDING', 120000, 2500)
  head(pending)
  const firstDeadline = pending.signatureUntil
  check('首次接受预检尚在原签名期限内', pending.now < firstDeadline,
    { redisTime: pending.now, signatureUntil: firstDeadline, precheckMode: 'ATOMIC_REDIS_SEED' })
  const afterAccept = await after(firstDeadline)
  check('首次接受Lua执行时过期不能获得授权', begin(pending, firstDeadline) === 0, { redisTime: afterAccept, signatureUntil: firstDeadline })
  check('首次过期仍保持未接受记录', redis('HGET', pending.keys.ledger, 'record') === pending.raw)

  const pendingDeleted = fixture('PENDING')
  head(pendingDeleted)
  markDeleted(pendingDeleted)
  check('首次接受同时校验会话墓碑', begin(pendingDeleted, pendingDeleted.signatureUntil) === 0)

  const cancelled = fixture('CONFIRMED')
  redis('HSET', cancelled.keys.ledger, 'record', JSON.stringify({ ...cancelled.record, status: 'CANCELLED' }))
  check('取消或变更的原记录不能发布新卡', publish(cancelled) === 0 && redis('LLEN', cancelled.keys.history) === '0')

  const publishDeleted = fixture('CONFIRMED')
  markDeleted(publishDeleted)
  check('发布同时校验会话墓碑', publish(publishDeleted) === 0 && redis('LLEN', publishDeleted.keys.history) === '0')

  const publishValid = fixture('CONFIRMED')
  check('原记录匹配时发布正向确实执行', publish(publishValid) === 1 && redis('LLEN', publishValid.keys.history) === '2')
  check('重复发布单次位阻止第二张卡', publish(publishValid) === 0 && redis('LLEN', publishValid.keys.history) === '2')
  report.status = 'PASS'
} catch (error) {
  report.status = 'FAIL'
  report.error = error.message
} finally {
  report.finishedAt = new Date().toISOString()
  const directory = fileURLToPath(new URL('../.evidence/', import.meta.url))
  await mkdir(directory, { recursive: true })
  const path = `${directory}/approval-redis-boundaries-${runId}.json`
  await writeFile(path, JSON.stringify(report, null, 2) + '\n')
  console.log(JSON.stringify({ status: report.status, checks: report.checks.length, passed: report.checks.filter((item) => item.pass).length,
    businessCalls: 0, modelCalls: 0, sourceDigest: report.sourceDigest, report: path }))
  if (report.status !== 'PASS') process.exitCode = 1
}
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) await main()
