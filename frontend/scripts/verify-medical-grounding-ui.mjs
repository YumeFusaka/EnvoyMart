import assert from 'node:assert/strict'
import { mkdirSync } from 'node:fs'
import { chromium } from 'playwright-core'

const base = process.env.VERIFY_BASE ?? 'http://localhost:5173'
const gateway = process.env.VERIFY_GW ?? 'http://localhost:8080'
const login = await fetch(`${gateway}/auth/login`, { method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ username: 'alice', password: '123456' }) }).then((response) => response.json())
assert.equal(login.code, 200, '已有测试账号应能登录')
const browser = await chromium.launch({
  executablePath: process.env.PLAYWRIGHT_CHROMIUM ?? 'C:/Program Files/Google/Chrome/Application/chrome.exe',
})

try {
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } })
  const page = await context.newPage()
  const errors = []
  page.on('pageerror', (error) => errors.push(error.message))
  await page.addInitScript((session) => {
    localStorage.setItem('user', JSON.stringify({ token: session.token, profile: session.user }))
  }, login.data)
  await page.route('**/ai/sessions**', (route) => route.fulfill({
    json: { code: 200, data: [], msg: null },
  }))
  const replies = ['', '当前检索证据不足，请咨询医师或药师并遵医嘱安排用药。']
  let turns = 0
  await page.route('**/ai/chat/stream', (route) => {
    const reply = replies[turns++]
    assert.notEqual(reply, undefined, '只应发起两次确定性对话')
    return route.fulfill({
      contentType: 'text/event-stream',
      body: 'event: delta\ndata: 维生素D3每日上限为9000IU [1]。\n\n'
        + 'event: done\ndata: ' + JSON.stringify({ reply, knowledge: [], toolCalls: [],
          unsupportedClaims: ['维生素D3每日上限为9000IU [1]。'], unsupportedStripped: true }) + '\n\n',
    })
  })
  await page.goto(`${base}/#/assistant`, { waitUntil: 'networkidle' })
  for (const [index, expected] of replies.entries()) {
    await page.locator('.composer textarea').fill('维生素D3每天吃多少？')
    await page.getByRole('button', { name: /发送消息/ }).click()
    const card = page.locator('.message-card.assistant').nth(index)
    await card.waitFor()
    await page.waitForFunction(() => !document.querySelector('.message-live'))
    const content = card.locator('.message-content')
    const actual = await content.count() ? (await content.innerText()).trim() : ''
    assert.equal(actual, expected, '最终正文必须使用服务端校验结果')
    assert.equal(actual.includes('9000IU'), false, '不能恢复被剔除的旧 delta')
  }
  assert.equal(turns, 2)
  assert.deepEqual(errors, [])
  mkdirSync('.screenshots', { recursive: true })
  await page.screenshot({ path: '.screenshots/medical-grounding-ui.png', fullPage: true })
  console.log('6 通过 / 0 失败：空正文、安全拒答、危险正文剔除、请求次数和页面异常核对')
} finally {
  await browser.close()
}
