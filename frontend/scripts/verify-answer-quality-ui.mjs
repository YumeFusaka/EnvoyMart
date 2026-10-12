/**
 * 回答质量只读快照验收：按当次接口核对指标、分母、36 条回答和证据链路。
 * 默认及 --offline-only 均不触发模型；管理员运行入口只打开确认框后取消。
 * 前置条件：九服务与前端已启动。用法：node scripts/verify-answer-quality-ui.mjs
 */
import { mkdirSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { chromium } from 'playwright-core'
import { assessMultiHopAcceptance } from './lib/grounding-acceptance.mjs'

const BASE = process.env.VERIFY_BASE ?? 'http://localhost:5173'
const GW = process.env.VERIFY_GW ?? 'http://localhost:8080'
const CHROMIUM = process.env.PLAYWRIGHT_CHROMIUM ?? 'C:/Program Files/Google/Chrome/Application/chrome.exe'
const OUT_DIR = resolve(dirname(fileURLToPath(import.meta.url)), '../.screenshots')
const REPORT = '/ai/eval/grounding/report'
const RUN = '/ai/admin/eval/grounding/run'
let pass = 0
let fail = 0
const blocked = []
function ck(name, condition, detail = '') {
  console.log('  ' + (condition ? '通过' : '失败') + ' ' + name + (condition || !detail ? '' : '：' + detail))
  if (condition) pass++
  else fail++
}
const compact = (text) => (text ?? '').replace(/\s+/g, ' ').trim()
const pct = (value) => (value * 100).toFixed(1) + '%'
const issue = (item) => !item.outcome || item.outcome.unsupported > 0 ||
  item.outcome.wrongCitations > 0 || item.outcome.outOfRange > 0 ||
  !item.outcome.refusalCorrect || (item.outcome.kind === 'MULTI_HOP' && !item.outcome.multiHopHit)
const completenessRecorded = (run, item) => ['agent-live-v6-medical-completeness', 'agent-live-v7-medical-scope', 'agent-live-v8-medical-publication', 'agent-live-v9-medical-context'].includes(run.pipelineVersion) &&
  typeof item.outcome?.answerComplete === 'boolean' && Array.isArray(item.outcome.missingRequired)
const issueFor = (run, item) => issue(item) || (completenessRecorded(run, item) && item.outcome.answerComplete === false)

async function login(username) {
  const response = await fetch(GW + '/auth/login', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password: '123456' }),
  })
  const body = await response.json()
  if (body.code !== 200) throw new Error('登录失败：' + username + '，' + body.msg)
  return body.data
}
const browser = await chromium.launch({ executablePath: CHROMIUM })
async function newPage(session) {
  const context = await browser.newContext({ viewport: { width: 1600, height: 1200 } })
  // 即使页面发生回归，也不能把管理员验收变成一次真实模型采集。
  await context.route(/\/(?:ai|knowledge)\/admin\/eval\/.*\/?run(?:\?|$)/, async (route) => {
    blocked.push(route.request().method() + ' ' + new URL(route.request().url()).pathname)
    await route.abort('blockedbyclient')
  })
  if (session) await context.addInitScript(
    (value) => localStorage.setItem('user', JSON.stringify(value)),
    { token: session.token, profile: session.user },
  )
  const page = await context.newPage()
  page.errors = []
  page.on('pageerror', (error) => page.errors.push(error.message))
  page.on('console', (message) => { if (message.type() === 'error') page.errors.push(message.text()) })
  return page
}
async function openReport(page) {
  const responsePromise = page.waitForResponse((response) =>
    new URL(response.url()).pathname === REPORT && response.request().method() === 'GET')
  await page.goto(BASE + '/#/knowledge/eval/answer', { waitUntil: 'networkidle' })
  const body = await (await responsePromise).json()
  if (body.code !== 200 || body.data?.live?.status !== 'COMPLETED') {
    throw new Error('需要已完成的真实回答快照：' + JSON.stringify(body.data?.live?.status))
  }
  await page.locator('.case-list').waitFor({ state: 'visible' })
  return body.data.live
}
async function filter(page, label, expected) {
  await page.locator('.cases-panel .el-radio-button__inner').getByText(label, { exact: true }).click()
  await page.waitForFunction((count) => document.querySelectorAll('.case-row').length === count, expected)
  ck('筛选「' + label + '」与快照一致', await page.locator('.case-row').count() === expected)
}

