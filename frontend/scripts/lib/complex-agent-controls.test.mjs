import test from 'node:test'
import assert from 'node:assert/strict'
import { createServer, request } from 'node:http'
import { createControls, createFaultProxy, listen, close, collectOperationEvidence, collectRetrievalEvidence, knowledgeIndexReady, restoreAiBaseline } from './complex-agent-controls.mjs'

test('stop已停进程但命令失败时仍恢复原服务，原失败保留', async () => {
  const calls = []
  const snapshots = [{ pid: 101, startedAt: '开始' }, null]
  const controls = createControls({ enabled: true, snapshot: async () => snapshots.shift(), waitReady: async () => ({ ready: true }), runLocal: async (args) => {
    calls.push(args)
    if (args[0] === 'stop') throw new Error('停止命令失败')
  } })
  await assert.rejects(controls.restart({ services: ['ai-service'] }), /停止命令失败/)
  assert.deepEqual(calls, [['stop', 'ai-service'], ['ai-service']])
})

test('故障期间AI启动失败无监听时仍按9003基线恢复，并验证新进程', async () => {
  const calls = []
  const snapshots = [null, { pid: 303, startedAt: '恢复后' }]
  const restored = await restoreAiBaseline({ step: { caseId: 'CA-024', sessionId: 's' } }, {
    snapshot: async () => snapshots.shift(), waitReady: async () => ({ ready: true }),
    runLocal: async (args, overrides) => { calls.push({ args, overrides }) },
  })
  assert.equal(restored.ready, true)
  assert.equal(restored.afterPid, 303)
  assert.deepEqual(calls, [{ args: ['ai-service'], overrides: { ORDER_SERVICE_URL: 'http://127.0.0.1:9003' } }])
})

test('AI重启须等待本轮索引与图谱完成，健康端点正常不能替代知识就绪', () => {
  const done = { running: false, error: null, finishedAt: '2026-10-11T00:00:00Z', result: { documentCount: 53, chunkCount: 425, graphAvailable: true, graphFailed: 0 } }
  assert.equal(knowledgeIndexReady(done), true)
  for (const status of [undefined, { running: false }, { ...done, running: true }, { ...done, finishedAt: null }, { ...done, error: '重建失败' }, { ...done, result: { ...done.result, chunkCount: 0 } }, { ...done, result: { ...done.result, graphAvailable: false } }, { ...done, result: { ...done.result, graphFailed: 1 } }]) {
    assert.equal(knowledgeIndexReady(status), false)
  }
})

test('真实控制默认阻断，错误不会执行run-local', async () => {
  let invoked = false
  const controls = createControls({ runLocal: () => { invoked = true } })
  await assert.rejects(controls.restart({ services: ['ai-service'], caseId: 'CA-017', sessionId: 's' }), (error) => error.blocked && /默认关闭/.test(error.message))
  assert.equal(invoked, false)
})

test('重启只使用固定服务命令并核对前后PID与就绪', async () => {
  const calls = []; let snapshots = [{ pid: 101, startedAt: '2026-10-11T01:00:00Z' }, { pid: 202, startedAt: '2026-10-11T01:01:00Z' }, { pid: 202, startedAt: '2026-10-11T01:01:00Z' }]
  const controls = createControls({
    enabled: true,
    runLocal: (args) => { calls.push(args); return { status: 0 } },
    snapshot: async () => snapshots.shift(),
    waitDown: async () => true,
    waitReady: async (service) => ({ ready: true, service, status: 200 }),
  })
  const evidence = await controls.restart({ services: ['ai-service'], caseId: 'CA-017', sessionId: 's' })
  assert.equal(evidence.beforePid, 101); assert.equal(evidence.afterPid, 202); assert.equal(evidence.ready, true)
  assert.deepEqual(calls, [['stop', 'ai-service'], ['ai-service']])
  await assert.rejects(controls.restart({ services: ['gateway-service'], caseId: 'CA-X', sessionId: 's' }), (error) => error.blocked)
})

test('故障方案未显式启用时不停止原服务', async () => {
  let stopped = false
  const controls = createControls({ enabled: true, runLocal: () => { stopped = true } })
  await assert.rejects(controls.fault({ mode: 'enable', failure: 'BUSINESS_FAILURE', target: 'order-service', caseId: 'CA-024', sessionId: 's', userId: 't1' }), (e) => e.blocked && /尚未显式/.test(e.message))
  assert.equal(stopped, false)
})

