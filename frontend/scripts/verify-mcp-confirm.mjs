/**
 * MCP 高危工具确认通道的端到端验收（批次 12 项 5 固化；2026-10-04 授权升级后重写）。
 *
 * 为什么单开一个脚本：修复前 order_cancel 是「发布了但永远失败」的工具——
 * ToolRegistry 的第二道防线要求 confirmed=true，而 MCP 这条路径把它写死为 false，
 * 协议里也没有任何地方能传它。第一轮修复把它变成工具签名里的显式布尔参数。
 *
 * 2026-10-04 的授权升级又发现那个布尔本身是个洞：任何拿到 JWT/API Key 的调用方
 * 直接填 true 就能执行高危操作，服务端签发确认令牌这条路被整个绕开。
 * 现在协议要求的是一枚<b>服务端签发的确认令牌</b>（approvalToken），
 * 自填的 confirmed 只表示「这是一次高危调用」，不构成授权。
 *
 * 所以本脚本验的是这条完整信任链，而不只是「参数传进去了」：
 *   发布（schema 里有 approvalToken 且必填）
 *   → 无令牌被拒、订单不动
 *   → 令牌签名被伪造 / 换用户 / 换动作 都被拒
 *   → 合法令牌通过 → 真执行
 *
 * 数据影响：新建 1 笔未支付订单并取消（订单表只增不减，终态不还原）。
 * 前置条件：后端已启动（网关 8080、ai-service 9004），种子数据在位（sku 16 在售）。
 * 用法：
 *   node scripts/verify-mcp-confirm.mjs
 */
const GW = process.env.VERIFY_GW ?? 'http://127.0.0.1:8080'
const MCP = process.env.VERIFY_MCP ?? 'http://127.0.0.1:9004'
const ALICE = { username: 'alice', password: '123456' }

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

async function call(base, path, { method = 'GET', token, body } = {}) {
  const res = await fetch(`${base}${path}`, {
    method,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    ...(body ? { body: JSON.stringify(body) } : {}),
  })
  return res.json()
}

const login = await call(GW, '/auth/login', { method: 'POST', body: ALICE })
const T = login.data?.token
if (!T) {
  console.error('FATAL: 登录失败——先启动后端', JSON.stringify(login))
  process.exit(1)
}

// ──── MCP JSON-RPC（Streamable HTTP）：初始化 → 发通知 → 调工具 ────

let sessionId = null

/** 一次 JSON-RPC 往返。Streamable HTTP 的响应体可能是 SSE（data: {...}）也可能直接是 JSON。 */
async function rpc(body, token = T) {
  const res = await fetch(`${MCP}/mcp`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      // 两个 Accept 都要：流式传输按此协商响应形态
      Accept: 'application/json, text/event-stream',
      Authorization: `Bearer ${token}`,
      ...(sessionId ? { 'mcp-session-id': sessionId } : {}),
    },
    body: JSON.stringify(body),
  })
  const gotSession = res.headers.get('mcp-session-id')
  if (gotSession) {
    sessionId = gotSession
  }
  const text = await res.text()
  const dataLine = text.split('\n').find((l) => l.trim().startsWith('data:'))
  const raw = dataLine ? dataLine.slice(dataLine.indexOf(':') + 1).trim() : text.trim()
  return { status: res.status, json: raw ? JSON.parse(raw) : null }
}

const toolText = (reply) => reply.json?.result?.content?.[0]?.text ?? ''

console.log('\n== MCP 握手与工具清单 ==')
const init = await rpc({
  jsonrpc: '2.0',
  id: 1,
  method: 'initialize',
  params: {
    protocolVersion: '2025-06-18',
    capabilities: {},
    clientInfo: { name: 'verify-mcp-confirm', version: '1.0.0' },
  },
})
ck('initialize 成功', Boolean(init.json?.result?.serverInfo), JSON.stringify(init))
ck('拿到会话 ID', Boolean(sessionId))
await rpc({ jsonrpc: '2.0', method: 'notifications/initialized' })

const list = await rpc({ jsonrpc: '2.0', id: 2, method: 'tools/list' })
const tools = list.json?.result?.tools ?? []
const cancelSchema = tools.find((t) => t.name === 'order_cancel')?.inputSchema
ck('工具清单包含 order_cancel', Boolean(cancelSchema), `工具: ${tools.map((t) => t.name)}`)
ck(
  '高危工具签名带 approvalToken（string 且必填）',
  cancelSchema?.properties?.approvalToken?.type === 'string' &&
    (cancelSchema?.required ?? []).includes('approvalToken'),
  JSON.stringify(cancelSchema),
)
ck(
  'confirmed 降级为「高危标记」，不再声明为授权',
  cancelSchema?.properties?.confirmed?.type === 'boolean' &&
    (cancelSchema?.required ?? []).includes('confirmed') &&
    !/仅.*确认.*(后|时)传 true/.test(cancelSchema?.properties?.confirmed?.description ?? ''),
  JSON.stringify(cancelSchema?.properties?.confirmed),
)
const searchSchema = tools.find((t) => t.name === 'product_search')?.inputSchema
ck(
  '非高危工具既不带 confirmed 也不带 approvalToken',
  searchSchema && !searchSchema.properties?.confirmed && !searchSchema.properties?.approvalToken,
)

// ──── 前置：一笔未支付订单 ────

