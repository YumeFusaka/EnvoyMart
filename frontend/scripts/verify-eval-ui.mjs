/**
 * 检索质量只读快照验收：按当次真实接口核对指标、分档、图谱口径和执行链路。
 * 不触发模型；管理员运行入口只打开确认框后取消。
 * 前置条件：九服务与前端已启动。用法：node scripts/verify-eval-ui.mjs
 */
import { chromium } from 'playwright-core'

const BASE = process.env.VERIFY_BASE ?? 'http://localhost:5173'
const GW = process.env.VERIFY_GW ?? 'http://localhost:8080'
const CHROMIUM = process.env.PLAYWRIGHT_CHROMIUM ?? 'C:/Program Files/Google/Chrome/Application/chrome.exe'
const REPORT = '/ai/eval/retrieval/report'
const RUN = '/ai/admin/eval/retrieval/run'
let pass = 0
let fail = 0
const blocked = []
const compact = (text) => (text ?? '').replace(/\s+/g, ' ').trim()
const pct = (value) => (value * 100).toFixed(1) + '%'
const fixed3 = (value) => value.toFixed(3)
function ck(name, condition, detail = '') {
  console.log('  ' + (condition ? '通过' : '失败') + ' ' + name + (condition || !detail ? '' : '：' + detail))
  if (condition) pass++
  else fail++
}
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
  // 页面回归也不能绕过只读验收，真实触发请求在到达服务前被拦住。
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
  await page.goto(BASE + '/#/knowledge/eval', { waitUntil: 'networkidle' })
  const body = await (await responsePromise).json()
  if (body.code !== 200 || body.data?.status !== 'COMPLETED') {
    throw new Error('需要已完成的真实检索快照：' + JSON.stringify(body.data?.status))
  }
  await page.locator('.case-list').waitFor({ state: 'visible' })
  return body.data
}
async function filter(page, label, expected, total) {
  await page.locator('.cases-panel .el-radio-button__inner').getByText(label, { exact: true }).click()
  await page.waitForFunction((count) => document.querySelectorAll('.case-row').length === count, expected)
  ck('筛选「' + label + '」与快照一致', await page.locator('.case-row').count() === expected &&
    compact(await page.locator('.cases-count').textContent()) === '显示 ' + expected + ' / ' + total + ' 条')
}