test('新进程半启时只停止本轮PID，恢复错误保留为AggregateError', async () => {
  const calls = []; const snapshots = [{ pid: 101, startedAt: '2026-10-11T01:00:00Z' }, { pid: 202, startedAt: '2026-10-11T01:01:00Z' }, { pid: 202, startedAt: '2026-10-11T01:01:00Z' }]
  const controls = createControls({ enabled: true, snapshot: async () => snapshots.shift(), waitDown: async () => true, stopOwnedProcess: async (p) => { calls.push(['stop-owned', p.pid]) }, runLocal: async (args) => { calls.push(args) }, waitReady: async () => { throw new Error('ready超时') } })
  await assert.rejects(controls.restart({ services: ['ai-service'] }), (error) => error instanceof AggregateError && error.errors.length === 2)
  assert.deepEqual(calls, [['stop', 'ai-service'], ['ai-service'], ['stop-owned', 202], ['ai-service']])
})

test('回滚时PID变成原进程或别的实例不能误杀', async () => {
  for (const current of [{pid:101,startedAt:'原启动'}, {pid:303,startedAt:'另一实例'}]) {
    let killed = false; let readyCalls = 0
    const snapshots=[{pid:101,startedAt:'原启动'},{pid:202,startedAt:'本轮启动'},current]
    const controls=createControls({enabled:true,snapshot:async()=>snapshots.shift(),runLocal:async()=>{},waitDown:async()=>true,stopOwnedProcess:async()=>{killed=true},waitReady:async()=>{if(readyCalls++===0)throw new Error('ready超时');return {ready:true}}})
    await assert.rejects(controls.restart({services:['ai-service']}),/ready超时/)
    assert.equal(killed,false)
  }
})

function httpGet(port, path, headers = {}) {
  return new Promise((resolve, reject) => {
    const req = request({ hostname: '127.0.0.1', port, path, headers }, (res) => {
      const chunks = []; res.on('data', (chunk) => chunks.push(chunk)); res.on('end', () => resolve({ status: res.statusCode, body: Buffer.concat(chunks).toString('utf8') }))
    })
    req.on('error', reject); req.end()
  })
}

test('故障代理只拦固定用户与路径，其他请求透明转发', async () => {
  const target = createServer((req, res) => { res.writeHead(200, { 'content-type': 'application/json' }); res.end(JSON.stringify({ proxied: true, path: req.url })) })
  const targetPort = await listen(target, 0)
  const proxy = createFaultProxy({ targetPort, failure: 'BUSINESS_FAILURE', targetUserId: 'u-t1', pathPrefixes: ['/orders/'] })
  const proxyPort = await listen(proxy, 0)
  try {
    const injected = await httpGet(proxyPort, '/orders/12/cancel', { 'x-user-id': 'u-t1' })
    assert.equal(injected.status, 200); assert.match(injected.body, /受控业务失败/)
    const otherUser = await httpGet(proxyPort, '/orders/12/cancel', { 'x-user-id': 'u-other' })
    assert.equal(otherUser.status, 200); assert.match(otherUser.body, /proxied/)
    const otherPath = await httpGet(proxyPort, '/products/12', { 'x-user-id': 'u-t1' })
    assert.equal(otherPath.status, 200); assert.match(otherPath.body, /proxied/)
  } finally { await close(proxy); await close(target) }
})

test('故障按准确方法匹配，写入失败在转发前注入且记录requestId', async () => {
  let forwarded = 0
  const target = createServer((req, res) => { forwarded += 1; res.end('真实目标') })
  const targetPort = await listen(target, 0)
  const proxy = createFaultProxy({ targetPort, failure: 'BUSINESS_FAILURE', targetUserId: 'u-t1', pathPrefixes: ['/orders/'], targetMethod: 'POST', targetPath: '/orders/12/cancel' })
  const port = await listen(proxy, 0)
  try {
    await httpGet(port, '/orders/12/cancel', { 'x-user-id': 'u-t1' })
    assert.equal(forwarded, 1)
    const denied = await new Promise((done, reject) => {
      const req = request({ hostname: '127.0.0.1', port, path: '/orders/12/cancel', method: 'POST', headers: { 'x-user-id': 'u-t1', 'x-request-id': 'req-fault' } }, (res) => { res.resume(); res.once('end', () => done(res.statusCode)) })
      req.once('error', reject); req.end()
    })
    assert.equal(denied, 200); assert.equal(forwarded, 1)
    assert.deepEqual(proxy.controlEvidence.injected.map(({ requestId, forwarded }) => ({ requestId, forwarded })), [{ requestId: 'req-fault', forwarded: false }])
  } finally { await close(proxy); await close(target) }
})

