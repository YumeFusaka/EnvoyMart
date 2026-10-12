import {test} from 'node:test'
import assert from 'node:assert/strict'
import * as phase1 from './write-receipt-phase1.mjs'
import {assessPhase1,validateCheckoutSignature,hasGlobalRollbackDecision,reassessLegacyPhase1} from './write-receipt-phase1.mjs'
const facts=()=>({mode:'commit',before:{stock:10,cart:[['1','1','1']]},during:{engine:'MySQL',receiptCount:1,completeCount:1,orderCount:1,undo:{order:[{xid:'x'}],product:[{xid:'x'}]}},
  after:{receiptCount:1,completeCount:1,orderCount:1,stock:9,cart:[],undo:{order:[],product:[]}},held:{kind:'GLOBAL_COMMIT_HELD',xid:'x'},
  recoveryDuring:{operationState:'UNKNOWN',modelCalls:0,original:true},recoveryAfter:{operationState:'COMMITTED',modelCalls:0,original:true,receiptReplayed:true},
  logs:{services:{order:['commit status: Committed'],product:[]}}})
test('两库阶段一原回执已落库且全局未决只读UNKNOWN，提交后恢复原回执',()=>assert.equal(assessPhase1(facts()).status,'PASS'))
test('数据库已存在行不能单独证明全局可重放；误报成功必须FAIL',()=>{const f=facts();f.recoveryDuring.operationState='COMMITTED';assert.equal(assessPhase1(f).status,'FAIL')})
test('缺阶段一undo或零模型运行证据严格BLOCKED',()=>{const f=facts();f.during.undo.order=[];assert.equal(assessPhase1(f).status,'BLOCKED');const g=facts();g.recoveryDuring.modelCalls=null;assert.equal(assessPhase1(g).status,'BLOCKED')})
test('超时真实全局回滚后订单和回执消失，库存购物车复原',()=>{const f=facts();f.mode='rollback';Object.assign(f.after,{receiptCount:0,completeCount:0,orderCount:0,stock:10,cart:f.before.cart});f.recoveryAfter.operationState='NOT_FOUND';f.logs.services={order:['rollback status: TimeoutRollbacked'],product:['branch rollback success']};assert.equal(assessPhase1(f).status,'PASS')})
test('签名参数必须匹配本地实验输入，外部新增字段和参数变更拒绝',()=>{
  const expected={receiverName:'验收',receiverPhone:'13800000000'}
  const response=(p)=>({approvalToken:Buffer.from(JSON.stringify({a:[{t:'cart_checkout',o:'op:1',p}]})).toString('base64url')+'.sig',usage:{totalTokens:1},pendingActionDetails:[{tool:'cart_checkout'}]})
  assert.equal(validateCheckoutSignature(response(expected),expected).operationId,'op:1')
  assert.throws(()=>validateCheckoutSignature(response({...expected,command:'x'}),expected));assert.throws(()=>validateCheckoutSignature(response({...expected,receiverName:'另一人'}),expected))
})
test('缓留提交后TC返回TimeoutRollbacked也是真实全局回滚决议，进行中状态不算',()=>{
  assert.equal(hasGlobalRollbackDecision(['commit status: TimeoutRollbacked']),true)
  assert.equal(hasGlobalRollbackDecision(['commit status: TimeoutRollbacking']),false)
  assert.equal(hasGlobalRollbackDecision(['commit status: AsyncCommitting']),false)
})
test('TC同XID明确回滚终态可结算但别的XID或只有分支日志不算',()=>{
  const f=facts();f.mode='rollback';Object.assign(f.after,{receiptCount:0,completeCount:0,orderCount:0,stock:10,cart:f.before.cart});f.recoveryAfter.operationState='NOT_FOUND'
  f.logs.services={order:['commit status: Finished'],product:['branch rollback success']};f.logs.tc={xid:'x',rolledBack:true}
  assert.equal(assessPhase1(f).status,'PASS');f.logs.tc.xid='other';assert.equal(assessPhase1(f).status,'BLOCKED')
  f.logs.tc={xid:'x',rolledBack:false};assert.equal(assessPhase1(f).status,'BLOCKED')
})
test('TC同XID明确全局提交终态可结算，缺失或错XID不可通过',()=>{
  const f=facts();f.logs.services.order=[];f.logs.tc={xid:'x',committed:true}
  assert.equal(assessPhase1(f).status,'PASS')
  f.logs.tc.xid='other';assert.equal(assessPhase1(f).status,'BLOCKED')
  f.logs.tc={xid:'x',committed:false};assert.equal(assessPhase1(f).status,'BLOCKED')
})
test('旧报告重判只纠正kind覆盖且缺原帧哈希或恢复证据仍BLOCKED',()=>{
  const f={...facts(),version:'seata-phase1-v1',status:'BLOCKED',xid:'x',orderRestoration:{ready:true,seataServer:'127.0.0.1:8091'},proxyEvents:[{kind:'ARMED',xid:'x'},
    {kind:'GLOBAL_COMMIT',xid:'x',type:7,transport:0,codec:1,compressor:0,digest:'a'.repeat(64),id:1},{kind:'GLOBAL_COMMIT',xid:'x',type:7,id:1,forwarded:1,reason:'EXPLICIT_RELEASE'}]}
  assert.equal(reassessLegacyPhase1(f).status,'PASS');f.proxyEvents[1].digest=null;assert.equal(reassessLegacyPhase1(f).status,'BLOCKED')
})
test('真实TimeoutRollbackFailed必须FAIL而不是undo未结算BLOCKED',()=>{
  const f=facts();f.mode='rollback';f.logs.tc={xid:'x',lines:['The Global session x has changed the status to TimeoutRollbackFailed']};f.after.undo.order=[{xid:'x'}]
  assert.equal(assessPhase1(f).status,'FAIL')
})
test('回滚采样先库存旧值后undo零不能提前终止，全部业务复原才终止',()=>{
  const before={stock:495,cart:[['29','1','1']]}
  const after={stock:494,cart:before.cart,receiptCount:0,completeCount:0,orderCount:0,orderRows:[],outbox:[],undo:{order:[],product:[]}}
  assert.equal(phase1.hasExpectedDatabaseOutcome({mode:'rollback',before,after}),false)
  after.stock=495
  assert.equal(phase1.hasExpectedDatabaseOutcome({mode:'rollback',before,after}),true)
  after.cart=[]
  assert.equal(phase1.hasExpectedDatabaseOutcome({mode:'rollback',before,after}),false)
})
test('提交必须库存扣减、购物车清空和完整回执一起稳定，有券回滚还必须券复原',()=>{
  const f=facts()
  assert.equal(phase1.hasExpectedDatabaseOutcome(f),true)
  f.after.stock=10
  assert.equal(phase1.hasExpectedDatabaseOutcome(f),false)
  f.mode='rollback';Object.assign(f.before,{coupon:{status:'UNUSED',orderNo:null,usedAtPresent:false}})
  Object.assign(f.after,{stock:10,receiptCount:0,completeCount:0,orderCount:0,cart:f.before.cart,outbox:[],promotionUndo:[],coupon:{status:'USED',orderNo:'o1',usedAtPresent:true}})
  assert.equal(phase1.hasExpectedDatabaseOutcome(f),false)
  f.after.coupon={...f.before.coupon}
  assert.equal(phase1.hasExpectedDatabaseOutcome(f),true)
  f.after.promotionUndo=[{xid:'x'}]
  assert.equal(phase1.hasExpectedDatabaseOutcome(f),false)
})
