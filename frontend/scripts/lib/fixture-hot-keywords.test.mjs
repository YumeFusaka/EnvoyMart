import test from 'node:test'
import assert from 'node:assert/strict'
import { fixtureSearchWord, captureFixtureSearch, confirmFixtureSearch, cleanupFixtureSearches } from './fixture-hot-keywords.mjs'

function redisFixture(values = {}) {
  const scores = new Map(Object.entries(values)); const calls = []
  const redis = (command, ...args) => {
    calls.push([command, ...args])
    if (command === 'ZSCORE') return String(scores.get(args[0]) ?? '')
    if (command === 'ZREM') { scores.delete(args[0]); return '1' }
    if (command === 'ZINCRBY') { scores.set(args[1], (scores.get(args[1]) ?? 0) + Number(args[0])); return String(scores.get(args[1])) }
    throw new Error('不允许的命令')
  }
  return { redis, scores, calls }
}

test('自然词、管理查询、别轮随机词和翻页不能获得热搜清理权限', () => {
  const owner = 't1ui_deadbeef'
  assert.equal(fixtureSearchWord('/products/search?keyword=维生素D', owner), null)
  assert.equal(fixtureSearchWord('/products/admin/spus?keyword=' + owner, owner), null)
  assert.equal(fixtureSearchWord('/products/search?keyword=t1ui_11223344', owner), null)
  assert.equal(fixtureSearchWord('/products/search?keyword=' + owner + '&page=1', owner), null)
  assert.equal(fixtureSearchWord('/products/search?keyword=' + owner + '&page=0', owner), owner)
})

test('零基线仅删除本轮确认词，未确认请求和无关自然词保留', () => {
  const { redis, scores, calls } = redisFixture({ 维生素D: 20 })
  const ledger = []; const path = '/products/search?keyword=t1ui_deadbeef&page=0'
  captureFixtureSearch(ledger, path, 't1ui_deadbeef', 'own', redis)
  scores.set('t1ui_deadbeef', 1)
  confirmFixtureSearch(ledger, 'own', 200)
  const result = cleanupFixtureSearches(ledger, redis)
  assert.equal(result[0].action, 'ZREM'); assert.equal(result[0].after, 0)
  assert.equal(scores.get('维生素D'), 20)
  assert.ok(calls.every(([command]) => ['ZSCORE', 'ZREM'].includes(command)))
})

test('已有分数及并发增加保留，只归还本轮已确认增量', () => {
  const { redis, scores, calls } = redisFixture({ t1api_deadbeef: 4 })
  const ledger = []; const path = '/products/search?keyword=t1api_deadbeef&page=0'
  captureFixtureSearch(ledger, path, 't1api_deadbeef', 'own', redis)
  confirmFixtureSearch(ledger, 'own', 200); scores.set('t1api_deadbeef', 6)
  const result = cleanupFixtureSearches(ledger, redis)
  assert.equal(result[0].action, 'RESTORE_OWN_DELTA'); assert.equal(result[0].after, 5)
  assert.ok(!calls.some(([command]) => command === 'ZREM'))
})

test('没有来源的人工词也不能删除；失败查询不归还未发生的增量', () => {
  assert.throws(() => cleanupFixtureSearches([{ word: '未知人工词', baselineScore: 0, requests: [] }], () => '1'), /缺独立来源/)
  const { redis, scores } = redisFixture({ t1ui_deadbeef: 2 })
  const ledger = []
  captureFixtureSearch(ledger, '/products/search?keyword=t1ui_deadbeef', 't1ui_deadbeef', 'failed', redis)
  confirmFixtureSearch(ledger, 'failed', 500)
  assert.equal(cleanupFixtureSearches(ledger, redis)[0].action, 'KEEP')
  assert.equal(scores.get('t1ui_deadbeef'), 2)
})
