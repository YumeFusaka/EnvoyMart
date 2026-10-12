import { test } from 'node:test'
import assert from 'node:assert/strict'
import { parameterHash, assertReceipt, restartVerified } from './write-operation-receipts.mjs'

const action = { tool: 'cart_add', operationId: 'op:1', parameters: { skuId: 7, quantity: 1 } }
const result = () => ({ status: 200, body: { code: 200, data: { quantity: 1 } }, headers: {
  'x-operation-id': 'op:1', 'x-operation-parameters-hash': parameterHash('u1',action.tool,action.parameters),
  'x-operation-receipt-version': '1', 'x-operation-replayed': 'true',
} })
test('对象键顺序保持哈希而身份工具或参数变化必然不同', () => {
  assert.equal(parameterHash('u1','cart_add',{quantity:1,skuId:7}), parameterHash('u1','cart_add',action.parameters))
  assert.notEqual(parameterHash('u2','cart_add',action.parameters), parameterHash('u1','cart_add',action.parameters))
  assert.notEqual(parameterHash('u1','cart_update',action.parameters), parameterHash('u1','cart_add',action.parameters))
})
test('四回执头或非删除业务数据缺失不能算成功', () => {
  assert.equal(assertReceipt(result(),action,'u1',true).length,64)
  for (const name of Object.keys(result().headers)) { const sample=result(); delete sample.headers[name]; assert.throws(() => assertReceipt(sample,action,'u1',true)) }
  const empty=result(); empty.body.data=null; assert.throws(() => assertReceipt(empty,action,'u1',true))
})
test('只凭单服务或同PID不能声明联合重启已执行', () => {
  const evidence={services:[{service:'order',beforePid:1,afterPid:2,afterReady:true},{service:'promotion',beforePid:3,afterPid:4,afterReady:true}]}
  assert.equal(restartVerified(evidence),true)
  evidence.services[1].afterPid=3; assert.equal(restartVerified(evidence),false)
  assert.equal(restartVerified({services:[evidence.services[0]]}),false)
})
