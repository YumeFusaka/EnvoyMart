/** 挂载真实诊断页与文档页；拦截所有业务请求，仅验证契约，不调用模型或修改后端。 */
import assert from 'node:assert/strict'
import { mkdirSync } from 'node:fs'
import { chromium } from 'playwright-core'
import { poll } from './verify-util.mjs'

export async function verifyGraphDiagnosticsUi() {
  const base = process.env.VERIFY_BASE ?? 'http://127.0.0.1:5173'
  const browser = await chromium.launch({ executablePath: process.env.PLAYWRIGHT_CHROMIUM ?? 'C:/Program Files/Google/Chrome/Application/chrome.exe' })
  let pass = 0
  const check = (name, condition) => { assert.ok(condition, name); pass++; console.log(`通过：${name}`) }
  const quote = '钙与维生素D联合使用。'
  const content = `${quote}\n😀这是第二段。${quote}\n结尾。`
  const start = content.lastIndexOf(quote)
  const common = { batchId: 'batch-A', docNo: 'KB-001', entityKey: 'SPU7', stage: 'VALIDATION', reasonCode: 'UNANCHORED', detail: '候选端点没有被正文锚定', retryable: false, rawCandidate: 'PRODUCT SPU7 -> supports -> INGREDIENT 钙', normalizedHeadKind: 'PRODUCT', normalizedHead: 'SPU7', normalizedTailKind: 'INGREDIENT', normalizedTail: 'calcium', relation: 'supports', quote, quoteOffsetStart: start, quoteOffsetEnd: start + quote.length, aliasHit: true, chunkId: 'KB-001_0', occurredAt: '2026-10-11T10:00:00' }
  const records = [
    { ...common, id: 1 },
    { ...common, id: 2, batchId: 'batch-B', stage: 'EXTRACT', reasonCode: 'EXTRACT_FAILED', aliasHit: false },
    { ...common, id: 3, docNo: 'KB-002', entityKey: 'SPU8', normalizedHead: 'SPU8', normalizedTail: 'iron', relation: 'contains', stage: 'WRITE' },
    { ...common, id: 4, stage: 'BATCH', reasonCode: 'BATCH_SKIPPED' },
  ]
  const requests = []
  const unexpected = []
  const errors = []
  let failNext = false
  let holdNext = false
  let releaseHeld
  try {
    const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } })
    page.on('pageerror', e => errors.push(e.message))
    await page.route(/\/src\/main\.ts(?:\?.*)?$/, route => route.fulfill({ contentType: 'application/javascript', body: 'document.getElementById("app").id="fixture";' }))
    await page.route('**/*', async route => {
      const url = new URL(route.request().url())
      if (!/^\/(ai|auth|cart|tickets|products|favorites|orders|reviews|payments|knowledge)(\/|$)/.test(url.pathname)) return route.fallback()
      const reply = data => route.fulfill({ json: { code: 200, data }, headers: { 'access-control-allow-origin': '*' } })
      if (url.pathname === '/knowledge/admin/graph/failures') {
        requests.push(Object.fromEntries(url.searchParams))
        if (holdNext) { holdNext = false; await new Promise(resolve => { releaseHeld = resolve }) }
        if (failNext) { failNext = false; return route.fulfill({ json: { code: 503, msg: '诊断读取失败' }, headers: { 'access-control-allow-origin': '*' } }) }
        return reply(records.filter(row => ['batchId', 'docNo', 'stage', 'reasonCode', 'entityKey', 'relation'].every(key => !url.searchParams.get(key) || (key === 'entityKey' ? [row.entityKey, row.normalizedHead, row.normalizedTail].includes(url.searchParams.get(key)) : row[key] === url.searchParams.get(key)))))
      }
      if (url.pathname === '/knowledge/admin/graph/failures/stats') {
        const rows = records.filter(row => !url.searchParams.get('batchId') || row.batchId === url.searchParams.get('batchId'))
        const counts = key => rows.reduce((map, row) => ({ ...map, [row[key]]: (map[row[key]] ?? 0) + 1 }), {})
        return reply({ failed: rows.length, retryable: 0, batches: new Set(rows.map(row => row.batchId)).size, byStage: counts('stage'), byReason: counts('reasonCode') })
      }
      if (url.pathname === '/knowledge/documents/KB-001') return reply({ docNo: 'KB-001', title: '诊断原文', source: 'manual', scope: 'nutrition', version: 'v1', status: 1, content, tags: null, updatedAt: '2026-10-11', chunks: [{ chunkId: 'KB-001_0', chunkIndex: 0, position: '正文', charOffset: 0, charEnd: content.length }] })
      unexpected.push(`${route.request().method()} ${url.pathname}`)
      return route.fulfill({ status: 500, json: { code: 500, msg: '禁止业务请求' }, headers: { 'access-control-allow-origin': '*' } })
    })
    await page.goto(base, { waitUntil: 'networkidle' })
    await page.evaluate(async () => {
      const source = await fetch('/src/views/admin/AdminGraphFailuresView.vue').then(r => r.text())
      const vueUrl = source.match(/from "([^"\n]*\/vue\.js\?v=[^"]+)"/)?.[1] ?? '/node_modules/.vite/deps/vue.js'
      const routerSource = await fetch('/src/router/index.ts').then(r => r.text())
      const routerUrl = routerSource.match(/from "([^"\n]*\/vue-router\.js\?v=[^"]+)"/)?.[1] ?? '/node_modules/.vite/deps/vue-router.js'
      const { createApp, h } = await import(vueUrl)
      const { createRouter, createMemoryHistory, RouterView } = await import(routerUrl)
      const { default: pinia } = await import('/src/stores/index.ts')
      const { default: Diagnostics } = await import('/src/views/admin/AdminGraphFailuresView.vue')
      const { default: Document } = await import('/src/views/KnowledgeDocView.vue')
      await import('/node_modules/element-plus/theme-chalk/index.css')
      await import('/src/styles/index.css')
      const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', component: Diagnostics }, { path: '/knowledge/:docNo', name: 'knowledge-doc', component: Document }] })
      await router.push('/')
      createApp({ render: () => h(RouterView) }).use(pinia).use(router).mount('#fixture')
      window.graphFixtureRouter = router
    })
    const tableRows = page.locator('.el-table__body tbody tr.el-table__row')
    check('初始读取四条真实组件诊断记录', await poll(() => tableRows.count(), n => n === 4) === 4)
    check('三层职责明确展示', (await page.locator('.explain-grid').innerText()).includes('Neo4j 保存通过事实'))
    const summaryText = await page.locator('.summary').innerText()
    check('阶段分布展示抽取、校验、写入与批次跳过', ['抽取失败', '事实校验拒绝', '写入失败', '批次跳过', 'EXTRACT_FAILED', 'BATCH_SKIPPED'].every(text => summaryText.includes(text)))
    await page.getByRole('textbox', { name: '构建批次', exact: true }).fill(' batch-A ')
    await page.getByRole('textbox', { name: '文档编号', exact: true }).fill('KB-001')
    await page.getByRole('combobox', { name: '阶段', exact: true }).focus()
    await page.keyboard.press('ArrowDown')
    await page.getByRole('option', { name: '事实校验拒绝 · VALIDATION', exact: true }).click()
    await page.getByRole('combobox', { name: '原因码', exact: true }).focus()
    await page.keyboard.press('ArrowDown')
    await page.getByRole('option', { name: 'UNANCHORED', exact: true }).click()
    await page.getByRole('textbox', { name: '实体', exact: true }).fill('calcium')
    await page.getByRole('textbox', { name: '关系', exact: true }).fill('supports')
    await page.getByRole('button', { name: '筛选', exact: true }).click()
    check('六项筛选命中规范化尾端点', await poll(() => tableRows.count(), n => n === 1) === 1)
    check('参数真实发送且批次去除外围空白', ['batchId', 'docNo', 'stage', 'reasonCode', 'entityKey', 'relation'].every(key => requests.at(-1)[key] === ({ batchId: 'batch-A', docNo: 'KB-001', stage: 'VALIDATION', reasonCode: 'UNANCHORED', entityKey: 'calcium', relation: 'supports' })[key]))
    check('批次汇总不会被文档实体筛选缩为一条', (await page.locator('.explain-grid').innerText()).includes('记录 3 条'))
    await tableRows.first().locator('.el-table__expand-icon').click()
    const detail = page.locator('.detail-box')
    await detail.waitFor()
    const detailText = await detail.innerText()
    check('详情完整展示候选、双端点、关系、引文与定位', [common.rawCandidate, 'PRODUCT', 'INGREDIENT', 'calcium', quote, `${start} → ${start + quote.length}`, '别名命中', '是', common.chunkId, 'batch-A', 'VALIDATION / UNANCHORED'].every(text => detailText.includes(text)))
    const link = detail.getByRole('link', { name: '查看原文并核对引文' })
    const href = await link.getAttribute('href')
    const query = new URL(href, base).searchParams
    check('原文链接复用真实文档路由与切片、quote、offset', href.startsWith('/knowledge/KB-001?') && query.get('quote') === quote && query.get('start') === String(start) && query.get('end') === String(start + quote.length) && query.get('chunk') === common.chunkId)
    mkdirSync('.screenshots', { recursive: true })
    await page.screenshot({ path: '.screenshots/graph-diagnostics-ui.png', fullPage: true })
    await link.click()
    await page.locator('mark.doc__mark').waitFor()
    check('重复引文按UTF-16偏移精确高亮第二处，包含emoji仍不偏移', await page.locator('mark.doc__mark').innerText() === quote && await page.locator('.doc-content > span').first().textContent() === content.slice(0, start))
    await page.evaluate(() => window.graphFixtureRouter.replace({ query: { quote: '不存在的引文', start: '0', end: '7' } }))
    await page.getByRole('alert').waitFor()
    check('不存在或过期引文明确报警且禁止错误高亮', await page.locator('mark.doc__mark').count() === 0)
    await page.evaluate(quote => window.graphFixtureRouter.replace({ query: { quote } }), quote)
    check('缺偏移的重复引文不猜第一处', await poll(() => page.locator('mark.doc__mark').count(), n => n === 0) === 0)
    await page.evaluate(() => window.graphFixtureRouter.replace({ query: { quote: '结尾。' } }))
    await page.locator('mark.doc__mark').waitFor()
    check('历史无偏移候选唯一逐字命中才定位', await page.locator('mark.doc__mark').innerText() === '结尾。')
    await page.locator('.doc-toc__item').click()
    check('切片目录清除候选定位并恢复整片高亮', await poll(() => page.locator('mark.doc__mark').textContent(), text => text === content) === content)
    await page.evaluate(() => window.graphFixtureRouter.push('/'))
    await page.getByRole('button', { name: '清空筛选' }).click()
    check('清空筛选恢复全部记录', await poll(() => tableRows.count(), n => n === 4) === 4)
    check('清空后的请求不残留任何筛选', Object.keys(requests.at(-1)).length === 1 && requests.at(-1).limit === '5000')
    await page.getByRole('textbox', { name: '关系', exact: true }).fill('不存在')
    await page.getByRole('button', { name: '筛选', exact: true }).click()
    await page.getByText('暂无诊断记录', { exact: true }).waitFor()
    check('无匹配展示明确空态', await tableRows.count() === 0)
    failNext = true
    await page.getByRole('button', { name: '清空筛选' }).click()
    await page.getByText('诊断读取失败', { exact: true }).first().waitFor()
    check('接口失败不会把旧数据展示成最新结果', await page.locator('.el-table').count() === 0)
    await page.getByRole('button', { name: '刷新', exact: true }).click()
    check('刷新可从错误恢复', await poll(() => tableRows.count(), n => n === 4) === 4)
    holdNext = true
    await page.getByRole('button', { name: '刷新', exact: true }).click()
    await poll(() => Boolean(releaseHeld), value => value)
    await page.evaluate(() => window.graphFixtureRouter.push({ name: 'knowledge-doc', params: { docNo: 'KB-001' } }))
    releaseHeld()
    await page.locator('.doc-content').waitFor()
    check('页面离开后的迟到诊断响应不覆盖原文', await page.locator('.admin-page').count() === 0)
    check('真实业务请求全被mock隔离且无页面异常', unexpected.length === 0 && errors.length === 0)
    console.log(`图谱诊断确定性回归：${pass} 通过 / 0 失败；真实业务请求 0 次。`)
  } finally { await browser.close() }
  return pass
}
