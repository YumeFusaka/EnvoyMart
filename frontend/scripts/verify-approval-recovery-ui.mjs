/** 审批恢复纯浏览器夹具：只查原回执、明确继续原参数、刷新原身份，不调用模型。 */
import assert from 'node:assert/strict'
import { mkdirSync } from 'node:fs'
import { resolve } from 'node:path'
import { chromium } from 'playwright-core'

const base = process.env.VERIFY_BASE ?? 'http://localhost:5173'
const browser = await chromium.launch({ executablePath: process.env.PLAYWRIGHT_CHROMIUM ?? 'C:/Program Files/Google/Chrome/Application/chrome.exe' })
const now = new Date().toISOString()
const originalToken = 'original-signed-batch'
const original = { id: 'a-original', role: 'assistant', content: '原操作结果待确认', at: new Date(Date.now() - 3600000).toISOString(), response: {
  sessionId: 'recover-session', turnId: 'turn-original', userMessageId: 'u-original', assistantMessageId: 'a-original', reply: '原操作结果待确认',
  pendingActions: ['ticket_create(category=ORDER,title=原申请,content=原问题)'], pendingActionDetails: [{ tool: 'ticket_create', arguments: { category: 'ORDER', title: '原申请', content: '原问题' } }],
  approvalToken: originalToken, approvalStatus: 'UNKNOWN', approvalRetryAllowed: true, toolCalls: [], knowledge: [],
  approvalRecoverySeconds: 86400, approvalRecoveryUntil: Date.now() + 23 * 3600000,
} }
const history = [
  { id: 'u-original', role: 'user', content: '请提交原申请', at: original.at, response: null }, original,
  { id: 'u-new', role: 'user', content: '后续普通问题', at: now, response: null },
  { id: 'a-new', role: 'assistant', content: '后续普通回答', at: now, response: { reply: '后续普通回答', assistantMessageId: 'a-new' } },
]
let count = 0
let recordDamaged = false
const requests = []
const forbidden = []
const errors = []
const check = (condition, message) => { assert.ok(condition, message); count++ }
try {
  const context = await browser.newContext({ viewport: { width: 1440, height: 1200 } })
  await context.addInitScript(() => localStorage.setItem('user', JSON.stringify({ token: 'mock-auth', profile: { id: 'mock-user', username: '夹具用户' } })))
  await context.route('**/*', async (route) => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (!/^\/(ai|auth|cart|tickets|products|favorites|orders|promotions|reviews|payments)(\/|$)/.test(path)) return route.continue()
    const fulfill = (data) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 200, data }) })
    if (request.method() === 'GET') {
      if (path === '/ai/sessions') return fulfill([{ sessionId: 'recover-session', title: '恢复原操作', createdAt: original.at, updatedAt: now, messageCount: history.length }])
      if (path.endsWith('/messages')) return fulfill(history)
      return fulfill([])
    }
    if (path === '/ai/sessions/recover-session/approvals/recover') {
      const payload = request.postDataJSON(); requests.push(payload)
      assert.deepEqual(Object.keys(payload).sort(), ['approvalToken', 'retry'])
      assert.equal(payload.approvalToken, originalToken)
      if (payload.retry) {
        original.response.approvalStatus = 'CONFIRMED'; original.response.approvalRetryAllowed = false
        original.content = '工单原首次回执 T-001'
      }
      return fulfill({ ...original.response, reply: original.content, approvalRecordAvailable: !recordDamaged,
        approvalRetryAllowed: recordDamaged ? false : original.response.approvalRetryAllowed,
        approvalRecordError: recordDamaged ? '原审批授权记录无法读取，仅查询首次回执；继续确认已禁用。' : null,
        assistantMessageId: recordDamaged ? null : original.id,
        toolCalls: [{ tool: 'ticket_create', operationId: 'original-op', operationState: payload.retry || original.response.approvalStatus === 'CONFIRMED' ? 'COMMITTED' : 'NOT_FOUND', receiptReplayed: true, success: !!payload.retry, output: payload.retry ? '工单原首次回执 T-001' : '结果待确认', latencyMs: 1 }] })
    }
    forbidden.push(request.method() + ' ' + path)
    return route.abort('blockedbyclient')
  })
  const page = await context.newPage()
  page.on('pageerror', (error) => errors.push(error.message))
  await page.goto(base + '/#/assistant', { waitUntil: 'networkidle' })
  const card = page.locator('.approval')
  await card.getByRole('button', { name: '查询执行结果', exact: true }).waitFor()
  check((await card.innerText()).includes('执行结果待确认'), '较早原批次刷新后可查询')
  check((await card.innerText()).includes('原操作继续确认截止'), '已接受原批次展示恢复截止时间')
  check(await card.getByRole('button', { name: '确认执行', exact: true }).count() === 0, '过期历史不能按正常首次确认写')
  await card.getByRole('button', { name: '查询执行结果', exact: true }).click()
  await page.waitForFunction(() => !document.querySelector('.approval')?.hasAttribute('inert'))
  check(requests.length === 1 && requests[0].retry === false, '查询仅提交原token queryOnly')
  check((await card.innerText()).includes('执行结果待确认'), 'NOT_FOUND不伪装成功')
  await card.getByRole('button', { name: '继续确认（原参数不变）', exact: true }).click()
  await page.getByRole('button', { name: '继续确认', exact: true }).click()
  await page.getByText('工单原首次回执 T-001', { exact: true }).first().waitFor()
  check(requests.length === 2 && requests[1].retry === true, '明确继续确认使用相同原token')
  check(await card.getByRole('button', { name: '继续确认（原参数不变）', exact: true }).count() === 0, '完成后只读查询')
  await page.reload({ waitUntil: 'networkidle' })
  await card.getByText('已提交确认的操作', { exact: true }).waitFor()
  check(await card.getByRole('button', { name: '查询执行结果', exact: true }).count() === 1, '完成原卡刷新可查首次回执')
  check((await card.innerText()).includes('原申请'), '恢复参数仍为原申请')
  check((await page.locator('body').innerText()).includes('后续普通回答'), '恢复不覆盖后续普通消息')
  original.response.approvalStatus = 'UNKNOWN'; original.response.approvalRetryAllowed = true
  await page.reload({ waitUntil: 'networkidle' })
  check(await card.getByRole('button', { name: '继续确认（原参数不变）', exact: true }).count() === 1, '账本损坏前未知批次具备明确继续确认入口')
  recordDamaged = true
  await card.getByRole('button', { name: '查询执行结果', exact: true }).click()
  await card.getByRole('alert').waitFor()
  check((await card.getByRole('alert').innerText()).includes('授权记录无法读取'), '记录损坏恢复保留公开错误语义')
  check(await card.getByRole('button', { name: '继续确认（原参数不变）', exact: true }).count() === 0, '记录不可读不能恢复写权限')
  recordDamaged = false
  original.response.approvalStatus = 'CANCELLED'; original.response.approvalRetryAllowed = false
  await page.reload({ waitUntil: 'networkidle' })
  await card.getByText('已停止后续操作', { exact: true }).waitFor()
  check(await card.getByRole('button', { name: '继续确认（原参数不变）', exact: true }).count() === 0, '取消不能继续原批次写')
  check(await card.getByRole('button', { name: '查询执行结果', exact: true }).count() === 1, '取消仍能查询已提交动作')
  history.splice(2)
  original.at = new Date().toISOString()
  original.response.approvalStatus = 'PENDING'
  original.response.approvalRecoveryUntil = null
  await page.reload({ waitUntil: 'networkidle' })
  await card.getByRole('button', { name: '确认执行', exact: true }).waitFor()
  check((await card.innerText()).includes('请在10分钟内首次确认；接受后24小时内可按原参数继续操作'), '新签名确认卡公开首次与接受后期限')
  original.response.approvalRecoverySeconds = 0
  await page.reload({ waitUntil: 'networkidle' })
  check(!(await card.innerText()).includes('接受后24小时'), '老签名不显示未经授权的24小时恢复权')
  assert.deepEqual(errors, []); assert.deepEqual(forbidden, []); count += 2
  mkdirSync('.screenshots', { recursive: true })
  await page.screenshot({ path: resolve('.screenshots/approval-recovery-original-batch.png') })
  console.log(`通过 ${count} 项原批次恢复 UI 验收，模型调用 0 次`)
} finally { await browser.close() }
