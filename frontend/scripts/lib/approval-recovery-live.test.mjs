import test from 'node:test'
import assert from 'node:assert/strict'
import { createServer } from 'node:http'
import { createLostResponseProxy, fingerprint, assertOriginalRecovery, comparisonReport } from './approval-recovery-live.mjs'
import { listen, close } from './complex-agent-controls.mjs'

test('订单代理先取得完整首次回执才丢响应且只阻断原用户原op查询', async () => {
  let writes = 0
  const target = createServer((request, response) => {
    if (request.method === 'POST') writes++
    response.writeHead(200, { 'content-type': 'application/json', 'x-operation-id': 'original-op', 'x-operation-parameters-hash': 'a'.repeat(64), 'x-operation-receipt-version': '1', 'x-operation-replayed': 'false' })
    response.end(JSON.stringify({ code: 200, data: { ticket: { id: 42 }, messages: [{ id: 1 }] } }))
  })
  const port = await listen(target)
  const proxy = createLostResponseProxy({ targetPort: port, mode: 'ORDER_COMMITTED_UNKNOWN', userId: '1', operationId: 'original-op', parametersHash: 'a'.repeat(64) })
  const entry = await listen(proxy)
  try {
    await assert.rejects(fetch(`http://127.0.0.1:${entry}/tickets`, { method: 'POST', headers: { 'X-User-Id': '1', 'X-Operation-Id': 'original-op' }, body: '{}' }))
    assert.equal(writes, 1); assert.equal(proxy.controlEvidence.dropped[0].fullResponseReceived, true)
    assert.equal(proxy.controlEvidence.dropped[0].forwarded, true)
    assert.equal((await fetch(`http://127.0.0.1:${entry}/tickets`, { method: 'POST', headers: { 'X-User-Id': '1', 'X-Operation-Id': 'original-op' }, body: '{}' })).status, 503)
    assert.equal(writes, 1)
    assert.equal((await fetch(`http://127.0.0.1:${entry}/orders/internal/operation-receipts/original-op`, { headers: { 'X-User-Id': '1' } })).status, 503)
    assert.equal((await fetch(`http://127.0.0.1:${entry}/orders/internal/operation-receipts/other-op`, { headers: { 'X-User-Id': '1' } })).status, 200)
    proxy.releaseQueries()
    assert.equal((await fetch(`http://127.0.0.1:${entry}/orders/internal/operation-receipts/original-op`, { headers: { 'X-User-Id': '1' } })).status, 200)
  } finally { await close(proxy); await close(target) }
})

test('客户端丢响应必须绑定认证及原审批指纹且不能伪装AI内部未知', async () => {
  const target = createServer((request, response) => {
    response.writeHead(200, { 'content-type': 'application/json' }); response.end(JSON.stringify({ code: 200, data: { approvalStatus: 'CONFIRMED', toolCalls: [{ operationId: 'op', operationState: 'COMMITTED' }] } }))
  })
  const port = await listen(target)
  const proxy = createLostResponseProxy({ targetPort: port, mode: 'CLIENT_COMMITTED_UNKNOWN', authFingerprint: fingerprint('Bearer actor'), tokenFingerprint: fingerprint('signed') })
  const entry = await listen(proxy)
  try {
    assert.equal((await fetch(`http://127.0.0.1:${entry}/ai/chat`, { method: 'POST', headers: { Authorization: 'Bearer actor' }, body: JSON.stringify({ approvalToken: 'different' }) })).status, 200)
    await assert.rejects(fetch(`http://127.0.0.1:${entry}/ai/chat`, { method: 'POST', headers: { Authorization: 'Bearer actor' }, body: JSON.stringify({ approvalToken: 'signed' }) }))
    assert.equal(proxy.controlEvidence.mode, 'CLIENT_COMMITTED_UNKNOWN'); assert.equal(proxy.controlEvidence.dropped.length, 1)
  } finally { await close(proxy); await close(target) }
})

test('恢复必须保留原消息与op且无模型用量，token变化或仅success不放行', () => {
  const state = { messageId: 'a', turnId: 't', approvalToken: 'signed', action: { operationId: 'op' } }
  const response = { assistantMessageId: 'a', turnId: 't', approvalToken: 'signed', approvalStatus: 'CONFIRMED', usage: null, toolCalls: [{ operationId: 'op', operationState: 'COMMITTED', receiptReplayed: true }] }
  assertOriginalRecovery(response, state)
  assert.throws(() => assertOriginalRecovery({ ...response, usage: { totalTokens: 1 } }, state))
  assert.throws(() => assertOriginalRecovery({ ...response, approvalToken: 'new' }, state))
  assert.throws(() => assertOriginalRecovery({ ...response, toolCalls: [{ operationId: 'op', success: true }] }, state))
})

test('旧客户端capture比较单独报告并保存原字节SHA，不改变源状态与原runId', () => {
  const original = { runId: 'original-run', status: 'CAPTURED_WAITING_COMPARE', actions: [{ mode: 'CLIENT_COMMITTED_UNKNOWN' }] }
  const copy = structuredClone(original)
  const bytes = JSON.stringify(original)
  const plan = comparisonReport('original-capture.json', bytes, original, '.evidence')
  assert.notEqual(plan.publicPath, plan.sourceCapture.path)
  assert.equal(plan.sourceCapture.sha256, fingerprint(bytes))
  assert.equal(plan.sourceCapture.runId, original.runId)
  assert.equal(plan.sourceCapture.status, 'CAPTURED_WAITING_COMPARE')
  assert.deepEqual(original, copy)
})

