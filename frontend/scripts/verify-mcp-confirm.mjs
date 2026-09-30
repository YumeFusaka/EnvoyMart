/**
 * MCP 高危工具确认通道的端到端验收（批次 12 项 5 固化）。
 *
 * 为什么单开一个脚本：修复前 order_cancel 是「发布了但永远失败」的工具——
 * ToolRegistry 的第二道防线要求 confirmed=true，而 MCP 这条路径把它写死为 false，
 * 协议里也没有任何地方能传它。修复后确认信号是工具签名里的显式参数（confirmed），
 * 这条链路只有以真实 MCP 客户端的形态走一遍 JSON-RPC 才算验证：
 * 发布（tools/list 的 schema 里有 confirmed）→ 未确认被拒（第二道防线）→ 确认后真执行。
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
async function rpc(body) {
  const res = await fetch(`${MCP}/mcp`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      // 两个 Accept 都要：流式传输按此协商响应形态
      Accept: 'application/json, text/event-stream',
      Authorization: `Bearer ${T}`,
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
  '高危工具签名带 confirmed（boolean 且必填）',
  cancelSchema?.properties?.confirmed?.type === 'boolean' &&
    (cancelSchema?.required ?? []).includes('confirmed'),
  JSON.stringify(cancelSchema),
)
const searchSchema = tools.find((t) => t.name === 'product_search')?.inputSchema
ck('非高危工具不带 confirmed', searchSchema && !searchSchema.properties?.confirmed)

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
// 待支付态在订单接口里的枚举值是 CREATED（statusText「待支付」）
ck('下单成功（待支付）', order.status === 'CREATED', JSON.stringify(checkoutRes))
const orderId = order.id

const orderStatus = async () => (await call(GW, `/orders/${orderId}`, { token: T })).data?.status

// ──── 场景 1：不传 confirmed → 第二道防线拒绝，订单纹丝不动 ────

console.log('\n== 场景 1 未确认的取消被拒绝 ==')
const denied = await rpc({
  jsonrpc: '2.0',
  id: 3,
  method: 'tools/call',
  params: { name: 'order_cancel', arguments: { orderId } },
})
const deniedText = denied.json?.result?.content?.[0]?.text ?? ''
ck('isError=true', denied.json?.result?.isError === true, JSON.stringify(denied.json))
// 缺参在协议层就被 JSON Schema 校验拦下（MCP SDK 行为），文案点名 confirmed
ck('缺参被协议层校验拒绝', deniedText.includes('confirmed'), deniedText)
ck('订单仍是待支付', (await orderStatus()) === 'CREATED')

// ──── 场景 2：confirmed 传 false → 过得了协议层，倒在第二道防线 ────

console.log('\n== 场景 2 confirmed=false 仍被拒绝 ==')
const deniedFalse = await rpc({
  jsonrpc: '2.0',
  id: 4,
  method: 'tools/call',
  params: { name: 'order_cancel', arguments: { orderId, confirmed: false } },
})
const deniedFalseText = deniedFalse.json?.result?.content?.[0]?.text ?? ''
ck('isError=true', deniedFalse.json?.result?.isError === true)
// 场景 1 的拦截发生在 callHandler 之前，这条才真正验证 ToolRegistry 的第二道防线在 MCP 路径上生效
ck('文案出自第二道防线', deniedFalseText.includes('需要用户确认'), deniedFalseText)
ck('订单仍是待支付', (await orderStatus()) === 'CREATED')

// ──── 场景 3：confirmed=true → 真执行 ────

console.log('\n== 场景 3 确认后真取消 ==')
const done = await rpc({
  jsonrpc: '2.0',
  id: 5,
  method: 'tools/call',
  params: { name: 'order_cancel', arguments: { orderId, confirmed: true } },
})
const doneText = done.json?.result?.content?.[0]?.text ?? ''
ck('isError 非 true', done.json?.result?.isError !== true, JSON.stringify(done.json))
ck('输出为取消成功文案', doneText.includes('已取消'), doneText)
ck('订单已取消', (await orderStatus()) === 'CANCELLED')
ck('确认参数没有漏进业务参数', !doneText.includes('confirmed'), doneText)

console.log(`\n== MCP 确认通道验收完成：PASS=${pass} FAIL=${fail} ==`)
if (fail > 0) {
  process.exitCode = 1
}
