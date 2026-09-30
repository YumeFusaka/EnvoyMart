/**
 * 请求标识贯穿的验收（批次 7-D）。
 *
 * 这条链路全是「看起来对」的：接口 200、回答正常，日志里那串 id 有没有、是不是同一个，
 * 没有任何东西会因此报错。所以这里验的是四个**坏了也不报错**的性质：
 *
 *   一、进得来 —— 客户端带上的 id 要原样出现在响应头、响应体与后端日志里。
 *   二、走得远 —— 跨服务（ai-service → order-service）必须是同一个 id，
 *       否则「谁调了谁」还是只能靠时间戳猜。
 *   三、盖得住流式 —— SSE 跑在另一个线程上，MDC 不显式带过去就会断在这一段，
 *       而流式恰恰是日志最有价值的地方（模型往返、工具调用全在这里）。
 *   四、换得掉非法的 —— 带空格/超长的 id 必须被换掉，否则一个带换行的头
 *       就能往每一行日志里插伪造行。
 *
 * 前置条件：后端九个服务已启动，且日志落在 logs/logs-local/。
 * 用法：node scripts/verify-request-id.mjs
 */
import { logLines } from './lib/logs.mjs'

const GW = process.env.VERIFY_GW ?? 'http://localhost:8080'

let pass = 0
let fail = 0
function ck(name, condition, detail = '') {
  if (condition) {
    console.log(`  \x1b[32mPASS\x1b[0m ${name}`)
    pass += 1
  } else {
    console.log(`  \x1b[31mFAIL\x1b[0m ${name}${detail ? `\n        ${detail}` : ''}`)
    fail += 1
  }
}

const session = await (async () => {
  const res = await fetch(`${GW}/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: 'admin', password: '123456' }),
  })
  const body = await res.json()
  if (body.code !== 200) throw new Error(`登录失败：${body.msg}（后端起了吗）`)
  return body.data
})()

const stamp = Date.now()

// ─────────── 一、非流式：进得来 ───────────
console.log('\n一、非流式对话（客户端带 id）')
const mine = `verifyreq-${stamp}`
const res = await fetch(`${GW}/ai/chat`, {
  method: 'POST',
  headers: {
    'Content-Type': 'application/json',
    Authorization: `Bearer ${session.token}`,
    'X-Request-Id': mine,
  },
  body: JSON.stringify({ sessionId: `verify-reqid-${stamp}`, message: '请调用 product_search 工具搜索“乳清蛋白粉”' }),
})
const body = await res.json()
ck('响应头回写了同一个 id', res.headers.get('x-request-id') === mine, `实际：${res.headers.get('x-request-id')}`)
ck('响应体里带着同一个 id', body.data?.requestId === mine, `实际：${body.data?.requestId}`)

const aiLines = await logLines('ai-service', mine)
ck('ai-service 日志里有这个 id 的行', aiLines.length > 0, '一行都没有：MDC 没写进去')

// ─────────── 二、跨服务：走得远 ───────────
// 问题刻意点名了工具：工具是在计划步骤的另一个线程上执行的，而 MDC 是线程级的——
// 不显式带过去，Feign 出站就没有这个头，下游只能自己发一个新的，链路断在中间那一跳
console.log('\n二、跨服务（ai-service → product-service）')
const productLines = await logLines('product-service', mine)
ck(
  'product-service 日志里也是同一个 id',
  productLines.length > 0,
  '商品服务没有这个 id 的行：工具执行换了线程，日志上下文没带过去（或 Feign 出站没带上）',
)

// ─────────── 三、流式：盖得住另一个线程 ───────────
console.log('\n三、流式对话（SSE 跑在虚拟线程上）')
const streamId = `verifystream-${stamp}`
const streamRes = await fetch(`${GW}/ai/chat/stream`, {
  method: 'POST',
  headers: {
    'Content-Type': 'application/json',
    Authorization: `Bearer ${session.token}`,
    'X-Request-Id': streamId,
  },
  body: JSON.stringify({ sessionId: `verify-reqid-stream-${stamp}`, message: '乳清蛋白粉怎么选' }),
})
let done = null
const reader = streamRes.body.getReader()
const decoder = new TextDecoder()
let buffer = ''
while (true) {
  const { value, done: finished } = await reader.read()
  if (finished) break
  buffer += decoder.decode(value, { stream: true })
  const events = buffer.split('\n\n')
  buffer = events.pop() ?? ''
  for (const event of events) {
    if (event.includes('event:done')) {
      done = event
    }
  }
}
ck('流式收到了 done 事件', Boolean(done), '流没走完')
ck(
  '流式响应体里带着同一个 id',
  done?.includes(streamId),
  `done 事件：${(done ?? '').slice(0, 200)}`,
)

const streamAiLines = await logLines('ai-service', streamId)
ck('流式的日志里也有这个 id', streamAiLines.length > 0, '流式这一段断了线（MDC 没带进虚拟线程）')
ck(
  '模型调用那几行（[LLM]）同样带着 id',
  streamAiLines.some((line) => line.includes('[LLM]')),
  `命中的行：${streamAiLines.slice(0, 2).join(' | ').slice(0, 300)}`,
)

// ─────────── 四、缺失与非法 ───────────
console.log('\n四、缺失与非法标识')
const noId = await fetch(`${GW}/ai/chat`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${session.token}` },
  body: JSON.stringify({ sessionId: `verify-reqid-none-${stamp}`, message: '你好' }),
})
const noIdBody = await noId.json()
const generated = noId.headers.get('x-request-id')
ck('不带 id 时由服务端补一个', /^[0-9a-f]{16}$/.test(generated ?? ''), `实际：${generated}`)
ck('补出来的那个与响应体一致', noIdBody.data?.requestId === generated, `体里：${noIdBody.data?.requestId}`)

const bad = `bad id ${'x'.repeat(200)}`
const badRes = await fetch(`${GW}/ai/chat`, {
  method: 'POST',
  headers: {
    'Content-Type': 'application/json',
    Authorization: `Bearer ${session.token}`,
    'X-Request-Id': bad,
  },
  body: JSON.stringify({ sessionId: `verify-reqid-bad-${stamp}`, message: '你好' }),
})
const badBody = await badRes.json()
ck(
  '非法 id 被换掉，不会进日志',
  badBody.data?.requestId !== bad && /^[0-9a-f]{16}$/.test(badBody.data?.requestId ?? ''),
  `实际：${badBody.data?.requestId}`,
)

console.log(`\n${pass} 通过 / ${fail} 失败`)
process.exit(fail === 0 ? 0 : 1)
