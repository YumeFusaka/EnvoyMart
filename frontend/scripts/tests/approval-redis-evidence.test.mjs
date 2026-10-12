import test from 'node:test'
import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { approvalRedisChecks, validateApprovalRedisEvidence, validApprovalUserId } from '../verify-approval-redis-boundaries.mjs'

const expected = { sourceDigest: 'a'.repeat(64), scriptDigests: { begin: 'b'.repeat(64), owned: 'c'.repeat(64), publish: 'd'.repeat(64) } }
function completeProof() {
  const proof = { version: 'approval-redis-boundaries-v1', status: 'PASS', mode: 'PRODUCTION_LUA_ONLY',
    sourceDigest: expected.sourceDigest, scriptDigests: { ...expected.scriptDigests }, businessCalls: 0, modelCalls: 0, serviceControls: 0,
    checks: approvalRedisChecks.map((name) => ({ name, pass: true, detail: {} })),
    commands: Object.values(expected.scriptDigests).map((evalScriptDigest) => ({ verb: 'EVAL', evalScriptDigest, category: 'OK', exitCode: 0, errorCode: null, redisErrorCode: null })) }
  const detail = (name, data) => { proof.checks.find((item) => item.name === name).detail = data }
  detail('Java式预检时租约尚有效', { precheckMode: 'ATOMIC_REDIS_SEED', redisTime: 1000, leaseUntil: 2000 })
  detail('预检之后到期的旧owner不可续租', { redisTime: 2001, leaseUntil: 2000 })
  detail('首次接受预检尚在原签名期限内', { precheckMode: 'ATOMIC_REDIS_SEED', redisTime: 3000, signatureUntil: 4000 })
  detail('首次接受Lua执行时过期不能获得授权', { redisTime: 4001, signatureUntil: 4000 })
  return proof
}

test('项目UUID无横线账号与旧数字兼容，拒绝越界键身份', () => {
  assert.ok(validApprovalUserId('92cacc6175474f93bb5893e98cf4f5f2'))
  assert.ok(validApprovalUserId('1001'))
  for (const value of ['', null, 'u:other', '92cacc61-7547-4f93-bb58-93e98cf4f5f2', 'x'.repeat(32)]) assert.equal(validApprovalUserId(value), false)
})
test('完整生产Lua报告可以按契约回读', () => {
  assert.equal(validateApprovalRedisEvidence(completeProof(), expected).checks, 16)
})
test('旧FAIL和帮助dry-run不能成为门禁绿灯', () => {
  for (const change of [{ status: 'FAIL' }, { mode: 'DRY_RUN' }, { version: undefined }, { modelCalls: 1 }]) {
    assert.throws(() => validateApprovalRedisEvidence({ ...completeProof(), ...change }, expected))
  }
})
test('生产源码或任一Lua摘要篡改必须拒绝', () => {
  assert.throws(() => validateApprovalRedisEvidence({ ...completeProof(), sourceDigest: 'e'.repeat(64) }, expected), /源码摘要/)
  for (const name of Object.keys(expected.scriptDigests)) {
    const proof = completeProof(); proof.scriptDigests[name] = 'e'.repeat(64)
    assert.throws(() => validateApprovalRedisEvidence(proof, expected), /Lua摘要/)
  }
})
test('缺失重复失败边界断言或缺真实EVAL观测不能被PASS掩盖', () => {
  for (const alter of [
    (proof) => proof.checks.pop(),
    (proof) => { proof.checks[1] = proof.checks[0] },
    (proof) => { proof.checks[5].pass = false },
    (proof) => proof.commands.pop(),
    (proof) => { proof.commands[0].category = 'TOOL_TIMEOUT' },
    (proof) => { proof.checks[3].detail.redisTime = 1999 },
  ]) {
    const proof = completeProof(); alter(proof)
    assert.throws(() => validateApprovalRedisEvidence(proof, expected))
  }
})
test('默认无证据退出BLOCKED且帮助不能与证据混用', () => {
  const entry = fileURLToPath(new URL('../verify-approval-redis-boundaries.mjs', import.meta.url))
  for (const args of [[], ['--evidence=missing.json', '--help'], ['--dry-run', '--run']]) {
    const result = spawnSync(process.execPath, [entry, ...args], { encoding: 'utf8', timeout: 30000, windowsHide: true })
    assert.equal(result.status, 2)
    assert.match(result.stdout, /BLOCKED/)
    assert.doesNotMatch(result.stdout, /16 通过/)
  }
})
test('统一总入口默认发现真实Lua证据门禁', () => {
  const entry = fileURLToPath(new URL('../verify-all.mjs', import.meta.url))
  const result = spawnSync(process.execPath, [entry, '--list', '--only=approval-redis'], { encoding: 'utf8', timeout: 30000, windowsHide: true })
  assert.equal(result.status, 0)
  assert.match(result.stdout, /verify-approval-redis-boundaries\.mjs/)
})
