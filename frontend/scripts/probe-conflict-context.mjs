/**
 * 探针（U47）：冲突核对在「同一会话已有前文」时的命中率。
 *
 * <b>它不是验收脚本，不进 verify-all</b>——每一发都要真调模型（本脚本 4 发 = 8 次
 * 对话调用），跑进日常回归既慢又贵。它的用途只有一个：把 U47 的基线钉住，
 * 让「改扩写/改提示词之后冲突漏报有没有变好」这件事有同一把尺子量。
 *
 * 采样设计与 U47 记录的一致（不可改动，改了就换了一把尺子）：
 *   - 每发一个全新 session，第一问维生素 D、第二问铁——复现的是**真实顺序**，
 *     不是把铁问句单独直发（那种情况下模型 6/6 都报得出）；
 *   - 判据只看响应里的 `conflicts` 长度，不看措辞；
 *   - 每发一个新注册的一次性用户（注册接口），**不碰 admin**——重复跑不会污染
 *     任何账号的画像，也不会把测试数据堆到演示账号的「我的评价」上。
 *
 * 历史上钉过的基线（写在待改进清单 U47 里，此处只做复现）：
 *   无上下文直发 12/12；带上下文 3/4（小样本，本脚本默认跑 4 发）。
 *
 * 用法：
 *   node scripts/probe-conflict-context.mjs [发数，默认 4] [--stream]
 *   `--stream` 走 `/ai/chat/stream`（界面脚本走的那条路）；不带则走非流式 `/ai/chat`。
 *   两条路的提示词装配是同一套，但流式那支历史上观察到过一次漏报，所以要分开量。
 */
const GW = process.env.VERIFY_GW ?? 'http://localhost:8080'
const ARGS = process.argv.slice(2)
const STREAM = ARGS.includes('--stream')
const SHOTS = Number(ARGS.find((a) => /^\d+$/.test(a)) ?? 4)

/** 流式那一支：读到 `done` 事件为止，返回它带的那份完整 ChatResponse */
async function chatStream(body, token) {
  const res = await fetch(`${GW}/ai/chat/stream`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
      Authorization: `Bearer ${token}`,
    },
    body: JSON.stringify(body),
    signal: AbortSignal.timeout(300_000),
  })
  const reader = res.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let done = null
  for (;;) {
    const { value, done: finished } = await reader.read()
    if (finished) break
    buffer += decoder.decode(value, { stream: true })
    // SSE 以空行分隔事件；只关心 done 事件带的那份完整结果
    let sep
    while ((sep = buffer.indexOf('\n\n')) >= 0) {
      const raw = buffer.slice(0, sep)
      buffer = buffer.slice(sep + 2)
      const event = raw.match(/^event:\s*(\S+)/m)?.[1]
      const data = raw.match(/^data:\s*(.*)$/m)?.[1]
      if (event === 'done' && data) {
        done = JSON.parse(data)
      } else if (event === 'error') {
        throw new Error(`流式返回 error 事件：${data}`)
      }
    }
  }
  if (!done) throw new Error('流式结束却没有 done 事件')
  return { code: 200, msg: null, data: done }
}

async function post(path, body, token) {
  const res = await fetch(`${GW}${path}`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: JSON.stringify(body),
    signal: AbortSignal.timeout(180_000),
  })
  return res.json()
}

const username = `cfp${Date.now().toString().slice(-9)}`
const registered = await post('/auth/register', {
  username,
  password: 'verify12345',
  nickname: '冲突探针用户',
})
if (registered.code !== 200) throw new Error(`注册探针用户失败：${registered.msg}`)
const login = await post('/auth/login', { username, password: 'verify12345' })
const token = login?.data?.token
if (!token) throw new Error('登录探针用户失败')
console.log(
  `探针用户 ${username}，${SHOTS} 发（每发：维生素 D → 铁，${STREAM ? '流式' : '非流式'}）\n`,
)

const ask = STREAM ? (_path, body, token) => chatStream(body, token) : post

let hits = 0
for (let shot = 1; shot <= SHOTS; shot++) {
  const sessionId = `probe-conflict-${Date.now()}-${shot}`
  const t0 = Date.now()
  const first = await ask('/ai/chat', { sessionId, message: '维生素D每天推荐摄入多少？成年人上限是多少？' }, token)
  if (first.code !== 200) {
    console.log(`第 ${shot} 发：第一问失败 code=${first.code} ${first.msg}`)
    continue
  }
  const second = await ask('/ai/chat', { sessionId, message: '成年女性每天应该摄入多少铁？' }, token)
  if (second.code !== 200) {
    console.log(`第 ${shot} 发：第二问失败 code=${second.code} ${second.msg}`)
    continue
  }
  const conflicts = second.data?.conflicts ?? []
  const hit = conflicts.length > 0
  if (hit) hits += 1
  const detail = conflicts.map((c) => (c.detail ?? '').slice(0, 60)).join(' | ')
  console.log(
    `第 ${shot} 发：${hit ? '命中' : '\x1b[31m漏报\x1b[0m'} ` +
      `conflicts=${conflicts.length} 用时 ${((Date.now() - t0) / 1000).toFixed(0)}s` +
      (detail ? `\n         ${detail}` : ''),
  )
}

console.log(`\n结果：${hits}/${SHOTS} 命中（基线口径：无上下文 12/12、带上下文 3/4）`)
process.exit(0)
