import test from 'node:test'
import assert from 'node:assert/strict'
import { captureCorpusMetadata, captureIndexMetadata, aggregateModelUsage, sha256 } from './complex-agent-metadata.mjs'

test('语料数组接口逐篇读取公开正文并记录真实版本与hash', async () => {
  const calls = []
  const result = await captureCorpusMetadata(async (path, options) => {
    calls.push({ path, actor: options.actor })
    return { requestId: '请求', data: path.includes('?') ? [{ docNo: 'KB-1', version: 'v1' }] : { docNo: 'KB-1', version: 'v1', content: '原文', status: 1, chunks: [{ id: 'c' }] } }
  })
  assert.equal(result.available, true)
  assert.equal(result.count, 1)
  assert.equal(result.documents[0].contentSha256, sha256('原文'))
  assert.ok(calls.every(c=>c.actor==='anonymous'))
})

test('语料分页必须取完，提前空页和详情版本变化明确unavailable', async () => {
  let listCalls = 0
  const result = await captureCorpusMetadata(async path => ({ requestId: 'r', data: path.includes('?') ? (listCalls++ === 0 ? { total: 2, records: [{ docNo: 'A', version: '1' }] } : { total: 2, records: [{ docNo: 'B', version: '1' }] }) : { docNo: path.split('/').at(-1), version: '1', content: '原文' } }))
  assert.equal(result.available, true); assert.equal(result.count, 2)
  const truncated = await captureCorpusMetadata(async () => ({ data: { total: 2, records: [] } }))
  assert.equal(truncated.available, false); assert.match(truncated.unavailable, /空页/)
  const changed = await captureCorpusMetadata(async path => ({ data: path.includes('?') ? [{ docNo: 'A', version: '1' }] : { docNo: 'A', version: '2', content: '原文' } }))
  assert.equal(changed.available, false); assert.match(changed.unavailable, /不一致/)
})

test('索引只读取管理员status，失败不伪造doc/chunk规模', async () => {
  const good = await captureIndexMetadata(async (path, options) => {
    assert.equal(path, '/ai/admin/knowledge/status'); assert.equal(options.actor, 'admin')
    return { data: { result: { documentCount: 47, chunkCount: 408 } }, requestId: 'r' }
  })
  assert.equal(good.counts.chunkCount, 408)
  const bad = await captureIndexMetadata(async () => { throw new Error('接口不可用') })
  assert.equal(bad.available, false); assert.equal(bad.status, undefined)
})

test('实际usage.models合计并区分无模型0与缺用量，不能推测模型名', () => {
  const result = aggregateModelUsage([{ turns: [
    { usage: { promptTokens: 3, completionTokens: 2, totalTokens: 5, models: [{ model: '实际模型', promptTokens: 3, completionTokens: 2 }] } },
    { usageEvidence: { mode: 'NO_MODEL_CALL' }, usage: { promptTokens: 0, completionTokens: 0, totalTokens: 0, models: [] } },
    { usage: null },
  ] }])
  assert.equal(result.totalTokens, 5)
  assert.equal(result.noModelTurns, 1); assert.equal(result.unavailableTurns, 1)
  assert.deepEqual(result.models.map(m=>m.model), ['实际模型'])
})