try {
  mkdirSync(OUT_DIR, { recursive: true })
  console.log('\n一、匿名公开快照：指标、回答与证据')
  const anon = await newPage()
  const run = await openReport(anon)
  const metrics = run.metrics
  const multiHopAcceptance = assessMultiHopAcceptance(run)
  for (const check of multiHopAcceptance.checks) ck('多跳硬门禁：' + check.name, check.pass,
    multiHopAcceptance.hits + '/18；要求至少16/18（88.9%）')
  ck('回答契约为36条且全部完成', run.totalCases === 36 && run.completedCases === 36 &&
    run.cases.length === 36 && metrics.caseCount === 36)
  const meta = compact(await anon.locator('.snapshot-meta').textContent())
  ck('元信息标明只读、完成数与快照版本', meta.includes('只读快照') &&
    meta.includes('完成 ' + run.completedCases + ' / ' + run.totalCases + ' 条') &&
    meta.includes(run.promptVersion) && meta.includes(run.pipelineVersion) && meta.includes(run.runId))
  const cards = anon.locator('.metric-card')
  const values = [metrics.hallucinationRate, metrics.citationAccuracy, metrics.refusalAccuracy, metrics.multiHopHitRate]
  ck('四项指标逐位匹配当次真实快照', await cards.count() === 4 &&
    JSON.stringify(await cards.locator('strong').allTextContents()) === JSON.stringify(values.map(pct)))
  ck('事实句分母随快照变化', compact(await cards.nth(0).locator('small').textContent()) ===
    metrics.unsupportedSentences + ' / ' + metrics.factSentences + ' 句未被证据支撑')
  ck('证据门与多跳统计展示真实计数', compact(await cards.nth(2).locator('small').textContent()) ===
    '未拦截低相关证据 ' + metrics.missedRefusals + ' · 误拦截可答问题 ' + metrics.overRefusals &&
    compact(await cards.nth(3).locator('small').textContent()) === metrics.multiHopCases + ' 条多跳问题')
  const sum = (key) => run.cases.reduce((total, item) => total + (item.outcome?.[key] ?? 0), 0)
  ck('指标分母与36条判定汇总一致', metrics.factSentences === sum('factSentences') &&
    metrics.unsupportedSentences === sum('unsupported') && metrics.citationChecks === sum('checks') &&
    metrics.multiHopCases === run.cases.filter((item) => item.outcome?.kind === 'MULTI_HOP').length)
  ck('36条问题原文顺序一致', JSON.stringify(await anon.locator('.question').allTextContents()) ===
    JSON.stringify(run.cases.map((item) => item.question)))
  ck('问题行标记与确定性判定一致', await anon.locator('.case-row.issue').count() === run.cases.filter((item) => issueFor(run, item)).length)
  for (let index = 0; index < run.cases.length; index++) {
    const item = run.cases[index]
    const text = await anon.locator('.case-row').nth(index).locator('.answer-completeness').textContent()
    ck('完整性来源与当次快照一致 ' + (item.outcome?.id ?? index), completenessRecorded(run, item)
      ? text.includes(item.outcome.answerComplete ? '必要要点完整' : '必要要点缺失') && item.outcome.missingRequired.every((point) => text.includes(point))
      : text.includes('本次快照未记录'))
  }
  ck('每条回答都有真实回答或失败原因', run.cases.every((item) => !!(item.answer?.trim() || item.error)))
  ck('回答正文与失败行均已渲染', await anon.locator('.case-row .message-content').count() ===
    run.cases.filter((item) => !item.error).length && await anon.locator('.case-row .error').count() ===
    run.cases.filter((item) => item.error).length &&
    (await anon.locator('.case-row .message-content').allTextContents()).every((answer) => answer.trim().length > 0))
  ck('当次证据完整展示', await anon.locator('.citation').count() ===
    run.cases.reduce((count, item) => count + (item.evidence?.length ?? 0), 0))

  const traceIndex = run.cases.findIndex((item) => item.retrievalTrace && item.evidence?.length)
  ck('真实快照包含可追溯检索与证据', traceIndex >= 0)
  if (traceIndex >= 0) {
    const item = run.cases[traceIndex]
    const row = anon.locator('.case-row').nth(traceIndex)
    await row.locator('.trace-details > summary').click()
    const trace = compact(await row.locator('.trace-details').textContent())
    ck('执行链路展示查询、请求标识、候选和证据数', trace.includes(item.retrievalTrace.query) &&
      trace.includes(item.requestId) && trace.includes('向量 ' + item.retrievalTrace.vectorCandidates) &&
      trace.includes('BM25 ' + item.retrievalTrace.keywordCandidates) &&
      compact(await row.locator('.trace-stage--metadata dd').nth(1).textContent()) === item.evidenceCount + ' 条')
    ck('当次证据内容及原文切片链接可核对', compact(await row.locator('.citation__content').first().textContent()) ===
      compact(item.evidence[0].content) &&
      (await row.locator('.citation__head').first().getAttribute('href')).includes('/knowledge/' + item.evidence[0].docId) &&
      (await row.locator('.citation__head').first().getAttribute('href')).includes('chunk=' + encodeURIComponent(item.evidence[0].chunkId)))
    const cite = row.locator('[data-cite]').first()
    if (await cite.count()) {
      const ref = Number(await cite.getAttribute('data-cite'))
      await cite.click()
      ck('回答角标定位当次对应证据', await row.locator('[id="cite-eval-' + item.outcome.id + '-' + ref + '"].is-active').count() === 1)
    }
  }
  for (const [label, kind] of [['该答', 'ANSWERABLE'], ['多跳', 'MULTI_HOP'], ['该拒', 'UNANSWERABLE']]) {
    await filter(anon, label, run.cases.filter((item) => item.outcome?.kind === kind).length)
  }
  await filter(anon, '仅有问题', run.cases.filter((item) => issueFor(run, item)).length)
  await filter(anon, '全部', run.cases.length)
  const causeCounts = new Map()
  for (const item of run.cases) {
    const cause = item.outcome?.rootCause
    if (cause && cause !== 'NONE') causeCounts.set(cause, (causeCounts.get(cause) ?? 0) + 1)
  }
  const rootCauses = [...causeCounts.entries()].sort((a, b) => b[1] - a[1])
  ck('根因分布与快照一致', JSON.stringify(await anon.locator('.root-cause-list button b').allTextContents()) ===
    JSON.stringify(rootCauses.map(([, count]) => String(count))))
  if (rootCauses.length) {
    const button = anon.locator('.root-cause-list button').first()
    await button.click()
    const expected = Number(await button.locator('b').textContent())
    await anon.waitForFunction((count) => document.querySelectorAll('.case-row').length === count, expected)
    ck('根因筛选计数与快照一致', expected === rootCauses[0][1])
    await button.click()
  }
  ck('匿名公开页没有触发模型按钮', await anon.getByRole('button', { name: /开始真跑|重新真跑|重新运行|手动触发/ }).count() === 0)
  ck('匿名页面无控制台异常', anon.errors.length === 0, anon.errors.join(' | '))
  await anon.screenshot({ path: resolve(OUT_DIR, 'answer-quality-anon.png'), fullPage: false })
  await anon.context().close()

  console.log('\n确定性展示夹具：完整性缺项与旧快照兼容，不计入真实评测指标')
  const fixture = structuredClone(run)
  fixture.cases = [structuredClone(run.cases.find((item) => item.outcome?.kind === 'ANSWERABLE'))]
  Object.assign(fixture.cases[0].outcome, { unsupported: 0, wrongCitations: 0, outOfRange: 0,
    refusalCorrect: true, rootCause: 'NONE', answerComplete: false, missingRequired: ['版本成分缺失', '时间下界缺失'] })
  fixture.pipelineVersion = 'agent-live-v6-medical-completeness'
  const preview = await newPage()
  await preview.context().route('**/ai/eval/grounding/report', (route) => route.fulfill({
    json: { code: 200, data: { live: fixture } },
  }))
  await openReport(preview)
  ck('无未支撑事实仍能标记必要要点缺失', await preview.locator('.case-row.issue').count() === 1)
  ck('缺失要点逐项展示', (await preview.locator('.answer-completeness').textContent()).includes('版本成分缺失') &&
    (await preview.locator('.answer-completeness').textContent()).includes('时间下界缺失'))
  await preview.locator('.answer-completeness').screenshot({ path: resolve(OUT_DIR, 'answer-completeness-fixture.png') })
  await filter(preview, '仅有问题', 1)
  fixture.pipelineVersion = 'agent-live-v5-medical-evidence'
  await preview.reload({ waitUntil: 'networkidle' })
  await preview.locator('.case-row').waitFor()
  ck('旧版本默认false不冒充已执行完整性判定', (await preview.locator('.answer-completeness').textContent()).includes('本次快照未记录') &&
    await preview.locator('.case-row.issue').count() === 0)
  await preview.context().close()

  console.log('\n二、管理员仅在运行中心确认并取消')
  const admin = await newPage(await login('admin'))
  await openReport(admin)
  ck('管理员公开页仍然只读', await admin.getByRole('button', { name: /开始真跑|重新真跑|重新运行|手动触发/ }).count() === 0)
  await admin.goto(BASE + '/#/admin/knowledge/evaluations', { waitUntil: 'networkidle' })
  const card = admin.locator('.run-card', { has: admin.getByRole('heading', { name: '回答质量评测' }) })
  await card.waitFor()
  ck('管理员运行中心可见回答评测入口', await card.getByRole('button', { name: '手动触发' }).count() === 1)
  await card.getByRole('button', { name: '手动触发' }).click()
  const dialog = admin.getByRole('dialog')
  await dialog.waitFor()
  ck('触发前明确确认真实Agent与配额', (await dialog.textContent()).includes('真实 Agent') &&
    (await dialog.textContent()).includes('模型额度'))
  await dialog.getByRole('button', { name: '取消', exact: true }).click()
  await dialog.waitFor({ state: 'hidden' })
  ck('管理员取消确认后无页面异常', admin.errors.length === 0, admin.errors.join(' | '))
  await admin.context().close()

  console.log('\n三、权限与无模型触发')
  // 只探测无权调用者；绝不向真实触发端点发送管理员请求。
  const anonymous = await fetch(GW + RUN, { method: 'POST' })
  ck('匿名触发端点被网关拒绝401', anonymous.status === 401)
  const session = await login('alice')
  const denied = await fetch(GW + RUN, { method: 'POST', headers: { Authorization: 'Bearer ' + session.token } })
  const deniedBody = await denied.json()
  ck('普通用户触发端点被拒绝403', denied.status === 403 || deniedBody.code === 403)
  const normal = await newPage(session)
  await openReport(normal)
  await normal.goto(BASE + '/#/admin/knowledge/evaluations', { waitUntil: 'networkidle' })
  await normal.waitForURL('**/#/shop')
  ck('普通用户无法进入运行中心', !normal.url().includes('/admin/knowledge/evaluations') && await normal.locator('.run-card').count() === 0)
  await normal.context().close()
  ck('浏览器未尝试任何评测触发请求', blocked.length === 0, blocked.join(' | '))
  console.log('  快照 runId=' + run.runId + '；事实句分母=' + metrics.factSentences + '；管理员触发请求=0')
} catch (error) {
  ck('脚本执行完成', false, error.stack)
} finally {
  await browser.close()
}
console.log('\n结果：' + pass + ' 通过，' + fail + ' 失败')
process.exitCode = fail ? 1 : 0
