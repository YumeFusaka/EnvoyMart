/** 固定真实题集的多跳硬门禁；不参与生成，也不重写评测判定。 */
export const EXPECTED_MULTI_HOP_IDS = Object.freeze([
  'G-13', 'G-14', 'G-15', 'G-16', 'G-17', 'G-18',
  ...Array.from({ length: 12 }, (_, index) => 'KG-' + String(index + 1).padStart(2, '0')),
])

export function assessMultiHopAcceptance(run) {
  const cases = run?.cases ?? []
  const multiHop = cases.filter(item => item.outcome?.kind === 'MULTI_HOP')
  const ids = multiHop.map(item => item.outcome.id).sort()
  const hits = multiHop.filter(item => item.outcome.multiHopHit === true).length
  const rate = hits / EXPECTED_MULTI_HOP_IDS.length
  const checks = [
    { name: '36条真实全集成功完成', pass: run?.status === 'COMPLETED' && run.totalCases === 36 && run.completedCases === 36 && run.failedCases === 0 && cases.length === 36 },
    { name: '18条原多跳题未删改', pass: JSON.stringify(ids) === JSON.stringify([...EXPECTED_MULTI_HOP_IDS].sort()) && run?.metrics?.multiHopCases === 18 },
    { name: '报告比例与原逐条判定一致', pass: Number.isFinite(run?.metrics?.multiHopHitRate) && Math.abs(run.metrics.multiHopHitRate - rate) < 1e-12 },
    { name: '多跳命中率严格超过85%', pass: rate > 0.85 && hits >= 16 },
  ]
  return { status: checks.every(check => check.pass) ? 'PASS' : 'FAIL', hits, denominator: 18, rate, minimumHits: 16, checks }
}
