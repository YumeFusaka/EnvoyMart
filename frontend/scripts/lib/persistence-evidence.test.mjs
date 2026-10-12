import { test } from 'node:test'
import assert from 'node:assert/strict'
import { digest, validReport, messagesEvidence, restoredMessages, restarted, readOnlyRpc, rpcResult, seedMessage } from './persistence-evidence.mjs'

test('持久化哈希忽略对象键序，但保留数组顺序和内容变化', () => {
  assert.equal(digest({ b: 2, a: 1 }), digest({ a: 1, b: 2 }))
  assert.notEqual(digest([1, 2]), digest([2, 1]))
  assert.notEqual(digest({ answer: '正确' }), digest({ answer: '变化' }))
})

test('不存在、损坏、未完成和分母不一致的快照不能作为基线', () => {
  const value = { status: 'COMPLETED', runId: 'run', cases: Array(36).fill({}), metrics: { caseCount: 36 }, totalCases: 36, completedCases: 36, failedCases: 0 }
  assert.equal(validReport(value, 'grounding'), true)
  for (const change of [{ status: 'NEVER_RUN' }, { status: 'SNAPSHOT_CORRUPTED' }, { runId: null }, { completedCases: 35 }, { failedCases: 1 }, { metrics: { caseCount: 35 } }]) {
    assert.equal(validReport({ ...value, ...change }, 'grounding'), false)
  }
})

test('历史按稳定消息编号比较，允许追加，拒绝丢失、重复、改写、顺序变化', () => {
  const before = messagesEvidence([{ id: 'u', role: 'user', content: '问题' }, { id: 'a', role: 'assistant', content: '答案', response: { knowledge: [{ quote: '依据' }] } }])
  assert.equal(restoredMessages(before, [...before, { id: 'new' }]), true)
  assert.equal(restoredMessages(before, before.slice(1)), false)
  assert.equal(restoredMessages(before, [...before, before[0]]), false)
  assert.equal(restoredMessages(before, [before[1], before[0]]), false)
  assert.equal(restoredMessages(before, [{ ...before[0], digest: '变化' }, before[1]]), false)
})

test('相同进程及缺少进程证据不能证明重启，PID复用时比较启动时间', () => {
  const before = { pid: 1, startedAt: '旧时间' }
  assert.equal(restarted(before, before), false)
  assert.equal(restarted(before, null), false)
  assert.equal(restarted(before, { pid: 1, startedAt: '新时间' }), true)
  assert.equal(restarted(before, { pid: 2, startedAt: '新时间' }), true)
})

test('MCP只允许握手和列工具，禁止业务执行，JSON/SSE均可解析', () => {
  assert.equal(readOnlyRpc('initialize'), true)
  assert.equal(readOnlyRpc('notifications/initialized'), true)
  assert.equal(readOnlyRpc('tools/list'), true)
  assert.equal(readOnlyRpc('tools/call'), false)
  assert.equal(readOnlyRpc('resources/read'), false)
  const value = { jsonrpc: '2.0', result: { tools: [{ name: '查询' }] } }
  assert.deepEqual(rpcResult(JSON.stringify(value)), value)
  assert.deepEqual(rpcResult('event: message\r\ndata: ' + JSON.stringify(value) + '\r\n\r\n'), value)
  assert.equal(rpcResult(''), null)
  assert.throws(() => rpcResult('非JSON'))
})

test('seed必须指定独立T1会话中的稳定助手消息，并有对应用户问题', () => {
  const messages = [{ id: 'u', role: 'user' }, { id: 'a', role: 'assistant', response: { reply: '答案' } }]
  assert.equal(seedMessage('t1-persistence', 'a', messages), messages[1])
  assert.equal(seedMessage('真实用户会话', 'a', messages), null)
  assert.equal(seedMessage('t1-persistence', 'u', messages), null)
  assert.equal(seedMessage('t1-persistence', '不存在', messages), null)
  assert.equal(seedMessage('t1-persistence', 'a', messages.slice(1)), null)
  assert.equal(seedMessage('t1-persistence', 'a', [{ id: 'u', role: 'user' }, { id: 'a', role: 'assistant' }]), null)
})
