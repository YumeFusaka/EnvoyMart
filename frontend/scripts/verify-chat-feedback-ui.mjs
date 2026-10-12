/** 真实组件的确定性浏览器回归；所有反馈请求均拦截，不调用模型、不写后端数据。 */
import assert from 'node:assert/strict'
import { mkdirSync } from 'node:fs'
import { resolve } from 'node:path'
import { chromium } from 'playwright-core'
import { poll } from './lib/verify-util.mjs'

const base = process.env.VERIFY_BASE ?? 'http://127.0.0.1:5173'
const browser = await chromium.launch({ executablePath: process.env.PLAYWRIGHT_CHROMIUM ?? 'C:/Program Files/Google/Chrome/Application/chrome.exe' })
const page = await browser.newPage({ viewport: { width: 1200, height: 900 } })
const errors = []
let viteConnected = false
page.on('console', (message) => { if (message.text().includes('[vite] connected.')) viteConnected = true })
page.on('pageerror', (error) => { if (!errors.includes(error.message)) { errors.push(error.message); console.error(`页面异常：${error.stack}`) } })
let calls = []
let pending = []
let mode = 'empty'
let active = 'a-1'
let passed = 0
const check = (name, value) => { assert.ok(value, name); console.log(`通过：${name}`); passed++ }
const reply = (route, data = [], code = 200) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code, msg: code === 200 ? '成功' : '模拟反馈失败', data }) })
async function release(data = [], code = 200) {
  await poll(() => pending.length, (count) => count > 0, { timeoutMs: 3000, intervalMs: 20 })
  assert.ok(pending.length, '预期的延迟反馈请求已到达')
  const route = pending.shift()
  const response = page.waitForResponse((value) => value.url() === route.request().url() && value.request().method() === route.request().method())
  await reply(route, data, code)
  await (await response).finished()
  await page.evaluate(() => window.feedbackFixture.nextTick())
}
await page.route('**/ai/sessions/**', async (route) => {
  const method = route.request().method()
  calls.push({ method, url: route.request().url() })
  if (mode === 'held') { pending.push(route); return }
  if (mode === 'failure') { await reply(route, null, 503); return }
  await reply(route, method === 'GET' && mode === 'active' ? [{ assistantMessageId: active, status: 'ACTIVE' }] : [])
})
// HTML 从真实开发服务器加载，保留正常的 Vite 连接；只替换应用入口，避免页面加载触发业务请求。
await page.route(/\/src\/main\.ts(?:\?.*)?$/, (route) => route.fulfill({ contentType: 'application/javascript', body: 'document.getElementById("app").id = "fixture";' }))
try {
  const ready = await fetch(`${base}/src/components/ai/ChatMessageList.vue`).catch(() => null)
  assert.ok(ready?.ok, `需要可访问的 Vite 开发服务：${base}；本脚本不会启动或重启服务。`)
  await page.goto(base)
  await poll(() => viteConnected, Boolean, { timeoutMs: 5000, intervalMs: 20 })
  assert.ok(viteConnected, 'Vite 模块传输连接已就绪')
  assert.equal(await page.locator('#fixture').count(), 1, '带查询版本的main模块也必须被替换，不能误加载业务应用')
  await page.evaluate(async () => {
    // Vite 会给组件依赖加当前构建的查询版本；host 必须复用组件实际使用的同一份 Vue
    // 响应式运行时，否则跨组件 reactive 更新不会触发 watch/computed，表现为第一个
    // persisted identity 到达后反馈恢复请求仍为 0。
    const componentSource = await fetch('/src/components/ai/ChatMessageList.vue').then((response) => response.text())
    const vueUrl = componentSource.match(/from "(\/node_modules\/\.vite\/deps\/vue\.js\?v=[^"]+)"/)?.[1] ?? '/node_modules/.vite/deps/vue.js'
    const { createApp, h, reactive, nextTick } = await import(vueUrl)
    const { default: pinia } = await import('/src/stores/index.ts')
    const { default: MessageList } = await import('/src/components/ai/ChatMessageList.vue')
    await import('/src/styles/index.css')
    const state = reactive({ sessionId: 'session-1', messages: [], streamingIndex: -1, visible: true })
    const app = createApp({ render: () => state.visible ? h(MessageList, state) : h('p', '已卸载') })
    app.use(pinia)
    app.mount('#fixture')
    window.feedbackFixture = { state, nextTick }
  })
  const set = (value) => page.evaluate(async (value) => { Object.assign(window.feedbackFixture.state, value); await window.feedbackFixture.nextTick() }, value)
  const messages = (id = 'a-1') => [{ id: 'u-1', role: 'user', content: '确定性问题' }, { id, role: 'assistant', content: '确定性回答' }]
  const feedback = () => page.locator('.message-card.assistant .message-actions button').last()
  check('空新会话不恢复反馈', calls.length === 0)
  await set({ messages: messages('assistant-123'), streamingIndex: 1 })
  check('流式占位消息不恢复反馈', calls.length === 0)
  await set({ streamingIndex: -1 })
  check('无持久身份的停止消息不恢复反馈', calls.length === 0)
  await set({ streamingIndex: 1 })
  await set({ messages: messages() })
  check('身份到达但流尚未收尾时不恢复反馈', calls.length === 0)
  await set({ streamingIndex: -1 })
  await feedback().filter({ hasText: /^点踩$/ }).waitFor()
  check('消息数不变的 done 身份替换触发恢复', calls.filter((call) => call.method === 'GET').length === 1)

  mode = 'failure'
  await set({ messages: messages('a-2') })
  await feedback().filter({ hasText: '反馈状态未知' }).waitFor()
  check('恢复失败明确显示未知并禁用点踩', await feedback().isDisabled())
  mode = 'empty'
  await page.getByRole('button', { name: '重新读取反馈' }).click()
  await feedback().filter({ hasText: /^点踩$/ }).waitFor()
  check('恢复失败可以重试', !(await feedback().isDisabled()))

  await feedback().click()
  await page.locator('.bad-case-reasons label').filter({ hasText: '事实错误' }).click()
  mode = 'held'
  await page.getByRole('button', { name: '提交反馈', exact: true }).click()
  await page.waitForFunction(() => document.querySelector('.bad-case-actions button[type="submit"]')?.disabled)
  check('提交期间禁用重复提交和反馈操作', await feedback().isDisabled())
  mode = 'failure'
  await release(null, 503)
  await feedback().filter({ hasText: '反馈状态未知' }).waitFor()
  check('提交与结果核对双失败不伪装未点踩', await feedback().isDisabled())

  mode = 'active'; active = 'a-2'
  await page.getByRole('button', { name: '重新读取反馈' }).click()
  await feedback().filter({ hasText: '已点踩' }).waitFor()
  mode = 'held'
  await feedback().click()
  await feedback().filter({ hasText: '撤销中' }).waitFor()
  check('撤销期间禁用重复操作', await feedback().isDisabled())
  mode = 'active'
  await release(null, 503)
  await feedback().filter({ hasText: '已点踩' }).waitFor()
  check('撤销失败按服务端状态保留已点踩', !(await feedback().isDisabled()))

  mode = 'held'
  await feedback().click()
  await feedback().filter({ hasText: '撤销中' }).waitFor()
  mode = 'empty'
  await set({ sessionId: 'session-2', messages: messages('a-3') })
  await feedback().filter({ hasText: /^点踩$/ }).waitFor()
  await release()
  check('切会话丢弃旧撤销结果', (await feedback().textContent()).trim() === '点踩')

  mode = 'held'
  await set({ sessionId: 'session-3', messages: messages('a-4') })
  await feedback().filter({ hasText: '读取反馈中' }).waitFor()
  mode = 'empty'
  await set({ sessionId: 'session-4', messages: messages('a-5') })
  await feedback().filter({ hasText: /^点踩$/ }).waitFor()
  await release([{ assistantMessageId: 'a-5', status: 'ACTIVE' }])
  check('切会话丢弃旧恢复结果', (await feedback().textContent()).trim() === '点踩')

  mode = 'held'
  await feedback().click()
  await page.locator('.bad-case-reasons label').filter({ hasText: '事实错误' }).click()
  await page.getByRole('button', { name: '提交反馈', exact: true }).click()
  await page.getByRole('button', { name: '提交中…', exact: true }).waitFor()
  mode = 'empty'
  await set({ sessionId: 'session-5', messages: messages('a-6') })
  await feedback().filter({ hasText: /^点踩$/ }).waitFor()
  await release()
  check('切会话丢弃旧提交结果并重置表单', (await feedback().textContent()).trim() === '点踩' && await page.locator('.bad-case-panel').count() === 0)

  mode = 'held'
  await set({ messages: messages('a-7') })
  await feedback().filter({ hasText: '读取反馈中' }).waitFor()
  await set({ visible: false })
  await release(null, 503)
  check('卸载后旧请求失败被处理且没有未捕获异常', errors.length === 0)
  mode = 'empty'
  await set({ visible: true })
  await feedback().filter({ hasText: /^点踩$/ }).waitFor()
  await feedback().click()
  await page.locator('.bad-case-reasons label').filter({ hasText: '事实错误' }).click()
  mkdirSync(resolve('.screenshots'), { recursive: true })
  await page.locator('#fixture').screenshot({ path: resolve('.screenshots/chat-feedback-ui.png') })
  check('全部反馈失败路径没有 pageerror', errors.length === 0)
  console.log(`反馈界面回归：${passed} 通过，0 失败；反馈请求 ${calls.length} 次，全部浏览器拦截。`)
} finally { await browser.close() }
