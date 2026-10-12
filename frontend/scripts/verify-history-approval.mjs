/**
 * 历史确认卡的确定性浏览器验收：真实页面加载接口 fixture，不依赖用户既存会话。
 * 只验证前端恢复、状态与请求契约；服务端令牌验签/交易执行由 approval-chain 验收。
 * 不登录、不调用模型、不写后端数据。前置：前端已启动。
 * 用法：node scripts/verify-history-approval.mjs
 */
import assert from 'node:assert/strict'
import { mkdirSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { chromium } from 'playwright-core'
import { poll } from './lib/verify-util.mjs'

const BASE = process.env.VERIFY_BASE ?? 'http://localhost:5173'
const OUT_DIR = resolve(dirname(fileURLToPath(import.meta.url)), '../.screenshots')
const now = new Date().toISOString()
const details = [
  { tool: 'cart_add', confirmable: true, arguments: {
    skuId: 71001, productName: '历史夹具叶黄素', specification: '30 粒装',
    quantity: 2, unitPrice: 12900, subtotal: 25800,
  } },
  { tool: 'cart_add', confirmable: true, arguments: {
    skuId: 71002, productName: '历史夹具维生素', specification: null,
    quantity: 1, unitPrice: 4900, subtotal: 4900,
  } },
]
function fixture(key, title, status = 'PENDING', { token = true, expired = false } = {}) {
  const sessionId = `history-fixture-${key}`
  const turnId = `turn-${key}`
  const at = expired ? new Date(Date.now() - 660000).toISOString() : now
  const userId = `persisted-question-${key}`
  const assistantId = `persisted-answer-${key}`
  return {
    key, title, sessionId, assistantId,
    messages: [
      { id: userId, turnId, role: 'user', content: '请核对这两件商品的加购内容', at, response: null },
      { id: assistantId, turnId, role: 'assistant', content: '请核对操作内容。', at,
        response: {
          sessionId, turnId, userMessageId: userId, assistantMessageId: assistantId,
          reply: '请核对操作内容。', knowledge: [], toolCalls: [], recommendedProducts: [],
          pendingActions: details.map((item) => `cart_add(skuId=${item.arguments.skuId},quantity=${item.arguments.quantity})`),
          pendingActionDetails: structuredClone(details),
          approvalToken: token ? `fixture-approval-${key}` : null,
          approvalStatus: status,
        },
      },
    ],
  }
}
const fixtures = [
  fixture('approve', '夹具：等待确认'),
  fixture('dismiss', '夹具：等待取消'),
  // 保留令牌，证明终态本身就足以禁用按钮。
  fixture('confirmed', '夹具：已提交确认', 'CONFIRMED'),
  fixture('dismissed', '夹具：已取消', 'DISMISSED'),
  fixture('expired', '夹具：令牌过期', 'PENDING', { expired: true }),
  fixture('no-token', '夹具：无令牌', 'HISTORY', { token: false }),
]
let pass = 0
let holdDismiss = false
let heldDismiss = null
let holdHistory = false
let heldHistory = null
let failDismiss = false
function ck(name, condition) {
  assert.ok(condition, name)
  pass++
  console.log(`通过：${name}`)
}

const browser = await chromium.launch({
  executablePath: process.env.PLAYWRIGHT_CHROMIUM ?? 'C:/Program Files/Google/Chrome/Application/chrome.exe',
  headless: true,
})
try {
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } })
  const page = await context.newPage()
  const problems = []
  const reads = []
  const writes = []
  page.on('pageerror', (error) => problems.push(error.message))
  page.on('console', (message) => {
    if (message.type() === 'error') problems.push(message.text())
  })
  await context.addInitScript(() => {
    localStorage.setItem('user', JSON.stringify({
      token: 'fixture-auth-token', profile: { id: 'history-fixture-user', username: '历史夹具用户' },
    }))
  })
  // 只拦截 API 路径；未列出的 API 一律失败，避免夹具令牌/写请求漏到真实服务。
  await context.route('**/*', async (route) => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (!/^\/(?:ai|auth|cart|tickets|products|favorites|orders|promotions|reviews|payments)(?:\/|$)/.test(path)) {
      await route.continue()
      return
    }
    const json = async (data) => route.fulfill({
      status: 200, contentType: 'application/json', body: JSON.stringify({ code: 200, msg: '成功', data }),
    })
    const method = request.method()
    if (method === 'GET' && path === '/ai/sessions') {
      await json(fixtures.map((item) => ({
        sessionId: item.sessionId, title: item.title, createdAt: now, updatedAt: now, messageCount: item.messages.length,
      })))
      return
    }
    const history = /^\/ai\/sessions\/([^/]+)\/(messages|bad-cases)$/.exec(path)
    const item = history && fixtures.find((entry) => entry.sessionId === decodeURIComponent(history[1]))
    if (method === 'GET' && item) {
      if (history[2] === 'messages' && item === fixtures[1] && holdHistory) { heldHistory = route; return }
      if (history[2] === 'messages') reads.push(item.sessionId)
      await json(history[2] === 'messages' ? item.messages : [])
      return
    }
    if (method === 'GET' && path === '/cart') { await json([]); return }
    if (method === 'GET' && path === '/products/hot-keywords') { await json([]); return }
    if (method === 'GET' && path === '/tickets/summary') {
      await json({ all: 0, open: 0, inProgress: 0, resolved: 0, closed: 0, awaitingMe: 0 })
      return
    }
    if (method === 'POST' && path === '/ai/chat/stream') {
      const body = request.postDataJSON()
      writes.push({ path, body })
      const target = fixtures[0]
      const pending = target.messages[1].response
      assert.equal(body.sessionId, target.sessionId, '确认请求必须属于装载的历史会话')
      assert.equal(body.approvalToken, pending.approvalToken, '确认请求原样携带历史令牌')
      assert.equal(body.message, '确认执行')
      pending.approvalStatus = 'CONFIRMED'
      pending.approvalToken = null
      const response = {
        sessionId: target.sessionId, turnId: 'fixture-confirmed-turn',
        userMessageId: 'persisted-confirm-question', assistantMessageId: 'persisted-confirm-answer',
        reply: '夹具已确认，不执行真实交易。', knowledge: [], toolCalls: [], recommendedProducts: [],
        pendingActions: [], pendingActionDetails: [], approvalToken: null,
      }
      target.messages.push(
        { id: response.userMessageId, turnId: response.turnId, role: 'user', content: body.message, at: now, response: null },
        { id: response.assistantMessageId, turnId: response.turnId, role: 'assistant', content: response.reply, at: now, response },
      )
      await route.fulfill({ status: 200, contentType: 'text/event-stream', body: `event: done\ndata: ${JSON.stringify(response)}\n\n` })
      return
    }
    const dismiss = `/ai/sessions/${fixtures[1].sessionId}/messages/${fixtures[1].assistantId}/dismiss-approval`
    if (method === 'POST' && path === dismiss) {
      if (failDismiss) {
        await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 503, msg: '取消未完成', data: null }) })
        return
      }
      if (holdDismiss) { heldDismiss = route; return }
      writes.push({ path })
      const message = fixtures[1].messages[1]
      message.response.approvalToken = null
      message.response.approvalStatus = 'DISMISSED'
      message.content = '已取消，本次没有执行任何操作。'
      await json(null)
      return
    }
    problems.push(`未定义的夹具请求：${method} ${path}`)
    await route.fulfill({ status: 500, contentType: 'application/json', body: JSON.stringify({ code: 500, msg: '未定义的夹具请求' }) })
  })

  const card = page.locator('.approval')
  async function select(item) {
    const before = reads.filter((id) => id === item.sessionId).length
    await page.locator('.session-list__main').filter({ hasText: item.title }).click()
    await page.waitForFunction((title) => document.querySelector('.chat-header__title strong')?.textContent === title, item.title)
    await card.waitFor()
    ck(`${item.title}通过历史接口重新载入`, reads.filter((id) => id === item.sessionId).length > before)
  }
  async function checkDetails(name) {
    assert.equal(await card.count(), 1, name)
    assert.deepEqual(await card.locator('.approval__product').allTextContents(), ['历史夹具叶黄素', '历史夹具维生素'], name)
    const rows = await card.locator('.approval__args dd').allTextContents()
    assert.deepEqual(rows, ['30 粒装', '2', '¥129.00', '¥258.00', '单一规格', '1', '¥49.00', '¥49.00'], name)
    ck(name, await card.locator('.approval__notice').count() === 0)
  }
  async function checkStale(title, name) {
    await page.getByText(title, { exact: true }).waitFor()
    ck(name, await card.getByRole('button', { name: '确认执行', exact: true }).count() === 0
      && await card.getByRole('button', { name: '继续确认（原参数不变）', exact: true }).count() === 0)
    await checkDetails(`${name}仍保留完整参数`)
  }

  await page.goto(`${BASE}/#/assistant`, { waitUntil: 'networkidle' })
  await card.getByRole('button', { name: '确认执行', exact: true }).waitFor()
  await checkDetails('历史 pendingActionDetails 还原商品、规格、数量与金额')
  const before = await card.innerText()
  await select(fixtures[2])
  await checkStale('已提交确认的操作', '已确认历史卡不可执行（即使仍携带令牌）')
  await select(fixtures[0])
  ck('会话回切后参数与可执行状态不变', await card.innerText() === before)
  const beforeReload = reads.filter((id) => id === fixtures[0].sessionId).length
  await page.reload({ waitUntil: 'networkidle' })
  await card.getByRole('button', { name: '确认执行', exact: true }).waitFor()
  ck('刷新后由历史接口恢复相同会话和参数',
    reads.filter((id) => id === fixtures[0].sessionId).length > beforeReload && await card.innerText() === before)
  await card.getByRole('button', { name: '确认执行', exact: true }).click()
  await page.getByText('夹具已确认，不执行真实交易。', { exact: true }).waitFor()
  ck('确认仅提交一次且保留历史令牌与会话身份', writes.length === 1)
  await page.reload({ waitUntil: 'networkidle' })
  await checkStale('已提交确认的操作', '确认后刷新历史卡不能重复执行')

  await select(fixtures[1])
  failDismiss = true
  await card.getByRole('button', { name: '取消', exact: true }).click()
  await page.getByText('取消失败，已按服务端状态恢复，请重试。', { exact: true }).waitFor()
  ck('取消失败复读后恢复原审批按钮并给局部反馈', await card.getAttribute('aria-busy') === 'false' && await card.getByRole('button', { name: '取消', exact: true }).isVisible())
  ck('局部处理取消失败不产生全局错误toast', await page.locator('.el-message--error').count() === 0)
  failDismiss = false
  holdHistory = true
  await card.getByRole('button', { name: '取消', exact: true }).click()
  await poll(() => heldHistory, Boolean, { timeoutMs: 3000, intervalMs: 20 })
  assert.ok(heldHistory)
  ck('取消期间仅原审批卡忙碌且不能重复确认', await card.getAttribute('aria-busy') === 'true')
  await select(fixtures[0])
  holdHistory = false
  await heldHistory.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 503, msg: '旧会话取消查询失败', data: null }) })
  heldHistory = null
  await page.waitForFunction(() => document.querySelector('.approval__title')?.textContent === '已提交确认的操作')
  ck('旧历史查询回来后不发取消POST且不向新会话弹错误', writes.length === 1 && await page.locator('.el-message--error').count() === 0)
  await select(fixtures[1])
  holdDismiss = true
  await card.getByRole('button', { name: '取消', exact: true }).click()
  await poll(() => heldDismiss, Boolean, { timeoutMs: 3000, intervalMs: 20 })
  assert.ok(heldDismiss)
  await select(fixtures[0])
  ck('取消请求等待期间切会话不会写入新会话', heldDismiss?.request().url().includes(`/ai/sessions/${fixtures[1].sessionId}/messages/${fixtures[1].assistantId}/dismiss-approval`) === true && writes.length === 1)
  holdDismiss = false
  writes.push({ path: `/ai/sessions/${fixtures[1].sessionId}/messages/${fixtures[1].assistantId}/dismiss-approval` })
  const dismissedMessage = fixtures[1].messages[1]
  dismissedMessage.response.approvalToken = null
  dismissedMessage.response.approvalStatus = 'DISMISSED'
  dismissedMessage.content = '已取消，本次没有执行任何操作。'
  await heldDismiss.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 200, msg: '成功', data: null }) })
  heldDismiss = null
  ck('原session取消请求可完成但不回写新session消息', (await card.locator('.approval__title').textContent()) === '已提交确认的操作')
  await select(fixtures[1])
  ck('原会话取消响应回来后仍可重新读取状态', await card.count() === 1)
  await checkStale('已停止后续操作', '取消当前历史卡后不可执行')
  ck('dismiss 使用服务端历史的权威消息 id', writes[1]?.path ===
    `/ai/sessions/${fixtures[1].sessionId}/messages/${fixtures[1].assistantId}/dismiss-approval`)
  await page.reload({ waitUntil: 'networkidle' })
  await checkStale('已停止后续操作', '取消后刷新历史卡仍不可执行')
  await select(fixtures[3])
  await checkStale('已停止后续操作', '已取消历史状态优先于残留令牌')
  for (const item of fixtures.slice(4)) {
    await select(item)
    await checkStale('操作确认记录', `${item.title}不可执行`)
  }
  ck('无额外交易/对话请求', writes.length === 2)
  assert.deepEqual(problems, [], '页面异常或未定义 API 请求')
  ck('无页面异常或未定义 API 请求', true)
  mkdirSync(OUT_DIR, { recursive: true })
  await page.screenshot({ path: resolve(OUT_DIR, 'approval-history-review.png'), fullPage: false })
  fixtures[1].messages[1].response.approvalToken = 'fixture-approval-dismiss'
  fixtures[1].messages[1].response.approvalStatus = 'PENDING'
  await select(fixtures[1])
  holdHistory = true
  await card.getByRole('button', { name: '取消', exact: true }).click()
  await poll(() => heldHistory, Boolean, { timeoutMs: 3000, intervalMs: 20 })
  assert.ok(heldHistory)
  await page.evaluate(() => document.querySelector('#app').__vue_app__.unmount())
  holdHistory = false
  await heldHistory.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 503, msg: '卸载后旧取消查询失败', data: null }) })
  heldHistory = null
  ck('卸载后迟到取消查询不发POST、不弹旧错误或未捕获异常', writes.length === 2 && await page.locator('.el-message--error').count() === 0 && problems.length === 0)
  console.log(`历史确认浏览器夹具验收：${pass} 项通过，未调用模型或修改后端数据`)
} finally {
  await browser.close()
}
