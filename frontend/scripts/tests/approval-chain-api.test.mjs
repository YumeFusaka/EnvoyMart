import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import vm from 'node:vm'

const source = await readFile(new URL('../verify-approval-chain.mjs', import.meta.url), 'utf8')
const start = source.indexOf('async function api(')
const end = source.indexOf('\n/**', start)
assert.ok(start >= 0 && end > start, '审批脚本 API 边界必须存在')

function client(status, payload) {
  const requests = []
  const context = vm.createContext({
    GW: 'http://验收桩',
    auth: { Authorization: 'Bearer alice' },
    fetch: async (url, options) => {
      requests.push({ url, options })
      return { status, json: async () => payload }
    },
  })
  // 只执行实际请求辅助函数，避免导入端到端脚本时触发登录、模型和订单写操作。
  vm.runInContext(`${source.slice(start, end)}\nglobalThis.api = api`, context)
  return { api: context.api, requests }
}

const rejectedMessage = '这次确认已提交、取消或被新消息替代，没有重复执行任何操作'
const expectedRejection = { expectedCode: 409, expectedMessage: rejectedMessage }

test('审批拒绝只接受 HTTP 200、业务 409 和精确契约文案', async () => {
  const payload = { code: 409, msg: rejectedMessage }
  assert.equal(await client(200, payload).api('/ai/chat', expectedRejection), payload)
})

test('业务成功不能被误判为重复审批拒绝', async () => {
  await assert.rejects(client(200, { code: 200, data: {} }).api('/ai/chat', expectedRejection), /契约不符/)
})

test('其他业务错误和拒绝文案不能被吞掉', async () => {
  for (const payload of [{ code: 500, msg: rejectedMessage }, { code: 409, msg: '其他冲突' }]) {
    await assert.rejects(client(200, payload).api('/ai/chat', expectedRejection), /契约不符/)
  }
})

test('HTTP 层失败不能当作预期业务拒绝', async () => {
  await assert.rejects(client(409, { code: 409, msg: rejectedMessage }).api('/ai/chat', expectedRejection), /HTTP=409/)
})

test('普通调用仍拒绝业务 409，管理员复读使用独立令牌', async () => {
  await assert.rejects(client(200, { code: 409, msg: rejectedMessage }).api('/ai/chat'), /契约不符/)
  const { api, requests } = client(200, { code: 200, data: { statusLogs: [] } })
  await api('/orders/admin/orders/1', { token: 'admin' })
  assert.equal(requests[0].options.headers.Authorization, 'Bearer admin')
})