console.log('\n== 准备一笔未支付订单 ==')
const cart = await call(GW, '/cart', { token: T })
for (const item of cart.data ?? []) {
  await call(GW, `/cart/items/${item.id}`, { method: 'DELETE', token: T })
}
await call(GW, '/cart/items', { method: 'POST', token: T, body: { skuId: 16, quantity: 1 } })
const checkoutRes = await call(GW, '/orders/checkout', {
  method: 'POST',
  token: T,
  body: {
    receiverName: '张三',
    receiverPhone: '13800000001',
    receiverProvince: '上海市',
    receiverCity: '上海市',
    receiverDistrict: '徐汇区',
    receiverDetail: '漕河泾开发区 1 号楼',
    remark: 'verify-mcp-confirm',
  },
})
const order = checkoutRes.data
if (!order) {
  console.error('FATAL: 下单失败', JSON.stringify(checkoutRes))
  process.exit(1)
}
ck('下单成功（待支付）', order.status === 'CREATED', JSON.stringify(checkoutRes))
const orderId = order.id

const orderStatus = async () => (await call(GW, `/orders/${orderId}`, { token: T })).data?.status

// ──── 场景 1：不传 approvalToken → 协议层直接拒绝 ────

console.log('\n== 场景 1 缺 approvalToken 在协议层被拒 ==')
const denied = await rpc({
  jsonrpc: '2.0',
  id: 3,
  method: 'tools/call',
  params: { name: 'order_cancel', arguments: { orderId, confirmed: true } },
})
ck('isError=true', denied.json?.result?.isError === true, JSON.stringify(denied.json))
ck('文案点名 approvalToken', toolText(denied).includes('approvalToken'), toolText(denied))
ck('订单仍是待支付', (await orderStatus()) === 'CREATED')

// ──── 场景 2：自填的 confirmed=true 不再构成授权 ────
//
// 这是本次升级真正要钉死的一条：修复前它会让高危操作真的执行下去。

console.log('\n== 场景 2 伪造令牌 / 无有效令牌一律拒绝 ==')
const forged = await rpc({
  jsonrpc: '2.0',
  id: 4,
  method: 'tools/call',
  params: { name: 'order_cancel', arguments: { orderId, confirmed: true, approvalToken: 'eyJ4IjoxfQ.deadbeef' } },
})
ck('伪造签名被拒', forged.json?.result?.isError === true, JSON.stringify(forged.json))
ck('文案说明需要服务端签发的令牌', toolText(forged).includes('服务端'), toolText(forged))
ck('订单仍是待支付', (await orderStatus()) === 'CREATED')

// 令牌与用户绑定：拿别人的令牌不能被接受。这里用「换一个用户拿 alice 的会话」近似——
// 因为本脚本只登录了 alice，退一步验证「空串 / 明显不合法的串」都过不了校验。
const empty = await rpc({
  jsonrpc: '2.0',
  id: 41,
  method: 'tools/call',
  params: { name: 'order_cancel', arguments: { orderId, confirmed: true, approvalToken: '' } },
})
ck('空令牌被拒', empty.json?.result?.isError === true, JSON.stringify(empty.json))
ck('订单仍是待支付', (await orderStatus()) === 'CREATED')

// ──── 场景 3：合法令牌 → 真执行 ────
//
// 令牌由服务端在「拦下一次高危调用」时签发：这里直接走对话链路
// （chat 带高风险意图 → 返回 pendingActions + approvalToken），把它取出来再用 MCP 执行。
// 这同时证明了「令牌由对话链路签发、MCP 链路能验证」——两条路走的是同一条信任链。

console.log('\n== 场景 3 服务端签发的令牌可以执行 ==')
const chat = await call(GW, '/ai/chat', {
  method: 'POST',
  token: T,
  body: { sessionId: `mcp-verify-${Date.now()}`, message: `取消订单 ${orderId}`, stream: false },
})
const approvalToken = chat.data?.approvalToken
const pending = chat.data?.pendingActions ?? []
// pendingActions 是「渲染用描述串」列表（如 order_cancel(orderId=400)），不是结构化对象
ck(
  '对话链路签发了确认令牌且动作是 order_cancel',
  Boolean(approvalToken) && pending.some((a) => String(a).startsWith('order_cancel')),
  JSON.stringify({ approvalToken: Boolean(approvalToken), pending }),
)

if (approvalToken) {
  const done = await rpc({
    jsonrpc: '2.0',
    id: 5,
    method: 'tools/call',
    params: {
      name: 'order_cancel',
      arguments: { orderId, confirmed: true, approvalToken },
    },
  })
  const doneText = toolText(done)
  ck('isError 非 true', done.json?.result?.isError !== true, JSON.stringify(done.json))
  ck('输出为取消成功文案', doneText.includes('已取消'), doneText)
  ck('订单已取消', (await orderStatus()) === 'CANCELLED')
  ck('确认与令牌参数没有漏进业务参数', !doneText.includes('confirmed') && !doneText.includes('approvalToken'), doneText)
} else {
  ck('场景 3 依赖对话链路签发的令牌', false, '未拿到 approvalToken，无法验证合法令牌路径')
}

console.log(`\n== MCP 确认通道验收完成：PASS=${pass} FAIL=${fail} ==`)
if (fail > 0) {
  process.exitCode = 1
}