test('故障生命周期缺真实流量时不能称verified，仍执行恢复', async () => {
  const target = createServer((req, res) => { res.end('真实目标') })
  const targetPort = await listen(target, 0)
  let restored = false
  const controls = createControls({ enabled: true, snapshot: async () => ({ pid: 77, startedAt: '起始' }), staging: {
    prepare: async () => ({ targetPort, proxyPort: 0 }),
    activate: async () => ({ ready: true }),
    restore: async () => { restored = true; return { ready: true } },
  } })
  const step = { failure: 'BUSINESS_FAILURE', target: 'order-service', caseId: 'CA-024', sessionId: 'mock-control', userId: 't1', paths: ['/orders/1/cancel'], method: 'POST', path: '/orders/1/cancel' }
  try {
    const enabled = await controls.fault({ ...step, mode: 'enable' })
    assert.equal(enabled.verified, true)
    const disabled = await controls.fault({ ...step, mode: 'disable' })
    assert.equal(disabled.verified, false)
    assert.equal(disabled.restored, true); assert.equal(restored, true)
  } finally { await close(target) }
})

test('disable恢复失败仍关闭代理并清理activeFaults', async () => {
  const target = createServer((req, res) => { res.end('真实目标') }); const targetPort = await listen(target, 0)
  const controls = createControls({ enabled: true, snapshot: async () => ({ pid: 77, startedAt: '起始' }), staging: { prepare: async () => ({ targetPort, proxyPort: 0 }), activate: async () => ({ ready: true }), restore: async () => { throw new Error('恢复失败') } } })
  const step = { failure: 'BUSINESS_FAILURE', target: 'order-service', caseId: 'CA-ROLLBACK', sessionId: 's', userId: 'u', paths: ['/orders/1'], method: 'POST', path: '/orders/1' }
  try {
    const enabled = await controls.fault({ ...step, mode: 'enable' }); assert.equal(enabled.verified, true)
    await assert.rejects(controls.fault({ ...step, mode: 'disable' }), /恢复失败/)
    await assert.rejects(httpGet(enabled.proxyPort,'/orders/1'),/ECONNREFUSED/)
    await assert.rejects(controls.fault({ ...step, mode: 'disable' }), /缺本轮已执行故障控制状态/)
  } finally { await close(target) }
})

test('operationId只接受同一requestId且来自Graph/Tool日志，缺证据保持未覆盖', async () => {
  const readText = async () => '[req-a] [Tool] cart_add success=true noData=false operationId=op-a tool=cart_add\n[req-b] [Tool] operationId=op-b 重复\n[req-a] INFO operationId=op-noise\n[req-ab] [Tool] operationId=op-prefix 重复\n'
    const evidence = await collectOperationEvidence({ requestIds: ['req-a'], caseId: 'CA-013' }, ['模拟日志'], readText)
    assert.equal(evidence.covered, true); assert.deepEqual(evidence.uniqueOperationIds, ['op-a'])
    const uncovered = await collectOperationEvidence({ requestIds: ['req-x'], caseId: 'CA-013' }, ['模拟日志'], readText)
    assert.equal(uncovered.covered, false); assert.deepEqual(uncovered.entries, [])
})

test('空operationId及只计划/失败/重复日志都不能成为执行证据', async () => {
  const invalid = ['null', 'undefined', '-', 'NULL', '']
  const text = invalid.map((id) => `[req-a] [Tool] cart_add success=true noData=false operationId=${id} tool=cart_add`).join('\n') + '\n[req-a] [Graph] operationId=op-plan tool=cart_add\n[req-a] [Tool] cart_add success=false operationId=op-fail tool=cart_add\n[req-a] [Tool] operationId=op-repeat 重复提交 tool=cart_add'
  const evidence = await collectOperationEvidence({ requestIds: ['req-a'] }, ['模拟日志'], async () => text)
  assert.equal(evidence.covered, false)
  assert.deepEqual(evidence.entries, [])
})

test('入口RAG必须同请求原query/重排/实际证据门，未检索NONE不通过',async()=>{
  const text='[r] [QueryExpand] 「甲和乙」→假想\n[r] [Rerank] model=实际 candidates=9 kept=3\n[r] [Agent] 证据门 WEAK 切片=3 —— 分数低\n'
  const good=await collectRetrievalEvidence({requestId:'r',query:'甲和乙'},['模拟'],async()=>text)
  assert.equal(good.available,true)
  const stale=await collectRetrievalEvidence({requestId:'新请求',query:'甲和乙'},['模拟'],async()=>text)
  assert.equal(stale.available,false)
  const skipped=await collectRetrievalEvidence({requestId:'r',query:'甲和乙'},['模拟'],async()=>text.replace('WEAK 切片=3 —— 分数低','NONE 切片=0 —— 未检索'))
  assert.equal(skipped.available,false)
})
