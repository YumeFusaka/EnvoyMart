/** 纯 mock 快照故障回归；阻断写请求，不访问模型、不修改真实快照。 */
import assert from 'node:assert/strict'
import { mkdirSync } from 'node:fs'
import { resolve } from 'node:path'
import { chromium } from 'playwright-core'

const base = process.env.VERIFY_BASE ?? 'http://localhost:5173'
const browser = await chromium.launch({ executablePath: process.env.PLAYWRIGHT_CHROMIUM ?? 'C:/Program Files/Google/Chrome/Application/chrome.exe' })
const metrics = { caseCount: 1, hallucinationRate: 0, factSentences: 1, unsupportedSentences: 0, citationAccuracy: 1, citationChecks: 1, wrongCitations: 0, outOfRangeCitations: 0, refusalAccuracy: 1, missedRefusals: 0, overRefusals: 0, multiHopHitRate: 1, multiHopCases: 1 }
const grounding = {
  status: 'COMPLETED', runId: 'grounding-last-good', startedAt: '2026-10-10T10:00:00+08:00', finishedAt: '2026-10-10T10:01:00+08:00',
  totalCases: 1, completedCases: 1, failedCases: 0, currentQuestion: null, userId: 'eval-history', metrics,
  promptVersion: '历史提示词', pipelineVersion: '历史链路',
  cases: [{ question: '历史回答问题', answer: '历史回答正文。', evidenceCount: 0, latencyMs: 1, error: null, evidence: [],
    outcome: { id: 'H-01', kind: 'ANSWERABLE', mode: 'PER_SENTENCE', answerable: true, factSentences: 1, unsupported: 0, checks: 1, wrongCitations: 0, outOfRange: 0, gateRefused: false, refusalCorrect: true, multiHopHit: false, citations: [], rootCause: 'NONE' } }],
}
const retrieval = {
  status: 'COMPLETED', runId: 'retrieval-last-good', generatedAt: '2026-10-10T10:02:00+08:00', durationMs: 10, trigger: 'MANUAL', pipeline: '历史检索链路',
  corpus: { documents: 47, cases: 1, topK: 3 }, overall: { topK: 3, caseCount: 1, hitRate: 1, mrr: 1, ndcg: 1 }, strata: [],
  graphPath: { requiredCases: 0, hitCases: 0, hitRate: 0 }, error: null,
  cases: [{ query: '历史检索问题', stratum: 'LEXICAL', relevantDocIds: [], retrievedDocIds: [], retrievedTitles: {}, hit: true, hitRank: 1, graphRequired: false, graphEvidenceHit: false }],
}
let checks = 0
let forbidden = 0
const screenshotDirectory = resolve('.screenshots')
mkdirSync(screenshotDirectory, { recursive: true })

async function scenario(status, history, admin = false) {
  const context = await browser.newContext({ viewport: { width: 1440, height: 1200 }, timezoneId: 'Asia/Shanghai' })
  const errors = []
  if (admin) await context.addInitScript(() => localStorage.setItem('user', JSON.stringify({ token: 'mock-token', profile: { id: 1, username: 'mock-admin', roleName: 'ADMIN' } })))
  await context.route('**/*', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    if (!['/ai/', '/knowledge/', '/tickets/', '/auth/', '/orders/', '/after-sales/'].some((prefix) => url.pathname.startsWith(prefix))) return route.continue()
    if (request.method() !== 'GET') { forbidden++; return route.abort('blockedbyclient') }
    let data = { total: 0, records: [] }
    const fail = { status, runId: 'current-bad-run', snapshotError: '当前快照读取失败，请检查文件', snapshotPath: 'data/eval/current.json' }
    if (url.pathname === '/ai/eval/grounding/report') data = { live: { ...grounding, ...fail, metrics: null, cases: [], lastCompleted: history ? grounding : null } }
    if (url.pathname === '/ai/eval/retrieval/report') data = { ...retrieval, ...fail, cases: [], lastCompleted: history ? retrieval : null }
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 200, data }) })
  })
  const page = await context.newPage()
  page.on('pageerror', (error) => errors.push(error.message))
  page.on('console', (message) => { if (message.type() === 'error') errors.push(message.text()) })
  for (const hash of admin ? ['/admin/knowledge/evaluations'] : ['/knowledge/eval/answer', '/knowledge/eval']) {
    const response = page.waitForResponse((item) => new URL(item.url()).pathname === (admin || hash.endsWith('answer') ? '/ai/eval/grounding/report' : '/ai/eval/retrieval/report'))
    await page.goto(base + '/#' + hash, { waitUntil: 'networkidle' })
    await response
    try { await page.locator(admin ? '.eval-admin' : hash.endsWith('answer') ? '.quality-page' : '.eval-page').waitFor() }
    catch (error) { throw new Error(`页面未就绪：${page.url()}，${errors.join('；')}，${(await page.locator('body').innerText()).slice(0, 1000)}`, { cause: error }) }
    await page.locator('[role="alert"]').first().waitFor()
    const text = await page.locator('body').innerText()
    assert.match(text, /当前快照读取失败/)
    checks++
    if (admin) assert.match(text, new RegExp('当前状态：' + status))
    else assert.match(text, status === 'SNAPSHOT_CORRUPTED' ? /快照已损坏/ : /版本不兼容/)
    checks++
    if (history) {
      assert.match(text, /最后可用快照/)
      assert.match(text, /2026-10-10 10:0[12]/)
      assert.match(text, admin ? /grounding-last-good/ : hash.endsWith('answer') ? /grounding-last-good/ : /retrieval-last-good/)
      if (!admin) assert.equal(await page.locator('.case-list > li').count(), 1)
      checks++
    } else {
      assert.match(text, /没有可用的已完成历史快照/)
      assert.equal(await page.locator('.metric-card').count(), 0)
      checks++
    }
    assert.equal(errors.length, 0, errors.join('\n'))
    checks++
    await page.screenshot({ path: resolve(screenshotDirectory, `snapshot-fallback-${admin ? 'admin' : hash.endsWith('answer') ? 'answer' : 'retrieval'}-${status}-${history}.png`) })
  }
  await context.close()
}

try {
  for (const status of ['SNAPSHOT_CORRUPTED', 'VERSION_INCOMPATIBLE']) {
    await scenario(status, true)
    await scenario(status, false)
    await scenario(status, true, true)
  }
  assert.equal(forbidden, 0, '页面访问触发了写请求')
  console.log(`通过 ${checks + 1} 项快照故障 UI 验收，真实评测调用 0 次`)
} finally { await browser.close() }