try {
  console.log('\n一、匿名公开快照：指标、分母与真实链路')
  const anon = await newPage()
  const report = await openReport(anon)
  const { overall, cases, graphPath, corpus } = report
  ck('快照样本数、指标分母与明细一致', cases.length > 0 && overall.caseCount === cases.length && corpus.cases === cases.length)
  const meta = compact(await anon.locator('.snapshot-meta').textContent())
  ck('元信息标明只读、真实语料和快照标识', meta.includes('只读快照') &&
    meta.includes('语料 ' + corpus.documents + ' 篇 · ' + corpus.cases + ' 条样本') && meta.includes(report.runId))
  ck('生产链路逐字来自快照', compact(await anon.locator('.pipeline__value').textContent()) === compact(report.pipeline))
  const cards = anon.locator('.metric-card')
  const displayed = (await cards.locator('.metric-card__value').allTextContents()).map(compact)
  ck('整体三项指标逐位匹配快照', await cards.count() === 3 &&
    JSON.stringify(displayed) === JSON.stringify([pct(overall.hitRate), fixed3(overall.mrr), fixed3(overall.ndcg)]))
  ck('Top-K与快照一致', (await cards.first().locator('.metric-card__label').textContent()) === 'Hit Rate@' + overall.topK)
  const hitRate = cases.filter((item) => item.hit).length / cases.length
  const mrr = cases.reduce((sum, item) => sum + (item.hit ? 1 / item.hitRank : 0), 0) / cases.length
  ck('命中率和MRR可由逐条结果核对', Math.abs(overall.hitRate - hitRate) < 1e-9 && Math.abs(overall.mrr - mrr) < 1e-9)
  const graphRequired = cases.filter((item) => item.graphRequired)
  const graphCandidates = graphRequired.filter((item) => (item.trace?.graphCandidates ?? 0) > 0)
  const graphMisses = graphRequired.filter((item) => !item.graphEvidenceHit)
  const graphHits = graphRequired.filter((item) => item.graphEvidenceHit)
  ck('图谱保留口径与全部查询分母分开', graphPath.requiredCases === graphRequired.length && graphPath.hitCases === graphHits.length)
  const graphCards = anon.locator('.graph-summary__card')
  ck('图谱贡献与候选召回展示正确分母', compact(await graphCards.nth(1).locator('strong').textContent()) ===
    graphHits.length + ' / ' + graphRequired.length &&
    compact(await graphCards.nth(2).locator('strong').textContent()) === graphCandidates.length + ' / ' + graphRequired.length)
  ck('逐条查询原文顺序一致', JSON.stringify(await anon.locator('.case-row__query').allTextContents()) ===
    JSON.stringify(cases.map((item) => item.query)))
  ck('未命中与图谱未保留行标记一致', await anon.locator('.case-row.is-miss').count() ===
    cases.filter((item) => !item.hit || item.graphRequired && !item.graphEvidenceHit).length)
  ck('图谱路径逐条可核对', await anon.locator('.graph-status').count() === graphRequired.length)
  ck('分档齐备且样本分母合计一致', await anon.locator('.stratum').count() === report.strata.length &&
    report.strata.reduce((sum, item) => sum + item.metrics.caseCount, 0) === cases.length)
  for (let index = 0; index < report.strata.length; index++) {
    const stratum = report.strata[index]
    const row = anon.locator('.stratum').nth(index)
    ck('分档「' + stratum.label + '」指标与分母一致', compact(await row.locator('.stratum__head').textContent()) ===
      stratum.label + pct(stratum.metrics.hitRate) &&
      compact(await row.locator('small').textContent()) === stratum.metrics.caseCount + ' 条 · MRR ' +
      fixed3(stratum.metrics.mrr) + ' · NDCG ' + fixed3(stratum.metrics.ndcg) &&
      stratum.metrics.caseCount === cases.filter((item) => item.stratum === stratum.key).length)
  }

  const traceIndex = cases.findIndex((item) => item.trace && item.evidence?.length)
  ck('真实快照包含检索trace与证据切片', traceIndex >= 0)
  if (traceIndex >= 0) {
    const item = cases[traceIndex]
    const row = anon.locator('.case-row').nth(traceIndex)
    await row.locator('.trace-details summary').click()
    const trace = compact(await row.locator('.trace-details').textContent())
    ck('展开链路展示查询、候选、重排和证据', trace.includes(item.trace.query) &&
      trace.includes('向量 ' + item.trace.vectorCandidates + ' · BM25 ' + item.trace.keywordCandidates +
        ' · 图谱 ' + item.trace.graphCandidates + ' · 融合 ' + item.trace.fusedCandidates) &&
      trace.includes(item.trace.rerankApplied ? '已执行（' + item.trace.rerankedCandidates + ' 条）' : '未执行') &&
      trace.includes(item.evidence.length + ' 条已进入回答上下文'))
    ck('期望和实际文档链接与快照一致', await row.locator('.case-row__docs a').count() ===
      item.relevantDocIds.length + item.retrievedDocIds.length)
    const link = row.locator('.case-row__docs a').first()
    ck('证据原文链接可到知识文档', (await link.getAttribute('href')).includes('/knowledge/' + item.relevantDocIds[0]))
  }
  await filter(anon, '仅未命中', cases.filter((item) => !item.hit).length, cases.length)
  await filter(anon, '仅图谱未保留', graphMisses.length, cases.length)
  for (const stratum of report.strata) {
    await filter(anon, stratum.label, cases.filter((item) => item.stratum === stratum.key).length, cases.length)
  }
  await filter(anon, '全部', cases.length, cases.length)
  ck('公开页没有旧离线扩写对照或重跑按钮', await anon.locator('.expansion-panel').count() === 0 &&
    await anon.getByRole('button', { name: /重新运行|开始真跑|重新真跑|手动触发/ }).count() === 0)
  ck('匿名页面无控制台异常', anon.errors.length === 0, anon.errors.join(' | '))
  await anon.context().close()

  console.log('\n二、管理员仅在运行中心确认并取消')
  const admin = await newPage(await login('admin'))
  await openReport(admin)
  ck('管理员公开检索页仍然只读', await admin.getByRole('button', { name: /重新运行|手动触发/ }).count() === 0)
  await admin.goto(BASE + '/#/admin/knowledge/evaluations', { waitUntil: 'networkidle' })
  const card = admin.locator('.run-card', { has: admin.getByRole('heading', { name: '检索质量评测' }) })
  await card.waitFor()
  ck('管理员运行中心可见检索评测入口', await card.getByRole('button', { name: '手动触发' }).count() === 1)
  await card.getByRole('button', { name: '手动触发' }).click()
  const dialog = admin.getByRole('dialog')
  await dialog.waitFor()
  ck('触发前明确确认真实embedding与配额', (await dialog.textContent()).includes('真实 embedding') &&
    (await dialog.textContent()).includes('模型额度'))
  await dialog.getByRole('button', { name: '取消', exact: true }).click()
  await dialog.waitFor({ state: 'hidden' })
  ck('管理员取消确认后无页面异常', admin.errors.length === 0, admin.errors.join(' | '))
  await admin.context().close()

  console.log('\n三、权限与无模型触发')
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
  console.log('  快照 runId=' + report.runId + '；样本分母=' + cases.length + '；图谱分母=' + graphRequired.length + '；管理员触发请求=0')
} catch (error) {
  ck('脚本执行完成', false, error.stack)
} finally {
  await browser.close()
}
console.log('\n结果：' + pass + ' 通过，' + fail + ' 失败')
process.exitCode = fail ? 1 : 0
