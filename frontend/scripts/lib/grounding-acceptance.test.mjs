import { test } from 'node:test'
import assert from 'node:assert/strict'
import { assessMultiHopAcceptance, EXPECTED_MULTI_HOP_IDS } from './grounding-acceptance.mjs'

function run(hits) {
  return { status: 'COMPLETED', totalCases: 36, completedCases: 36, failedCases: 0,
    metrics: { multiHopCases: 18, multiHopHitRate: hits / 18 },
    cases: [...EXPECTED_MULTI_HOP_IDS.map((id, index) => ({ outcome: { id, kind: 'MULTI_HOP', multiHopHit: index < hits } })),
      ...Array.from({ length: 18 }, (_, index) => ({ outcome: { id: 'other-' + index, kind: 'ANSWERABLE' } }))],
  }
}
test('13/18和15/18不合格，16/18达到严格85%以上', () => {
  assert.equal(assessMultiHopAcceptance(run(13)).status, 'FAIL')
  assert.equal(assessMultiHopAcceptance(run(15)).status, 'FAIL')
  assert.equal(assessMultiHopAcceptance(run(16)).status, 'PASS')
  assert.equal(assessMultiHopAcceptance(run(18)).status, 'PASS')
})
test('不能修改分母、删除难题或伪造总比例', () => {
  const denominator = run(16); denominator.metrics.multiHopCases = 17
  assert.equal(assessMultiHopAcceptance(denominator).status, 'FAIL')
  const replacement = run(16); replacement.cases[17].outcome.id = 'replacement'
  assert.equal(assessMultiHopAcceptance(replacement).status, 'FAIL')
  const forged = run(13); forged.metrics.multiHopHitRate = 1
  assert.equal(assessMultiHopAcceptance(forged).status, 'FAIL')
})
test('执行失败和未完成不能凭多跳率通过', () => {
  const failed = run(16); failed.failedCases = 1
  assert.equal(assessMultiHopAcceptance(failed).status, 'FAIL')
  const partial = run(16); partial.status = 'RUNNING'
  assert.equal(assessMultiHopAcceptance(partial).status, 'FAIL')
})