test('营销代理只丢自有用户原op原couponId完整首次回执，权威唯一持券核验后才生效', async () => {
  let writes = 0, proofs = 0
  const target = createServer((request, response) => {
    if (request.method === 'POST') writes++
    response.writeHead(200, { 'content-type': 'application/json', 'x-operation-id': 'coupon-op', 'x-operation-parameters-hash': 'a'.repeat(64), 'x-operation-receipt-version': '1', 'x-operation-replayed': 'false' })
    response.end(JSON.stringify({ code: 200, data: { id: 42, couponId: 7 } }))
  })
  const port = await listen(target)
  const proxy = createLostResponseProxy({ targetPort: port, mode: 'PROMOTION_COMMITTED_UNKNOWN', userId: 'own', operationId: 'coupon-op', couponId: 7,
    parametersHash: 'a'.repeat(64), verifyPromotionCommit: async () => { proofs++; return { userId: 'own', couponId: 7, userCouponId: 42, count: 1, requestId: 'authority' } } })
  const entry = await listen(proxy)
  const send = (user, op, id = 7) => fetch(`http://127.0.0.1:${entry}/coupons/${id}/receive`, { method: 'POST', headers: { 'X-User-Id': user, 'X-Operation-Id': op } })
  try {
    for (const input of [['other', 'coupon-op'], ['own', 'other-op'], ['own', 'coupon-op', 8]]) assert.equal((await send(...input)).status, 200)
    assert.equal(proofs, 0)
    await assert.rejects(send('own', 'coupon-op'))
    assert.equal(proofs, 1)
    assert.equal(proxy.controlEvidence.dropped.length, 1)
    assert.equal(proxy.controlEvidence.dropped[0].authority.userCouponId, 42)
    const originalWrites = writes
    assert.equal((await send('own', 'coupon-op')).status, 503)
    assert.equal(writes, originalWrites)
    const receipt = `http://127.0.0.1:${entry}/coupons/internal/operation-receipts/coupon-op?toolName=coupon_receive&parametersHash=${'a'.repeat(64)}`
    assert.equal((await fetch(receipt, { headers: { 'X-User-Id': 'own' } })).status, 503)
    assert.equal((await fetch(receipt, { headers: { 'X-User-Id': 'other' } })).status, 200)
    assert.equal((await fetch(receipt.replace('a'.repeat(64), 'b'.repeat(64)), { headers: { 'X-User-Id': 'own' } })).status, 200)
    proxy.releaseQueries()
    assert.equal((await fetch(receipt, { headers: { 'X-User-Id': 'own' } })).status, 200)
  } finally { await close(proxy); await close(target) }
})

test('营销不完整错误hash错误持券或不唯一证据不能丢响应也不能阻断重试', async () => {
  const variants = [
    { headers: { 'x-operation-parameters-hash': 'b'.repeat(64) } },
    { headers: { 'x-operation-receipt-version': undefined } },
    { headers: { 'x-operation-replayed': 'true' } },
    { data: { id: 42, couponId: 8 } },
    { data: { couponId: 7 } },
    { proof: { userId: 'other' } },
    { proof: { count: 2 } },
    { proof: { userCouponId: 43 } },
  ]
  for (const variant of variants) {
    let calls = 0
    const target = createServer((request, response) => {
      calls++
      const headers = { 'content-type': 'application/json', 'x-operation-id': 'coupon-op', 'x-operation-parameters-hash': 'a'.repeat(64),
        'x-operation-receipt-version': '1', 'x-operation-replayed': 'false', ...variant.headers }
      response.writeHead(200, Object.fromEntries(Object.entries(headers).filter(([, value]) => value !== undefined)))
      response.end(JSON.stringify({ code: 200, data: variant.data ?? { id: 42, couponId: 7 } }))
    })
    const port = await listen(target)
    const proxy = createLostResponseProxy({ targetPort: port, mode: 'PROMOTION_COMMITTED_UNKNOWN', userId: 'own', operationId: 'coupon-op', couponId: 7,
      parametersHash: 'a'.repeat(64), verifyPromotionCommit: async () => ({ userId: 'own', couponId: 7, userCouponId: 42, count: 1, requestId: 'authority', ...variant.proof }) })
    const entry = await listen(proxy)
    try {
      for (let index = 0; index < 2; index++) assert.equal((await fetch(`http://127.0.0.1:${entry}/coupons/7/receive`, {
        method: 'POST', headers: { 'X-User-Id': 'own', 'X-Operation-Id': 'coupon-op' },
      })).status, 200)
      assert.equal(proxy.controlEvidence.dropped.length, 0)
      assert.equal(proxy.controlEvidence.blockedWrites.length, 0)
      assert.equal(calls, 2)
    } finally { await close(proxy); await close(target) }
  }
})
