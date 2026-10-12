import test from 'node:test'
import assert from 'node:assert/strict'
import { createServer } from 'node:http'
import { chromium } from 'playwright-core'
import { installBrowserSseCapture, parseSseDone } from './public-api-evidence.mjs'

test('真实Chrome原Fetch和透明clone保留UTF-8，原请求和响应不被模拟替换', async () => {
  const done = { requestId: 'real-browser-server-id', pendingActionDetails: [{ tool: 'ticket_create', arguments: { title: '页面工单' } }] }
  const received = []
  const server = createServer((request, response) => {
    received.push({ method: request.method, path: request.url, header: request.headers['x-request-id'] })
    if (request.url === '/ai/chat/stream') { response.writeHead(200, { 'content-type': 'text/event-stream' }); response.end('event: done\ndata: ' + JSON.stringify(done) + '\n\n') }
    else { response.writeHead(200, { 'content-type': 'text/html' }); response.end('<!doctype html><title>真实传输验证</title>') }
  })
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  const browser = await chromium.launch({ executablePath: process.env.PLAYWRIGHT_CHROMIUM ?? 'C:/Program Files/Google/Chrome/Application/chrome.exe' })
  try {
    const context = await browser.newContext()
    await installBrowserSseCapture(context)
    const page = await context.newPage()
    let cdp
    page.on('response', async (response) => { if (new URL(response.url()).pathname === '/ai/chat/stream') cdp = parseSseDone(await response.body()) })
    await page.goto('http://127.0.0.1:' + server.address().port)
    const actual = await page.evaluate(async () => {
      const response = await fetch('/ai/chat/stream', { method: 'POST', headers: { 'X-Request-Id': 'real-client-id' }, body: '真实请求' })
      return await response.text()
    })
    await page.waitForFunction(() => window.__verifyLiveSse.length > 0)
    const cloned = await page.evaluate(() => window.__verifyLiveSse[0])
    assert.deepEqual(parseSseDone(Buffer.from(actual)), done)
    assert.deepEqual(cloned.data, done)
    assert.deepEqual(received.filter((entry) => entry.path === '/ai/chat/stream'), [{ method: 'POST', path: '/ai/chat/stream', header: 'real-client-id' }])
    assert.equal(cloned.httpStatus, 200)
    console.log('真实SSE原Fetch/clone中文正确；CDP中文=' + (cdp?.pendingActionDetails?.[0]?.arguments.title ?? '未返回'))
  } finally { await browser.close(); await new Promise((resolve) => server.close(resolve)) }
})
